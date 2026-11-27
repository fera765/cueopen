package dev.cueopen.app.browser

import android.content.Context
import dev.cueopen.app.CueApp
import dev.cueopen.app.workflow.WorkflowEngine
import dev.cueopen.app.workflow.WorkflowStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * WhatsApp do API Hub: configurações (contatos bloqueados / só permitidos / sem grupos) e o webhook de mensagens
 * recebidas em tempo real. O webhook escuta o WhatsApp Web (WA-JS, evento chat.new_message) na página das APIs,
 * lê a fila a cada ~3 s e, para cada mensagem: guarda nas recentes, dispara os fluxos com gatilho "Mensagem no
 * WhatsApp" que casam com o filtro e, se configurado, encaminha (POST JSON) para um endereço externo.
 */
object WhatsAppHub {
    data class Config(
        val webhook: Boolean = false,
        val token: String = "",
        val forwardUrl: String = "",
        val blocked: List<String> = emptyList(),
        val allowOnly: List<String> = emptyList(),
        val noGroups: Boolean = false,
        val includeFromMe: Boolean = false,
    )

    private const val PREF = "wa_hub"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _recent = MutableStateFlow<List<JsonObject>>(emptyList())
    val recent: StateFlow<List<JsonObject>> = _recent
    private val _status = MutableStateFlow("desligado")
    val status: StateFlow<String> = _status
    private val pending = ArrayDeque<Pair<String, Pair<String, String>>>() // (wfId, (nodeId, payload)) aguardando o fluxo terminar
    @Volatile private var started = false
    @Volatile private var lastLoad = 0L

    private fun list(s: String?) = s.orEmpty().split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun config(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var token = p.getString("token", "").orEmpty()
        if (token.isBlank()) { token = newToken(); p.edit().putString("token", token).apply() }
        return Config(p.getBoolean("webhook", false), token, p.getString("forward", "").orEmpty(),
            list(p.getString("blocked", "")), list(p.getString("allow", "")), p.getBoolean("no_groups", false), p.getBoolean("from_me", false))
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("webhook", c.webhook).putString("token", c.token.ifBlank { newToken() }).putString("forward", c.forwardUrl.trim())
            .putString("blocked", c.blocked.joinToString("\n")).putString("allow", c.allowOnly.joinToString("\n"))
            .putBoolean("no_groups", c.noGroups).putBoolean("from_me", c.includeFromMe).apply()
        if (c.webhook) start(ctx) else _status.value = "desligado"
    }

    private fun newToken() = "wa_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    /** Endereço do webhook (identificador copiável) usado no gatilho dos fluxos. */
    fun webhookId(ctx: Context) = "fluwy://whatsapp/" + config(ctx).token

    /** Regras que vão para o código das APIs (o modelo nunca as define). */
    fun policyJson(ctx: Context): JsonObject {
        val c = config(ctx)
        return buildJsonObject {
            putJsonArray("blocked") { c.blocked.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("allow_only") { c.allowOnly.forEach { add(JsonPrimitive(it)) } }
            put("no_groups", c.noGroups)
        }
    }

    private fun matches(id: String, list: List<String>) = list.any { x ->
        val d = x.filter { it.isDigit() }; val u = id.substringBefore('@').filter { it.isDigit() }
        if (d.isNotEmpty() && u.isNotEmpty()) u.endsWith(d) || d.endsWith(u) else id.contains(x, ignoreCase = true)
    }

    fun allowed(c: Config, chat: String, name: String): Boolean {
        if (matches(chat, c.blocked) || (name.isNotBlank() && c.blocked.any { name.contains(it, true) })) return false
        if (c.allowOnly.isNotEmpty() && !matches(chat, c.allowOnly) && c.allowOnly.none { name.isNotBlank() && name.contains(it, true) }) return false
        return true
    }

    /** Liga o escutador (idempotente). Roda enquanto o processo do app vive; o serviço em primeiro plano o mantém. */
    fun start(ctx: Context) {
        if (!config(ctx).webhook) return
        runCatching { dev.cueopen.app.sandbox.SandboxService.start(ctx, "Webhook do WhatsApp ativo") }
        if (started) return
        started = true
        scope.launch {
            while (true) {
                val c = config(ctx)
                if (!c.webhook) { _status.value = "desligado"; delay(5_000); continue }
                runCatching { tick(ctx, c) }.onFailure { _status.value = "erro: " + (it.message ?: it.toString()).take(120) }
                delay(3_000)
            }
        }
    }

    private suspend fun tick(ctx: Context, c: Config) {
        retryPending()
        val raw = WebApiRunner.withPage { h, helpers ->
            if (!h.currentUrl().contains("web.whatsapp.com")) "__NOPAGE__"
            else h.runJs("(function(){$helpers;if(!(window.WPP&&WPP.isReady))return '__NOTREADY__';api.waWatch();return api.waDrain()})()")
        }
        if (raw == "__NOPAGE__" || raw == "__NOTREADY__" || raw.startsWith("ERROR")) {
            _status.value = if (raw == "__NOTREADY__") "aguardando o WhatsApp Web ficar pronto" else "abrindo o WhatsApp Web…"
            // Carrega a página (e o WA-JS) pela API de status, no máximo 1 vez por minuto.
            if (System.currentTimeMillis() - lastLoad > 60_000) {
                lastLoad = System.currentTimeMillis()
                WebApiHub.find(ctx, "whatsapp_status")?.let { val r = WebApiHub.call(ctx, it, JsonObject(emptyMap()))
                    if (!r.contains("\"ok\":true")) _status.value = "erro: " + (Regex("\"error\":\"([^\"]*)").find(r)?.groupValues?.get(1) ?: "WhatsApp indisponível").take(160) }
            }
            return
        }
        _status.value = "ativo · escutando mensagens"
        val arr = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return
        for (el in arr) dispatch(ctx, c, el as? JsonObject ?: continue)
    }

    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** Processa uma mensagem recebida (também usado pelo teste do webhook na tela). */
    fun dispatch(ctx: Context, c: Config, m0: JsonObject) {
        val fromMe = s(m0, "from_me") == "true"
        if (fromMe && !c.includeFromMe) return
        val chat = s(m0, "chat"); val name = s(m0, "chat_name").ifBlank { s(m0, "sender_name") }
        if (!allowed(c, chat, name)) return
        val m = JsonObject(m0 + mapOf("event" to JsonPrimitive("whatsapp.message"), "webhook" to JsonPrimitive("fluwy://whatsapp/" + c.token),
            "received_at" to JsonPrimitive(java.time.Instant.now().toString())))
        _recent.value = (listOf(m) + _recent.value).take(30)
        dev.cueopen.app.diag.ActivityLog.webApi("whatsapp_webhook", true, 0, chat, "mensagem ${s(m, "type")} de ${name.ifBlank { chat }}")
        if (c.forwardUrl.startsWith("https://")) scope.launch { forward(c, m) }
        fireFlows(m)
    }

    /** Filtro do gatilho "Mensagem no WhatsApp": conversa, tipo (privada/grupo), palavra-chave. */
    fun nodeMatches(cfg: Map<String, String>, m: JsonObject): Boolean {
        val chat = s(m, "chat"); val name = s(m, "chat_name"); val sender = s(m, "sender_name"); val text = s(m, "text")
        val isGroup = chat.endsWith("@g.us")
        when (cfg["scope"] ?: "all") { "private" -> if (isGroup) return false; "groups" -> if (!isGroup) return false }
        val f = cfg["chat"].orEmpty().trim()
        if (f.isNotEmpty()) {
            val d = f.filter { it.isDigit() }
            val hit = chat.equals(f, true) || name.contains(f, true) || sender.contains(f, true) ||
                (d.length >= 6 && chat.substringBefore('@').endsWith(d))
            if (!hit) return false
        }
        val kw = cfg["keyword"].orEmpty().trim()
        if (kw.isNotEmpty() && kw.split('|').map { it.trim() }.filter { it.isNotEmpty() }.none { text.contains(it, true) }) return false
        if (cfg["from_me"] != "true" && s(m, "from_me") == "true") return false
        return true
    }

    private fun fireFlows(m: JsonObject) {
        val payload = m.toString()
        for (w in WorkflowStore.flows.value) {
            if (!w.enabled) continue
            for (n in w.nodes) {
                if (n.type != "trigger_whatsapp" || !nodeMatches(n.config, m)) continue
                WorkflowEngine.start(w.id, trigger = "whatsapp", triggerNodeId = n.id, payload = payload)
                    .onFailure { if (it.message?.contains("já está em execução") == true) synchronized(pending) { if (pending.size < 50) pending.addLast(w.id to (n.id to payload)) } }
            }
        }
    }

    /** Mensagens que chegaram com o fluxo ainda rodando: disparam quando ele termina (em ordem). */
    private fun retryPending() {
        val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
        val busy = mutableSetOf<String>()
        for ((wf, np) in batch) {
            if (wf in busy) { synchronized(pending) { pending.addLast(wf to np) }; continue }
            WorkflowEngine.start(wf, trigger = "whatsapp", triggerNodeId = np.first, payload = np.second)
                .onFailure { if (it.message?.contains("já está em execução") == true) { busy += wf; synchronized(pending) { pending.addLast(wf to np) } } }
        }
    }

    private fun forward(c: Config, m: JsonObject) = runCatching {
        val con = java.net.URL(c.forwardUrl).openConnection() as java.net.HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 10_000; con.readTimeout = 15_000
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        con.setRequestProperty("X-Fluwy-Token", c.token)
        con.outputStream.use { it.write(m.toString().toByteArray()) }
        val code = con.responseCode; con.disconnect()
        if (code !in 200..299) dev.cueopen.app.diag.ErrorLog.log("webapi", "webhook WhatsApp: encaminhamento HTTP $code", null, "whatsapp_webhook")
    }.onFailure { runCatching { dev.cueopen.app.diag.ErrorLog.log("webapi", "webhook WhatsApp: encaminhamento falhou: ${it.message}", null, "whatsapp_webhook") } }

    /** Mensagem de exemplo para testar o webhook e os fluxos sem depender de alguém mandar mensagem. */
    fun sample(c: Config): JsonObject = buildJsonObject {
        put("id", "teste_" + System.currentTimeMillis()); put("from_me", false); put("chat", "5511999999999@c.us"); put("chat_name", "Contato de teste")
        put("sender_name", "Contato de teste"); put("type", "chat"); put("text", "Mensagem de teste do webhook"); put("is_group", false)
        put("time", java.time.Instant.now().toString()); put("has_media", false)
    }
}
