package dev.fluwy.app.diag

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.io.File
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/** Redaction helpers shared by persistence, UI copy and export. Bodies and header values are never accepted. */
object ConnectorDebugRedactor {
    private val sensitivePair = Regex(
        "(?i)(authorization|proxy-authorization|cookie|set-cookie|password|passwd|token|secret|api[_-]?key|otp|access[_-]?token|refresh[_-]?token|session[_-]?id)(\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:bearer\\s+)?[^\\s,;\\\"']+"
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val opaque = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_-]{40,}(?![A-Za-z0-9])")
    private val urlWithQuery = Regex("(?i)(https?://[^\\s?#]+)\\?[^\\s#]*")
    private val unsafePathSegment = Regex("(?i)^(?:[A-F0-9]{24,}|[A-Za-z0-9_-]{32,}|[^/]*@[^/]*)$")

    fun endpoint(raw: String?): String {
        if (raw.isNullOrBlank()) return "endpoint=[indisponível]"
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: return sanitizeText(raw.substringBefore('?')).take(240)
        val scheme = uri.scheme?.lowercase()?.takeIf { it in setOf("http", "https", "ws", "wss") } ?: "unknown"
        val host = uri.host?.lowercase()?.take(180) ?: "[host-redigido]"
        val rawPath = uri.rawPath.orEmpty().ifBlank { "/" }.take(600)
        var redactNext = false
        val path = rawPath.split('/').mapIndexed { index, segment ->
            if (index == 0 || segment.isEmpty()) return@mapIndexed segment
            val out = if (redactNext || unsafePathSegment.matches(segment)) "[redigido]" else segment
            redactNext = segment.matches(Regex("(?i)^(token|secret|password|passwd|key|api[-_]?key|auth|session|email|user|users|account|accounts|message|messages|thread|threads|file|files|document|documents|position|positions|order|orders|balance|balances|trade|trades|transaction|transactions|payment|payments|wallet|wallets|contact|contacts|conversation|conversations|label|labels)$"))
            out
        }.joinToString("/")
        return "$scheme://$host$path".take(800)
    }

    fun authType(headerNames: Iterable<String>): String {
        val names = headerNames.map { it.lowercase() }.toSet()
        return when {
            "proxy-authorization" in names -> "proxy-auth"
            "authorization" in names -> "authorization"
            names.any { it.contains("api-key") || it.contains("api_key") || it == "x-key" } -> "api-key"
            "cookie" in names || "set-cookie" in names -> "cookie"
            else -> "none"
        }
    }

    fun sanitizeText(value: String, secrets: Iterable<String> = emptyList()): String {
        var out = value
        secrets.filter { it.length >= 4 }.forEach { secret -> out = out.replace(secret, "[redigido]") }
        out = urlWithQuery.replace(out) { it.groupValues[1] }
        out = sensitivePair.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}[redigido]" }
        out = bearer.replace(out, "Bearer [redigido]")
        out = email.replace(out, "[e-mail redigido]")
        out = opaque.replace(out, "[valor opaco redigido]")
        return out.take(2_000)
    }
}

/** Rotating, bounded diagnostic files. Files are namespaced by connector id and contain redacted metadata only. */
class ConnectorDebugLog(private val directory: File) {
    companion object {
        private const val PREFIX = "connector-"
        private const val SUFFIX = ".debug.log"
        private const val MAX_FILE_BYTES = 128 * 1024L
        private const val KEEP_AFTER_ROTATE_BYTES = 64 * 1024
        private const val MAX_TOTAL_BYTES = 2 * 1024 * 1024L
        private const val MAX_READ_CHARS = 80_000
    }

    private val lock = Any()
    private val secretsById = ConcurrentHashMap<String, Set<String>>()
    private val eventFlows = ConcurrentHashMap<String, MutableSharedFlow<Long>>()

    fun rememberSecrets(connectorId: String, secrets: Iterable<String>) {
        val safeId = id(connectorId)
        val newValues = secrets.filter { it.length >= 4 }.toSet()
        if (newValues.isNotEmpty()) secretsById.compute(safeId) { _, old -> (old.orEmpty() + newValues).toList().takeLast(80).toSet() }
    }

    fun events(connectorId: String): SharedFlow<Long> = eventFlows.computeIfAbsent(id(connectorId)) {
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    fun recordHttp(
        connectorId: String,
        method: String,
        url: String,
        headerNames: Iterable<String>,
        durationMs: Long,
        statusCode: Int?,
        summary: String,
    ) {
        val verb = method.uppercase().takeIf { it.matches(Regex("[A-Z]{3,10}")) } ?: "HTTP"
        val status = statusCode?.toString() ?: "sem resposta"
        append(connectorId, "HTTP ${ConnectorDebugRedactor.endpoint(url)} method=$verb auth=${ConnectorDebugRedactor.authType(headerNames)} status=$status duration_ms=${durationMs.coerceAtLeast(0)} summary=$summary")
    }

    fun recordFailure(connectorId: String, category: String, error: Throwable, endpoint: String? = null, method: String? = null, durationMs: Long? = null) {
        val type = when (error) {
            is SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
            is UnknownHostException -> "dns"
            is SSLException -> "tls"
            is java.io.IOException -> "network-io"
            else -> error.javaClass.simpleName.take(80).ifBlank { "error" }
        }
        val methodField = method?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{3,10}")) }?.let { " method=$it" }.orEmpty()
        val durationField = durationMs?.let { " duration_ms=${it.coerceAtLeast(0)}" }.orEmpty()
        val endpointField = endpoint?.let { " ${ConnectorDebugRedactor.endpoint(it)}" }.orEmpty()
        append(connectorId, "ERROR category=${category.take(40)} type=$type$methodField$endpointField$durationField")
    }

    fun recordEvent(connectorId: String, category: String, summary: String, endpoint: String? = null) {
        val endpointField = endpoint?.let { " ${ConnectorDebugRedactor.endpoint(it)}" }.orEmpty()
        append(connectorId, "EVENT category=${category.take(40)}$endpointField summary=$summary")
    }

    fun read(connectorId: String): String = synchronized(lock) {
        val file = fileFor(connectorId)
        val raw = runCatching { if (file.isFile) file.readText().takeLast(MAX_READ_CHARS) else "" }.getOrDefault("")
        ConnectorDebugRedactor.sanitizeText(raw, secretsById[id(connectorId)].orEmpty())
    }

    fun clear(connectorId: String) = synchronized(lock) { runCatching { fileFor(connectorId).delete() } }

    private fun append(connectorId: String, message: String) {
        val safeId = id(connectorId)
        val secrets = secretsById[safeId].orEmpty()
        val safeMessage = ConnectorDebugRedactor.sanitizeText(message, secrets).replace('\n', ' ').replace('\r', ' ')
        val line = "${Instant.now()} $safeMessage\n"
        runCatching {
            synchronized(lock) {
                if (!directory.exists()) directory.mkdirs()
                val file = fileFor(safeId)
                file.appendText(line)
                if (file.length() > MAX_FILE_BYTES) {
                    val tail = file.readText().takeLast(KEEP_AFTER_ROTATE_BYTES)
                    file.writeText("[log rotacionado; entradas antigas removidas]\n$tail")
                }
                trimTotal()
            }
            eventFlows[safeId]?.tryEmit(System.nanoTime())
        }
    }

    private fun trimTotal() {
        val files = directory.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            ?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (old in files) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= old.length()
            old.delete()
        }
    }

    private fun fileFor(connectorId: String): File = File(directory, "$PREFIX${id(connectorId)}$SUFFIX")
    private fun id(value: String): String = value.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(80).ifBlank { "unknown" }
}
