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
        val blocked: List<String> = emptyList(),
        val allowOnly: List<String> = emptyList(),
        val noGroups: Boolean = false,
        val includeFromMe: Boolean = false,
    )

    private const val PREF = "wa_hub"
    /** Marca invisível que o atendente põe no fim das respostas: o webhook ignora essas mensagens (sem eco/loop). */
    const val BOT_MARK = "\u2063"
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
        return Config(p.getBoolean("webhook", false), token,
            list(p.getString("blocked", "")), list(p.getString("allow", "")), p.getBoolean("no_groups", false), p.getBoolean("from_me", false))
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("webhook", c.webhook).putString("token", c.token.ifBlank { newToken() })
            .putString("blocked", c.blocked.joinToString("\n")).putString("allow", c.allowOnly.joinToString("\n"))
            .putBoolean("no_groups", c.noGroups).putBoolean("from_me", c.includeFromMe).apply()
        if (c.webhook) start(ctx) else _status.value = "desligado"
    }

    private fun newToken() = "wa_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    /** Endereço local do webhook (só neste aparelho: 127.0.0.1). */
    fun webhookUrl(ctx: Context) = "http://127.0.0.1:${WaLocalServer.port}/whatsapp/" + config(ctx).token

    /** Esquema do evento que o webhook entrega (fluxos: {{trigger.payload.<campo>}}; servidor local: JSON). */
    val SCHEMA: List<Triple<String, String, String>> = listOf(
        Triple("event", "string", "sempre \"whatsapp.message\""),
        Triple("id", "string", "id da mensagem (use em reply_to, whatsapp_react, whatsapp_download_media)"),
        Triple("chat", "string", "id da conversa: …@c.us (pessoa), …@g.us (grupo) ou …@lid"),
        Triple("chat_name", "string", "nome da conversa ou do grupo"),
        Triple("is_group", "boolean", "true se veio de um grupo"),
        Triple("author", "string", "quem enviou (em grupos, o participante)"),
        Triple("sender_name", "string", "nome de quem enviou"),
        Triple("from_me", "boolean", "true se foi você que enviou"),
        Triple("type", "string", "chat, image, video, audio, ptt, document, sticker, location, vcard, poll_creation…"),
        Triple("text", "string", "texto (ou legenda; [tipo] para mídia sem legenda)"),
        Triple("has_media", "boolean", "true se tem mídia (baixe com whatsapp_download_media)"),
        Triple("lat", "number", "latitude (só type=location)"),
        Triple("lng", "number", "longitude (só type=location)"),
        Triple("time", "string", "data/hora da mensagem (ISO 8601)"),
        Triple("received_at", "string", "quando o app recebeu (ISO 8601)"),
        Triple("ack", "number", "status de entrega (−1 erro, 0 pendente, 1 enviado, 2 entregue, 3 lido)"),
    )

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
        WaLocalServer.start(ctx)
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
            else {
                // Leitura leve a cada 3 s (sem reenviar os helpers); instala o escutador só quando falta.
                val r = h.runJs("(function(){if(!(window.WPP&&WPP.isReady))return '__NOTREADY__';if(!window.__fluwyWaWatch)return '__INSTALL__';var q=window.__fluwyInbox||[];window.__fluwyInbox=[];return JSON.stringify(q)})()")
                if (r == "__INSTALL__") h.runJs("(function(){$helpers;api.waWatch();return '[]'})()") else r
            }
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

    private val NOISE = setOf("e2e_notification", "gp2", "notification_template", "notification", "ciphertext", "protocol", "revoked", "call_log", "broadcast_notification", "pinned_message", "debug")
    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** Processa uma mensagem recebida (também usado pelo teste do webhook na tela). */
    fun dispatch(ctx: Context, c: Config, m0: JsonObject) {
        val fromMe = s(m0, "from_me") == "true"
        if (fromMe && !c.includeFromMe) return
        val chat = s(m0, "chat"); val name = s(m0, "chat_name").ifBlank { s(m0, "sender_name") }
        // Ruído do protocolo: status (stories), avisos de criptografia/grupo e mensagens ainda não decifradas.
        if (chat == "status@broadcast" || chat.isBlank() || s(m0, "type") in NOISE) return
        if (s(m0, "text").endsWith(BOT_MARK)) return  // resposta do próprio atendente
        if (!allowed(c, chat, name)) return
        val m = JsonObject(m0 + mapOf("event" to JsonPrimitive("whatsapp.message"), "webhook" to JsonPrimitive("http://127.0.0.1:${WaLocalServer.port}/whatsapp/" + c.token),
            "received_at" to JsonPrimitive(java.time.Instant.now().toString())))
        _recent.value = (listOf(m) + _recent.value).take(30)
        WaLocalServer.publish(m)
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

    /** Deixa o WhatsApp Web carregado em segundo plano (as chamadas seguintes ficam quase instantâneas). */
    fun prewarm(ctx: Context) = scope.launch {
        val loaded = runCatching { WebApiRunner.withPage { h, _ -> h.currentUrl().contains("web.whatsapp.com") } }.getOrDefault(false)
        if (!loaded) WebApiHub.find(ctx, "whatsapp_status")?.let { WebApiHub.call(ctx, it, JsonObject(emptyMap())) }
    }

    /** Mensagem de exemplo para testar o webhook e os fluxos sem depender de alguém mandar mensagem. */
    fun sample(c: Config): JsonObject = buildJsonObject {
        put("id", "teste_" + System.currentTimeMillis()); put("from_me", false); put("chat", "5511999999999@c.us"); put("chat_name", "Contato de teste")
        put("sender_name", "Contato de teste"); put("type", "chat"); put("text", "Mensagem de teste do webhook"); put("is_group", false)
        put("time", java.time.Instant.now().toString()); put("has_media", false)
    }
}
