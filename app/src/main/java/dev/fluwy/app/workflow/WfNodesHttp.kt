package dev.fluwy.app.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Requisição HTTP (com retentativa de rede embutida para métodos idempotentes).

internal suspend fun nodeHttp(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return withContext(Dispatchers.IO) {
                val method = r("method").ifBlank { "GET" }
                if (run.trigger == "test" && WfQuality.isExternalSend("http", node.config, method)) {
                    val url = r("url").trim()
                    if (!Regex("^https?://[^\\s/]+\\.[^\\s]+").containsMatchIn(url)) return@withContext NodeResult(error = "URL inválida: ${url.take(200)}")
                    return@withContext simulated(node, "${method.uppercase()} $url", r("body"))
                }
                var res = httpCall(method, r("url").trim(), r("headers"), r("body"), (r("timeout").toIntOrNull() ?: 30).coerceIn(1, 300))
                var attempt = 0
                while (WfRetry.shouldRetry(method, res.error, attempt)) {
                    kotlinx.coroutines.delay(WfRetry.backoffMs(attempt)); attempt++
                    res = httpCall(method, r("url").trim(), r("headers"), r("body"), (r("timeout").toIntOrNull() ?: 30).coerceIn(1, 300))
                }
                // Sem rede: status 0 junto do erro (com "continuar em caso de erro" vira dado para um nó Condição decidir).
                if (res.error != null && res.output.isBlank()) res.copy(output = buildJsonObject { put("status", 0) }.toString()) else res
            }
}

internal fun httpCall(method: String, url: String, headers: String, body: String, timeoutSec: Int): NodeResult {
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return NodeResult(error = "URL inválida: \"$url\" (use http:// ou https://).")
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    return try {
        c.requestMethod = method.uppercase()
        c.connectTimeout = 15_000; c.readTimeout = timeoutSec * 1_000; c.instanceFollowRedirects = true
        headers.lines().map { it.trim() }.filter { it.contains(':') }.forEach { c.setRequestProperty(it.substringBefore(':').trim(), it.substringAfter(':').trim()) }
        if (body.isNotBlank() && method.uppercase() != "GET") {
            c.doOutput = true
            if (c.getRequestProperty("Content-Type") == null)
                c.setRequestProperty("Content-Type", if (body.trim().startsWith("{") || body.trim().startsWith("[")) "application/json" else "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(200_000)
        val data = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val out = buildJsonObject { put("status", code); put("body", if (WfFeed.isFeed(text)) text.take(4_000) else text.take(50_000)); if (data != null) put("data", data)
            val feed = if (data == null && WfFeed.isFeed(text)) WfFeed.parse(text, 30) else emptyList()
            if (feed.isNotEmpty()) { put("items", WfFeed.toJson(feed)); put("text", WfFeed.toText(feed)); put("count", feed.size) }
            else if (data == null && Regex("<(html|body|div|p)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(text)) put("text", WfLogic.htmlText(text).take(20_000)) }.toString()
        if (code >= 400) NodeResult(out, error = "HTTP $code: ${text.take(400)}") else NodeResult(out)
    } catch (e: Exception) {
        NodeResult(error = "Falha de rede: ${e.message ?: e.javaClass.simpleName}")
    } finally { c.disconnect() }
}