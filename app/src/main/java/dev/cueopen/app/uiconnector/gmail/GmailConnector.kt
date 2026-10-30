package dev.cueopen.app.uiconnector.gmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.FieldType
import dev.cueopen.app.uiconnector.LoginField
import dev.cueopen.app.uiconnector.ParamSpec
import dev.cueopen.app.uiconnector.Redactor
import dev.cueopen.app.uiconnector.SettingSpec
import dev.cueopen.app.uiconnector.SettingType
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.ToolSpec
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import dev.cueopen.app.uiconnector.UiConnectorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Conector Gmail (API oficial do Google) com login OAuth 2.0 + PKCE.
 *
 * O usuário cria um "ID do cliente OAuth" do tipo **App para computador (Desktop)** no Google Cloud do PRÓPRIO
 * projeto (com a Gmail API ativada) e cola o client_id e o client_secret. O login abre o navegador na tela
 * oficial de consentimento do Google; a resposta volta para um servidor local temporário em 127.0.0.1
 * (redirect loopback, o fluxo recomendado pelo Google para apps instalados). O refresh token fica só no
 * cofre cifrado do app; tokens, client secret e conteúdo de credenciais nunca chegam ao LLM.
 * Escopos: gmail.readonly (ler) + gmail.send (enviar — só se o usuário liberar nas configurações).
 */
class GmailConnector(private val ctx: Context) : UiConnector {

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val SCOPES = "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send"
        private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000
    }

    override val spec = UiConnectorSpec(
        id = "gmail",
        name = "Gmail",
        description = "Sua caixa do Gmail pela API oficial (OAuth): buscar, ler e — se você liberar — enviar e-mails.",
        version = "1.0.0",
        loginFields = listOf(
            LoginField("client_id", "Client ID (OAuth · App para computador)", FieldType.TEXT, hint = "xxxx.apps.googleusercontent.com"),
            LoginField("client_secret", "Client secret", FieldType.PASSWORD, hint = "Do mesmo ID do cliente OAuth"),
        ),
        settings = listOf(
            SettingSpec("allow_send", "Agente pode ENVIAR e-mails",
                "Desligado: o agente só lê. Ligado: ele pode enviar e-mails em seu nome (cada envio pede confirmação).",
                SettingType.SWITCH, "false", risky = true),
            SettingSpec("max_send_day", "Máx. de envios por dia (agente)", "Teto diário de e-mails enviados pelo agente.", SettingType.NUMBER, "5"),
            SettingSpec("inbox_count", "E-mails na tela", "Quantos e-mails recentes da caixa de entrada mostrar (1–25).", SettingType.NUMBER, "10"),
        ),
        tools = listOf(
            ToolSpec("unread", "Quantidade de e-mails não lidos na caixa de entrada e o endereço da conta."),
            ToolSpec("search", "Busca e-mails com a sintaxe do Gmail (ex.: \"from:banco is:unread newer_than:2d\"). Devolve id, remetente, assunto, data e trecho.", listOf(
                ParamSpec("query", "Consulta no formato da busca do Gmail. Padrão: in:inbox", required = false),
                ParamSpec("max", "Quantidade (1–25). Padrão 10.", "integer", required = false),
            )),
            ToolSpec("read", "Lê o conteúdo de um e-mail pelo id (obtido em search).", listOf(ParamSpec("id", "Id do e-mail"))),
            ToolSpec("send", "Envia um e-mail de texto em nome do usuário. Só funciona se o usuário liberou o envio nas configurações do conector.", listOf(
                ParamSpec("to", "Destinatário (e-mail)"),
                ParamSpec("subject", "Assunto"),
                ParamSpec("body", "Corpo em texto puro"),
            ), risky = true),
            ToolSpec("labels", "Lista os marcadores (labels) da conta."),
        ),
        accent = 0xFFEA4335,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val baseHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()
    private fun httpClient() = dev.cueopen.app.CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.AGENT_API, baseHttp)
    private fun debugLog() = runCatching { dev.cueopen.app.CueApp.instance.connectorDebugLog }.getOrNull()
    private val mutex = Mutex()

    @Volatile private var clientId: String? = null
    @Volatile private var clientSecret: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiry = 0L
    @Volatile private var email: String = ""
    private var sentDay = ""
    private var sentCount = 0

    override val isLoggedIn: Boolean get() = refreshToken != null

    override fun secrets(): List<String> = listOfNotNull(clientSecret, refreshToken, accessToken).filter { it.length >= 4 }

    /** O cofre guarda o refresh token (não a senha do Google, que o app nunca vê). */
    override fun persistedCredentials(): Map<String, String>? {
        val id = clientId ?: return null
        val sec = clientSecret ?: return null
        val rt = refreshToken ?: return null
        return mapOf("client_id" to id, "client_secret" to sec, "refresh_token" to rt)
    }

    // ------------------------------------------------------------------ OAuth
    override suspend fun login(credentials: Map<String, String>): Result<Unit> = mutex.withLock {
        try {
            debugLog()?.rememberSecrets("gmail", listOfNotNull(credentials["client_secret"], credentials["refresh_token"]))
            val id = credentials["client_id"]?.trim().orEmpty()
            val sec = credentials["client_secret"]?.trim().orEmpty()
            if (!id.endsWith(".apps.googleusercontent.com")) return@withLock Result.failure(IllegalArgumentException("Client ID inválido: deve terminar em .apps.googleusercontent.com (Google Cloud → APIs e serviços → Credenciais → ID do cliente OAuth do tipo App para computador)."))
            if (sec.isEmpty()) return@withLock Result.failure(IllegalArgumentException("Informe o client secret do mesmo ID do cliente OAuth."))
            val saved = credentials["refresh_token"]?.trim().orEmpty()
            val rt = if (saved.isNotEmpty()) saved else interactive(id, sec)
            debugLog()?.rememberSecrets("gmail", listOf(id, sec, rt))
            clientId = id; clientSecret = sec; refreshToken = rt
            accessToken = null; accessExpiry = 0
            refreshAccess()
            email = get("$API/profile")["emailAddress"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Result.success(Unit)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            refreshToken = null; accessToken = null
            debugLog()?.recordFailure("gmail", "oauth-login", t)
            Result.failure(IllegalStateException(Redactor.redact(t.message ?: t.javaClass.simpleName, listOfNotNull(credentials["client_secret"], credentials["refresh_token"]))))
        }
    }

    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Abre a tela oficial do Google e recebe o código no redirect loopback (127.0.0.1). Devolve o refresh token. */
    private suspend fun interactive(id: String, sec: String): String = withContext(Dispatchers.IO) {
        val rnd = SecureRandom()
        val verifier = b64url(ByteArray(48).also { rnd.nextBytes(it) })
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = b64url(ByteArray(24).also { rnd.nextBytes(it) })
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = LOGIN_TIMEOUT_MS
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = "$AUTH_URL?client_id=${enc(id)}&redirect_uri=${enc(redirect)}&response_type=code" +
                "&scope=${enc(SCOPES)}&code_challenge=$challenge&code_challenge_method=S256&state=$state" +
                "&access_type=offline&prompt=consent"
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
            var code: String? = null
            while (code == null) {
                if (System.currentTimeMillis() > deadline) error("Tempo esgotado esperando o login no Google (5 min).")
                val sock = try { server.accept() } catch (e: SocketTimeoutException) { error("Tempo esgotado esperando o login no Google (5 min).") }
                sock.use { s ->
                    s.soTimeout = 10_000
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty() // GET /?code=…&state=… HTTP/1.1
                    val target = line.split(' ').getOrNull(1).orEmpty()
                    val q = target.substringAfter('?', "").split('&').filter { '=' in it }
                        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    val (ok, msg) = when {
                        q["state"] != state && (q.containsKey("code") || q.containsKey("error")) -> false to "Resposta inválida (state)."
                        q["error"] != null -> false to "Login cancelado ou recusado: ${q["error"]}"
                        q["code"] != null -> true to "Login concluído. Pode voltar ao Fluwy Bot."
                        else -> null to ""
                    }
                    val html = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'></head>" +
                        "<body style='font-family:sans-serif;background:#111;color:#eee;padding:32px'><h2>Fluwy Bot · Gmail</h2><p>${msg.ifEmpty { "Aguardando…" }}</p></body></html>"
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                    when (ok) {
                        true -> code = q["code"]
                        false -> error(msg)
                        null -> {}
                    }
                }
            }
            // volta para o app
            runCatching {
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    withContext(Dispatchers.Main) { ctx.startActivity(it) }
                }
            }
            val body = FormBody.Builder()
                .add("code", code!!).add("client_id", id).add("client_secret", sec)
                .add("redirect_uri", redirect).add("grant_type", "authorization_code").add("code_verifier", verifier)
                .build()
            val res = tokenCall(body)
            res["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: error("O Google não devolveu refresh token. Remova o acesso do app em myaccount.google.com/permissions e entre de novo.")
        }
    }

    private fun tokenCall(body: FormBody): JsonObject {
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        val started = System.nanoTime()
        try {
            httpClient().newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                debugLog()?.recordHttp("gmail", req.method, req.url.toString(), req.headers.names(),
                    (System.nanoTime() - started) / 1_000_000L, r.code, "OAuth token response; body omitted")
                val o = runCatching { json.parseToJsonElement(txt).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                if (!r.isSuccessful) {
                    val err = o["error"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${r.code}"
                    val hint = when (err) {
                        "invalid_grant" -> " — autorização expirada ou revogada; faça login de novo (em projetos em modo Teste o Google expira a autorização em 7 dias)."
                        "invalid_client", "unauthorized_client" -> " — confira o client ID/secret (tipo App para computador)."
                        else -> ""
                    }
                    error("Google recusou o token: $err$hint")
                }
                return o
            }
        } catch (t: Throwable) {
            debugLog()?.recordFailure("gmail", "oauth-token", t, req.url.toString(), req.method, (System.nanoTime() - started) / 1_000_000L)
            throw t
        }
    }

    private suspend fun refreshAccess() = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId ?: error("sem login")).add("client_secret", clientSecret.orEmpty())
            .add("refresh_token", refreshToken ?: error("sem login")).add("grant_type", "refresh_token").build()
        val o = tokenCall(body)
        accessToken = o["access_token"]?.jsonPrimitive?.contentOrNull ?: error("resposta sem access_token")
        debugLog()?.rememberSecrets("gmail", listOfNotNull(clientId, clientSecret, refreshToken, accessToken))
        val exp = o["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600
        accessExpiry = System.currentTimeMillis() + (exp - 60) * 1000
    }

    private suspend fun token(): String {
        if (refreshToken == null) error("Gmail: faça login no conector (Conectores → UI Conectores → Gmail).")
        if (accessToken == null || System.currentTimeMillis() >= accessExpiry) refreshAccess()
        return accessToken!!
    }

    private suspend fun call(build: (Request.Builder) -> Request.Builder, retry: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        val req = build(Request.Builder()).header("Authorization", "Bearer ${token()}").build()
        val started = System.nanoTime()
        try {
            httpClient().newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                debugLog()?.recordHttp("gmail", req.method, req.url.toString(), req.headers.names(),
                    (System.nanoTime() - started) / 1_000_000L, r.code, "Gmail API response; body and message omitted")
                if (r.code == 401 && retry) { accessToken = null; return@withContext call(build, false) }
                if (!r.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(txt).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    error("Gmail API HTTP ${r.code}: ${msg ?: txt.take(200)}")
                }
                runCatching { json.parseToJsonElement(txt).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) debugLog()?.recordFailure("gmail", "api", t, req.url.toString(), req.method, (System.nanoTime() - started) / 1_000_000L)
            throw t
        }
    }

    private suspend fun get(url: String) = call({ it.url(url) })

    override suspend fun logout() {
        val rt = refreshToken
        refreshToken = null; accessToken = null; email = ""
        if (rt != null) runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("https://oauth2.googleapis.com/revoke").post(FormBody.Builder().add("token", rt).build()).build()
                val started = System.nanoTime()
                httpClient().newCall(req).execute().use { r ->
                    debugLog()?.recordHttp("gmail", req.method, req.url.toString(), req.headers.names(),
                        (System.nanoTime() - started) / 1_000_000L, r.code, "OAuth revoke; body omitted")
                }
            }
        }
    }

    // ------------------------------------------------------------------ dados
    data class MailRow(val id: String, val from: String, val subject: String, val date: String, val snippet: String, val unread: Boolean)

    private suspend fun list(query: String, max: Int): List<MailRow> {
        val ids = get("$API/messages?maxResults=${max.coerceIn(1, 25)}&q=${enc(query)}")["messages"]
            ?.let { it as? JsonArray }?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        return ids.map { id ->
            val m = get("$API/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
            val h = GmailMime.headers(m)
            MailRow(id, h["from"].orEmpty(), h["subject"].orEmpty().ifEmpty { "(sem assunto)" }, h["date"].orEmpty(),
                m["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                (m["labelIds"] as? JsonArray)?.any { it.jsonPrimitive.content == "UNREAD" } == true)
        }
    }

    private suspend fun unreadCount(): Int =
        get("$API/labels/INBOX")["messagesUnread"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> {
        if (!isLoggedIn) return listOf(UiBlock.Note("Entre com o Client ID e o secret do seu projeto Google (OAuth, App para computador, com a Gmail API ativada).", Tone.NEUTRAL))
        return try {
            val n = settings.num("inbox_count", 10.0).toInt().coerceIn(1, 25)
            val unread = unreadCount()
            val rows = list("in:inbox", n)
            listOf(
                UiBlock.Header(email.ifEmpty { "Gmail" }, "Caixa de entrada", "$unread não lidos", if (unread > 0) Tone.WARN else Tone.GOOD),
                UiBlock.Table("Recentes", listOf("De", "Assunto", "Data"),
                    rows.map { listOf((if (it.unread) "● " else "") + GmailMime.shortFrom(it.from), it.subject, GmailMime.shortDate(it.date)) }, "Caixa vazia."),
                UiBlock.Note(if (settings.bool("allow_send")) "Envio pelo agente: LIBERADO (com confirmação)." else "Envio pelo agente: bloqueado (só leitura).",
                    if (settings.bool("allow_send")) Tone.WARN else Tone.NEUTRAL),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            listOf(UiBlock.Note(Redactor.redact(t.message ?: "erro", secrets()), Tone.BAD))
        }
    }

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> =
        Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))

    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String = try {
        when (name) {
            "unread" -> "Conta ${email.ifEmpty { "?" }}: ${unreadCount()} não lido(s) na caixa de entrada."
            "search" -> {
                val rows = list(args["query"]?.ifBlank { null } ?: "in:inbox", args["max"]?.toIntOrNull() ?: 10)
                if (rows.isEmpty()) "Nenhum e-mail encontrado."
                else rows.mapIndexed { i, r -> "${i + 1}. [${r.id}]${if (r.unread) " (não lido)" else ""} ${r.date} — ${r.from} — ${r.subject}\n   ${r.snippet.take(200)}" }.joinToString("\n")
            }
            "read" -> {
                val id = args["id"]?.trim().orEmpty()
                if (!Regex("^[A-Za-z0-9]{6,64}$").matches(id)) "ERRO: id inválido (use o id devolvido por search)."
                else {
                    val m = get("$API/messages/$id?format=full")
                    val h = GmailMime.headers(m)
                    "De: ${h["from"].orEmpty()}\nPara: ${h["to"].orEmpty()}\nData: ${h["date"].orEmpty()}\nAssunto: ${h["subject"].orEmpty()}\n\n" +
                        GmailMime.bodyText(m["payload"]).ifBlank { m["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty() }.take(6000)
                }
            }
            "send" -> send(args, settings)
            "labels" -> (get("$API/labels")["labels"] as? JsonArray)?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }?.sorted()?.joinToString(", ")
                ?: "Nenhum marcador."
            else -> "Ferramenta desconhecida: $name"
        }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        "ERRO: " + Redactor.redact(t.message ?: t.javaClass.simpleName, secrets())
    }

    private suspend fun send(args: Map<String, String>, settings: ConnectorSettings): String {
        if (!settings.bool("allow_send")) return "RECUSADO: o usuário não liberou o envio de e-mails pelo agente (configurações do conector Gmail)."
        val to = args["to"]?.trim().orEmpty()
        if (!GmailMime.validEmail(to)) return "ERRO: destinatário inválido."
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        synchronized(this) { if (sentDay != today) { sentDay = today; sentCount = 0 } }
        val max = settings.num("max_send_day", 5.0).toInt()
        if (sentCount >= max) return "RECUSADO: limite diário de $max envio(s) atingido."
        val raw = GmailMime.build(email, to, args["subject"].orEmpty(), args["body"].orEmpty())
        val body = buildString { append("{\"raw\":"); append(JsonPrimitive(raw).toString()); append('}') }
        val r = call({ it.url("$API/messages/send").post(body.toRequestBody("application/json".toMediaType())) })
        synchronized(this) { sentCount++ }
        return "Enviado para $to (id ${r["id"]?.jsonPrimitive?.contentOrNull ?: "?"})."
    }
}

/** Funções puras de MIME/estrutura do Gmail (testáveis sem Android). */
object GmailMime {
    private val EMAIL = Regex("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+\\.[^\\s@<>,;\"]+$")
    fun validEmail(s: String) = EMAIL.matches(s)

    fun headers(m: JsonObject): Map<String, String> =
        ((m["payload"] as? JsonObject)?.get("headers") as? JsonArray)?.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            n.lowercase() to (o["value"]?.jsonPrimitive?.contentOrNull ?: "")
        }?.toMap().orEmpty()

    private fun decode(data: String): String = runCatching {
        String(Base64.getUrlDecoder().decode(data.replace('+', '-').replace('/', '_').trimEnd('=')), Charsets.UTF_8)
    }.getOrDefault("")

    /** Texto do corpo: prefere text/plain; senão text/html sem tags. */
    fun bodyText(payload: JsonElement?): String {
        val plain = ArrayList<String>(); val html = ArrayList<String>()
        fun walk(p: JsonElement?) {
            val o = p as? JsonObject ?: return
            val mime = o["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val data = ((o["body"] as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull
            if (data != null) when {
                mime.startsWith("text/plain") -> plain.add(decode(data))
                mime.startsWith("text/html") -> html.add(decode(data))
            }
            (o["parts"] as? JsonArray)?.forEach { walk(it) }
        }
        walk(payload)
        if (plain.isNotEmpty()) return plain.joinToString("\n").trim()
        return html.joinToString("\n") { stripHtml(it) }.trim()
    }

    fun stripHtml(h: String): String = h
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    private fun encWord(s: String) = if (s.all { it.code < 128 }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    /** Mensagem RFC 2822 em base64url (formato do campo "raw" da Gmail API). Remove quebras de linha de cabeçalhos. */
    fun build(from: String, to: String, subject: String, body: String): String {
        fun clean(v: String) = v.replace("\r", " ").replace("\n", " ").trim()
        val msg = buildString {
            if (from.isNotBlank()) append("From: ").append(clean(from)).append("\r\n")
            append("To: ").append(clean(to)).append("\r\n")
            append("Subject: ").append(encWord(clean(subject))).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(body.toByteArray(Charsets.UTF_8)))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(msg.toByteArray(Charsets.UTF_8))
    }

    fun shortFrom(f: String): String = f.substringBefore('<').trim().trim('"').ifEmpty { f.trim('<', '>') }.take(30)
    fun shortDate(d: String): String = d.replace(Regex("\\s*[+-]\\d{4}.*$"), "").replace(Regex("^\\w{3},\\s*"), "").take(17)
}
