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
        doc(a)
    }.ifBlank { "(nenhuma API ativa no hub)" }

    /** Documentação de uma API para o modelo: o que faz, quando é ação externa e cada argumento com tipo e descrição. */
    fun doc(a: WebApi): String = "- ${a.slug}${if (a.sensitive) " [AÇÃO EXTERNA: só com pedido explícito do usuário, confirmed=true]" else ""}: ${a.description}\n" +
        a.params.filter { it.name != "confirmed" }.joinToString("\n") { p -> "    · " + p.name + " (" + p.type + (if (p.required) ", obrigatório" else "") + (if (p.default.isNotBlank()) ", padrão " + p.default else "") + "): " + p.description }

    private val STOP = setOf("para", "com", "uma", "que", "por", "dos", "das", "meu", "minha", "isso", "esse", "essa", "como", "mais", "the", "and")
    /** APIs do hub relacionadas ao pedido (palavras do pedido × nome, slug e descrição; WhatsApp por sinônimos). */
    fun relevant(ctx: Context, text: String, max: Int = 8): List<WebApi> {
        val t = text.lowercase()
        val wa = Regex("whats|zap\\b|zapzap|wpp|\\bwa\\b|mensage|grupo|contato|conversa").containsMatchIn(t)
        val words = Regex("[\\p{L}\\d]{3,}").findAll(t).map { it.value }.filter { it !in STOP }.toSet()
        return enabled(ctx).map { a ->
            val hay = (a.slug.replace('_', ' ') + " " + a.name + " " + a.description).lowercase()
            var score = words.count { w -> hay.contains(w.take(5)) }
            if (wa && a.slug.startsWith("whatsapp_")) score += 3
            a to score
        }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** Teste seguro (__dry_run): a API validou tudo e parou antes de agir — vira sucesso simulado. */
    fun dryOk(slug: String, out: String): String {
        if (!out.contains("__DRYRUN_OK__")) return out
        val note = Regex("__DRYRUN_OK__([^\"]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        return buildJsonObjectString(slug, note)
    }
    private fun buildJsonObjectString(slug: String, note: String) = kotlinx.serialization.json.buildJsonObject {
        put("api", JsonPrimitive(slug)); put("ok", JsonPrimitive(true)); put("dry_run", JsonPrimitive(true))
        put("results", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
            put("ok", JsonPrimitive(true)); put("simulated", JsonPrimitive(true)); put("note", JsonPrimitive("teste seguro: " + note + " — nada foi enviado"))
        })))
    }.toString()

    /** Executa e registra estatísticas. */
    suspend fun call(ctx: Context, a: WebApi, args0: JsonObject, workspace: java.io.File? = null, onChunk: ((String) -> Unit)? = null): String {
        val missing = a.params.filter { it.required && (args0[it.name] as? JsonPrimitive)?.content.isNullOrBlank() }.map { it.name }
        if (missing.isNotEmpty()) return """{"api":"${a.slug}","ok":false,"error":"faltam argumentos: ${missing.joinToString()}"}"""
        // Regras do usuário para o WhatsApp (bloqueados / só permitidos / sem grupos): sempre do app, nunca do modelo.
        val args = if (a.slug.startsWith("whatsapp_")) JsonObject(args0 - "__wa_policy" + ("__wa_policy" to WhatsAppHub.policyJson(ctx))) else args0
        val out = dryOk(a.slug, WebApiRunner.run(ctx, a, args, workspace = workspace, onChunk = onChunk))
        val ok = out.contains("\"ok\":true")
        val store = CueApp.instance.store
        val err = if (ok) "" else Regex("\"error\":\"([^\"]*)").find(out)?.groupValues?.get(1).orEmpty()
        val base = store.state.value.webApis.firstOrNull { it.slug == a.slug } ?: a
        store.upsertWebApi(base.copy(runs = base.runs + 1, errors = base.errors + if (ok) 0 else 1, lastError = err, lastRunAt = System.currentTimeMillis()))
        return out
    }
}
