package dev.cueopen.app.browser

import dev.cueopen.app.CueApp
import dev.cueopen.app.data.UserScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Instala um userscript no padrão Tampermonkey (colado, importado de .js ou criado pela IA). */
object UserScriptInstaller {
    data class Result(val script: UserScript?, val error: String?, val updated: Boolean = false)

    private const val MAX_REQUIRE = 2 * 1024 * 1024

    suspend fun install(code: String, source: String): Result {
        val clean = code.removePrefix("﻿")
        if (clean.length > BrowserPolicies.MAX_SCRIPT_CHARS) return Result(null, "Script grande demais (máx. ${BrowserPolicies.MAX_SCRIPT_CHARS / 1000} mil caracteres).")
        val (meta, err) = UserScriptMeta.parse(clean)
        if (meta == null) return Result(null, err)
        val requireCode = if (meta.requires.isEmpty()) "" else runCatching { downloadRequires(meta.requires) }.getOrElse {
            return Result(null, "Não consegui baixar um @require: ${it.message}")
        }
        val store = CueApp.instance.store
        val existing = BrowserPolicies.findExisting(meta, store.state.value.userScripts)
        val script = BrowserPolicies.fromMeta(meta, clean, requireCode, source, existing)
        store.upsertUserScript(script)
        return Result(script, null, updated = existing != null)
    }

    suspend fun downloadRequires(urls: List<String>): String = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        val out = StringBuilder()
        for (u in urls.take(10)) {
            BrowserPolicies.gmRequestDenial(u)?.let { error("$u ($it)") }
            client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) error("$u respondeu HTTP ${r.code}")
                val text = r.body?.string().orEmpty()
                if (out.length + text.length > MAX_REQUIRE) error("os @require passam de 2 MB")
                out.append("\n// @require ").append(u).append('\n').append(text).append("\n;")
            }
        }
        out.toString()
    }
}
