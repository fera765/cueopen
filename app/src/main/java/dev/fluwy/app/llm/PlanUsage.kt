package dev.fluwy.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Uso do plano do provedor, para acompanhar na tela de Provedores/Uso:
 * - OpenCode Go: GET https://opencode.ai/zen/go/v1/usage (o mesmo que o cliente oficial do OpenCode lê), com a chave e o
 *   workspace (org_…). Devolve % usada nas janelas de 5 h, semanal e mensal e quando cada uma reinicia.
 * - OpenRouter: GET https://openrouter.ai/api/v1/key (gasto, limite e pedidos gratuitos do dia).
 */
object PlanUsage {
    data class Meter(val label: String, val percent: Double, val limited: Boolean, val resetsAt: String)
    data class Report(val provider: String, val meters: List<Meter>, val lines: List<String>, val error: String? = null)

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }
    private fun JsonObject.num(k: String) = str(k)?.toDoubleOrNull()

    /** org_… que aparece nas mensagens de erro do OpenCode (link do console). */
    fun orgIdFrom(message: String?): String? = message?.let { Regex("opencode\\.ai/console/(org_[A-Za-z0-9]+)").find(it)?.groupValues?.get(1) }

    fun parseGo(body: String): List<Meter> {
        val u = json.parseToJsonElement(body).jsonObject.obj("usage") ?: return emptyList()
        return listOf("rolling" to "Janela de 5 horas", "weekly" to "Semana", "monthly" to "Mês").mapNotNull { (k, label) ->
            val m = u.obj(k) ?: return@mapNotNull null
            Meter(label, m.num("percent") ?: 0.0, m.str("status") == "rate-limited", m.str("resetsAt").orEmpty())
        }
    }

    fun parseOpenRouter(body: String): List<String> {
        val d = json.parseToJsonElement(body).jsonObject.obj("data") ?: return emptyList()
        fun usd(v: Double?) = v?.let { "US$ " + "%.2f".format(java.util.Locale.US, it) } ?: "—"
        return buildList {
            add("Gasto hoje: ${usd(d.num("usage_daily"))} · semana: ${usd(d.num("usage_weekly"))} · mês: ${usd(d.num("usage_monthly"))}")
            add(if (d.num("limit") == null) "Limite da chave: sem limite" else "Limite da chave: ${usd(d.num("limit"))} · restante: ${usd(d.num("limit_remaining"))}")
            d.obj("free_model_daily_requests")?.let { f -> add("Modelos gratuitos hoje: ${f.str("used") ?: "?"} de ${f.str("limit") ?: "?"} pedidos (restam ${f.str("remaining") ?: "?"})") }
            if (d.str("is_free_tier") == "true") add("Conta no plano gratuito (sem créditos comprados)")
        }
    }

    suspend fun fetch(providerId: String, apiKey: String, orgId: String): Report? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        when (providerId) {
            "opencode-go", "opencode-zen" -> runCatching {
                val req = Request.Builder().url("https://opencode.ai/zen/go/v1/usage").header("Authorization", "Bearer $apiKey").header("Accept", "application/json")
                    .apply { if (orgId.isNotBlank()) header("x-opencode-org-id", orgId) }.build()
                http.newCall(req).execute().use { r ->
                    val b = r.body?.string().orEmpty()
                    when {
                        r.code == 403 -> Report("OpenCode Go", emptyList(), emptyList(), "Este workspace não tem o plano Go (ou o ID do workspace está errado).")
                        !r.isSuccessful -> Report("OpenCode Go", emptyList(), emptyList(), "O OpenCode não devolveu o uso (HTTP ${r.code})" + if (orgId.isBlank()) ". Informe o ID do workspace (org_…)." else ".")
                        else -> Report("OpenCode Go", parseGo(b), emptyList())
                    }
                }
            }.getOrElse { Report("OpenCode Go", emptyList(), emptyList(), "Falha ao consultar o uso (${it.javaClass.simpleName}).") }
            "openrouter" -> runCatching {
                http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/key").header("Authorization", "Bearer $apiKey").build()).execute().use { r ->
                    if (!r.isSuccessful) Report("OpenRouter", emptyList(), emptyList(), "O OpenRouter não devolveu o uso (HTTP ${r.code}).")
                    else Report("OpenRouter", emptyList(), parseOpenRouter(r.body?.string().orEmpty()))
                }
            }.getOrElse { Report("OpenRouter", emptyList(), emptyList(), "Falha ao consultar o uso (${it.javaClass.simpleName}).") }
            else -> null
        }
    }
}
