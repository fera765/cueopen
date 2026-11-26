package dev.cueopen.app.browser

import android.content.Context
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.WebApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Web API Hub: APIs nativas (assets/webapi/builtins.json) + as do usuário/IA (mesmo slug sobrepõe a nativa). */
object WebApiHub {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<WebApi>? = null

    fun builtins(ctx: Context): List<WebApi> = cache ?: runCatching {
        json.decodeFromString<List<WebApi>>(ctx.assets.open("webapi/builtins.json").bufferedReader().readText())
            .map { it.copy(id = "builtin-" + it.slug, builtin = true) }
    }.getOrDefault(emptyList()).also { cache = it }

    /** Hub zerado a pedido do usuário (uma vez): apaga também as APIs salvas no aparelho. */
    private fun resetOnce(ctx: Context) {
        val p = ctx.getSharedPreferences("webapi_hub", Context.MODE_PRIVATE)
        if (p.getBoolean("reset_v1", false)) return
        val store = CueApp.instance.store
        store.state.value.webApis.map { it.slug }.forEach(store::deleteWebApi)
        p.edit().putBoolean("reset_v1", true).apply()
    }

    fun all(ctx: Context): List<WebApi> {
        resetOnce(ctx)
        val mine = CueApp.instance.store.state.value.webApis
        val bySlug = mine.associateBy { it.slug }
        // Cópia salva de uma nativa guarda só ligado/estatísticas; o código vem sempre da versão do app. Versão editada (builtin=false) vale inteira.
        return builtins(ctx).map { b -> bySlug[b.slug]?.let { m -> if (m.builtin) b.copy(enabled = m.enabled, runs = m.runs, errors = m.errors, lastError = m.lastError, lastRunAt = m.lastRunAt) else m } ?: b } + mine.filter { m -> builtins(ctx).none { it.slug == m.slug } }
    }
    fun enabled(ctx: Context) = all(ctx).filter { it.enabled }
    fun find(ctx: Context, slug: String) = all(ctx).firstOrNull { it.slug == slug.trim() }

    fun validSlug(s: String) = Regex("^[a-z][a-z0-9_]{2,39}$").matches(s)

    /** Lista curta para a LLM: slug, descrição e parâmetros. */
    fun catalog(ctx: Context): String = enabled(ctx).joinToString("\n") { a ->
        "- ${a.slug}${if (a.sensitive) " [AÇÃO EXTERNA: só com pedido explícito do usuário, confirmed=true]" else ""}: ${a.description} · args: " + a.params.joinToString(", ") { p -> p.name + ":" + p.type + (if (p.required) "*" else "") + (if (p.default.isNotBlank()) "=" + p.default else "") }
    }.ifBlank { "(nenhuma API ativa no hub)" }

    /** Executa e registra estatísticas. */
    suspend fun call(ctx: Context, a: WebApi, args: JsonObject): String {
        val missing = a.params.filter { it.required && (args[it.name] as? JsonPrimitive)?.content.isNullOrBlank() }.map { it.name }
        if (missing.isNotEmpty()) return """{"api":"${a.slug}","ok":false,"error":"faltam argumentos: ${missing.joinToString()}"}"""
        val out = WebApiRunner.run(ctx, a, args)
        val ok = out.contains("\"ok\":true")
        val store = CueApp.instance.store
        val err = if (ok) "" else Regex("\"error\":\"([^\"]*)").find(out)?.groupValues?.get(1).orEmpty()
        val base = store.state.value.webApis.firstOrNull { it.slug == a.slug } ?: a
        store.upsertWebApi(base.copy(runs = base.runs + 1, errors = base.errors + if (ok) 0 else 1, lastError = err, lastRunAt = System.currentTimeMillis()))
        return out
    }
}
