package dev.cueopen.app.browser

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Ponte "__fluwyFs" do WebView das Web APIs: arquivos na área de trabalho de quem chamou e stream de pedaços.
 * Segurança: só responde com o token de uso único da chamada em andamento (o script do site não o enxerga:
 * ele vive só no escopo da função da API) e só dentro da pasta raiz da chamada (sem "..", sem .cue).
 */
object WebApiFs {
    private const val MAX_BYTES = 30L * 1024 * 1024
    @Volatile private var token: String? = null
    @Volatile private var root: File? = null
    @Volatile private var onEmit: ((String) -> Unit)? = null

    fun begin(dir: File, emit: ((String) -> Unit)?): String {
        dir.mkdirs()
        root = dir.canonicalFile; onEmit = emit
        return java.util.UUID.randomUUID().toString().also { token = it }
    }
    fun end() { token = null; root = null; onEmit = null }

    private fun err(m: String) = buildJsonObject { put("ok", false); put("error", m) }.toString()
    private fun resolve(tok: String?, path: String): File {
        val r = root
        if (tok == null || tok != token || r == null) throw SecurityException("token inválido (só durante a chamada da Web API)")
        val rel = path.trim().removePrefix("/root/workspace").trimStart('/').replace('\\', '/')
        val f = File(r, rel).canonicalFile
        if (f.path != r.path && !f.path.startsWith(r.path + File.separator)) throw SecurityException("caminho fora da área de trabalho: $path")
        if (f.relativeTo(r).path.split(File.separatorChar).any { it == ".cue" }) throw SecurityException("pasta interna protegida")
        return f
    }
    private fun rel(f: File) = f.relativeTo(root!!).path

    private inline fun guard(block: () -> String): String = try { block() } catch (e: Throwable) { err(e.message ?: e.toString()) }

    @JavascriptInterface fun write(tok: String?, path: String, data: String, base64: Boolean, append: Boolean): String = guard {
        val f = resolve(tok, path)
        if (f.isDirectory) return@guard err("é uma pasta: $path")
        val bytes = if (base64) android.util.Base64.decode(data, android.util.Base64.DEFAULT) else data.toByteArray()
        if (bytes.size + (if (append && f.exists()) f.length() else 0) > MAX_BYTES) return@guard err("arquivo maior que 30 MB")
        f.parentFile?.mkdirs()
        if (append) f.appendBytes(bytes) else f.writeBytes(bytes)
        buildJsonObject { put("ok", true); put("path", rel(f)); put("size", f.length()) }.toString()
    }

    @JavascriptInterface fun read(tok: String?, path: String, mode: String): String = guard {
        val f = resolve(tok, path)
        if (!f.isFile) return@guard err("arquivo não encontrado: $path")
        if (f.length() > MAX_BYTES) return@guard err("arquivo maior que 30 MB")
        val b = f.readBytes()
        val data = when (mode) {
            "base64" -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
            "dataurl" -> "data:" + (java.net.URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream") + ";base64," +
                android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
            else -> b.decodeToString()
        }
        buildJsonObject { put("ok", true); put("data", data) }.toString()
    }

    @JavascriptInterface fun list(tok: String?, path: String): String = guard {
        val d = resolve(tok, path)
        if (!d.isDirectory) return@guard err("pasta não encontrada: ${path.ifBlank { "." }}")
        val files = d.listFiles().orEmpty().filter { it.name != ".cue" }.sortedBy { it.name }.take(500).map { f ->
            buildJsonObject { put("name", f.name); put("path", rel(f)); put("dir", f.isDirectory); put("size", if (f.isFile) f.length() else 0); put("modified", f.lastModified()) }
        }
        buildJsonObject { put("ok", true); put("files", JsonArray(files)) }.toString()
    }

    @JavascriptInterface fun exists(tok: String?, path: String): String = guard {
        buildJsonObject { put("ok", true); put("exists", resolve(tok, path).exists()) }.toString()
    }

    @JavascriptInterface fun mkdir(tok: String?, path: String): String = guard {
        val d = resolve(tok, path); d.mkdirs()
        buildJsonObject { put("ok", d.isDirectory); put("path", rel(d)) }.toString()
    }

    @JavascriptInterface fun emit(tok: String?, part: String): String = guard {
        if (tok == null || tok != token) return@guard err("token inválido")
        onEmit?.invoke(part)
        buildJsonObject { put("ok", true) }.toString()
    }

    fun json(s: String) = JsonPrimitive(s)
}
