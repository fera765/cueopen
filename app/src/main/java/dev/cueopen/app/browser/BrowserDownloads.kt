package dev.cueopen.app.browser

import android.webkit.CookieManager
import android.webkit.URLUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads do navegador do agente (OpenBot 0.73): o arquivo vai para a pasta downloads/ do workspace da tarefa
 * e o agente é avisado no próximo resultado de ferramenta (nome, tamanho e caminho, ou o motivo da falha).
 * Limites: [MAX_ACTIVE] downloads ao mesmo tempo e [MAX_BYTES] por arquivo.
 */
object BrowserDownloads {
    const val MAX_ACTIVE = 4
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private val active = AtomicInteger(0)
    private val events = ConcurrentHashMap<String, MutableList<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun event(sessionId: String, text: String) {
        events.getOrPut(sessionId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(text)
    }

    /** Avisos pendentes para o agente desta tarefa (e limpa a fila). */
    fun drain(sessionId: String): String? {
        val list = events.remove(sessionId) ?: return null
        val lines = synchronized(list) { list.toList() }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { "[Download] $it" }
    }

    /** Nome seguro e sem sobrescrever: arquivo.pdf, arquivo (2).pdf… */
    fun uniqueFile(dir: File, raw: String): File {
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifBlank { "download" }.take(120)
        var f = File(dir, clean)
        if (!f.exists()) return f
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var i = 2
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    fun tooBig(contentLength: Long): Boolean = contentLength > MAX_BYTES

    fun start(sessionId: String, workspace: File, guestWorkspace: String, url: String, userAgent: String?, contentDisposition: String?, mime: String?, contentLength: Long) {
        val name = runCatching { URLUtil.guessFileName(url, contentDisposition, mime) }.getOrDefault("download")
        if (tooBig(contentLength)) {
            event(sessionId, "parado: \"$name\" tem mais de 2 GiB. Nenhum arquivo foi salvo; o usuário pode baixar pelo navegador dele."); return
        }
        if (url.startsWith("blob:")) {
            event(sessionId, "\"$name\" é gerado pela página (blob:) e não pode ser baixado pelo app. Procure um link direto do arquivo ou leia o conteúdo na página."); return
        }
        if (active.incrementAndGet() > MAX_ACTIVE) {
            active.decrementAndGet()
            event(sessionId, "recusado: já há $MAX_ACTIVE downloads em andamento. Espere terminarem e tente de novo."); return
        }
        scope.launch {
            val dir = File(workspace, "downloads").apply { mkdirs() }
            val out = uniqueFile(dir, name)
            try {
                if (url.startsWith("data:")) {
                    val payload = url.substringAfter(',', "")
                    val bytes = if (url.substringBefore(',').endsWith(";base64")) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                        else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
                    out.writeBytes(bytes)
                } else {
                    val req = Request.Builder().url(url).apply {
                        userAgent?.let { header("User-Agent", it) }
                        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.let { header("Cookie", it) }
                    }.build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("resposta vazia")
                        if (tooBig(body.contentLength())) throw IllegalStateException("arquivo maior que 2 GiB")
                        var total = 0L
                        body.byteStream().use { input ->
                            out.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    total += n
                                    if (total > MAX_BYTES) throw IllegalStateException("arquivo maior que 2 GiB")
                                    o.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
                event(sessionId, "concluído: salvo em $guestWorkspace/downloads/${out.name} (${out.length()} bytes).")
            } catch (t: Throwable) {
                out.delete()
                event(sessionId, "falhou: \"$name\" não foi salvo (${t.message ?: t.javaClass.simpleName}).")
                runCatching { dev.cueopen.app.diag.ErrorLog.log("browser", "Download falhou: $url — ${t.message}", sessionId) }
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
