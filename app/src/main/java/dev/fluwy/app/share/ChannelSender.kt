package dev.fluwy.app.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Envio de arquivos e imagens pelos agentes.
 * - Por conta própria (sem tocar no celular): Telegram (bot), Discord (webhook) e Slack (app/bot), com as credenciais
 *   configuradas em Configurações › Canais de envio.
 * - Pelo app instalado (WhatsApp, Telegram, Slack, Discord): abre o compartilhamento do app com o arquivo pronto;
 *   o envio final é um toque em "Enviar" (que passa pela confirmação do agente).
 */
object ChannelSender {
    enum class Channel(val id: String, val label: String, val maxBytes: Long) {
        TELEGRAM("telegram", "Telegram", 50L * 1024 * 1024),
        DISCORD("discord", "Discord", 10L * 1024 * 1024),
        SLACK("slack", "Slack", 50L * 1024 * 1024),
    }

    data class Config(val telegramToken: String = "", val telegramChatId: String = "", val discordWebhook: String = "",
                      val slackToken: String = "", val slackChannel: String = "")

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val sent = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    const val MAX_PER_HOUR = 40

    fun channel(id: String): Channel? = Channel.entries.firstOrNull { it.id == id.trim().lowercase() }

    fun configured(c: Config): List<Channel> = buildList {
        if (c.telegramToken.isNotBlank() && c.telegramChatId.isNotBlank()) add(Channel.TELEGRAM)
        if (validDiscordWebhook(c.discordWebhook)) add(Channel.DISCORD)
        if (c.slackToken.isNotBlank() && c.slackChannel.isNotBlank()) add(Channel.SLACK)
    }

    fun validDiscordWebhook(url: String): Boolean =
        Regex("^https://(canary\\.|ptb\\.)?(discord\\.com|discordapp\\.com)/api/webhooks/\\d+/[A-Za-z0-9_-]+$").matches(url.trim())

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "pdf" -> "application/pdf"; "txt", "md", "csv", "log" -> "text/plain"; "html", "htm" -> "text/html"
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "zip" -> "application/zip"; "json" -> "application/json"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    /** Pacotes dos apps para o compartilhamento (o primeiro instalado é usado). */
    fun packagesFor(app: String): List<String> = when (app.trim().lowercase()) {
        "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
        "telegram" -> listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
        "slack" -> listOf("com.Slack")
        "discord" -> listOf("com.discord")
        else -> emptyList()
    }

    /** Número no formato do WhatsApp (só dígitos, com DDI) ou null se não parecer um número válido. */
    fun whatsappJid(phone: String): String? = phone.filter { it.isDigit() }.takeIf { it.length in 10..15 }?.let { "$it@s.whatsapp.net" }

    private fun checkFile(file: File, max: Long): String? = when {
        !file.isFile -> "o arquivo não existe"
        file.length() == 0L -> "o arquivo está vazio"
        file.length() > max -> "o arquivo tem ${file.length() / 1_048_576} MB e o limite deste canal é ${max / 1_048_576} MB"
        else -> null
    }

    private fun rateLimited(): Boolean {
        val now = System.currentTimeMillis()
        while (sent.peekFirst()?.let { it < now - 3_600_000L } == true) sent.pollFirst()
        return sent.size >= MAX_PER_HOUR
    }

    /** Envia [file] pelo canal configurado. Devolve o texto para o agente (nunca contém credenciais). */
    suspend fun send(channel: Channel, c: Config, file: File, caption: String): String = withContext(Dispatchers.IO) {
        checkFile(file, channel.maxBytes)?.let { return@withContext "ERRO: $it." }
        if (channel !in configured(c)) return@withContext "ERRO: o canal ${channel.label} não está configurado. Peça ao usuário para preencher em Configurações › Canais de envio."
        if (rateLimited()) return@withContext "RECUSADO: limite de $MAX_PER_HOUR envios por hora atingido."
        val cap = caption.trim().take(1_000)
        val result = runCatching {
            when (channel) {
                Channel.TELEGRAM -> telegram(c, file, cap)
                Channel.DISCORD -> discord(c, file, cap)
                Channel.SLACK -> slack(c, file, cap)
            }
        }.getOrElse { "ERRO: falha de rede ao enviar para ${channel.label} (${it.javaClass.simpleName})." }
        if (!result.startsWith("ERRO")) sent.addLast(System.currentTimeMillis())
        result
    }

    private fun telegram(c: Config, file: File, caption: String): String {
        val image = isImage(file.name) && !file.name.endsWith(".gif", true) && file.length() <= 10L * 1024 * 1024
        val method = if (image) "sendPhoto" else "sendDocument"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", c.telegramChatId.trim())
            .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption) }
            .addFormDataPart(if (image) "photo" else "document", file.name, file.asRequestBody(mime(file.name).toMediaType()))
            .build()
        http.newCall(Request.Builder().url("https://api.telegram.org/bot${c.telegramToken.trim()}/$method").post(body).build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val ok = runCatching { JSONObject(txt).optBoolean("ok") }.getOrDefault(false)
            return if (ok) "Enviado ao Telegram (${file.name})."
            else "ERRO: o Telegram recusou (HTTP ${r.code}: ${runCatching { JSONObject(txt).optString("description") }.getOrDefault("").take(160)}). Confira o token do bot e o chat_id, e se o bot foi adicionado ao chat."
        }
    }

    private fun discord(c: Config, file: File, caption: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { if (caption.isNotBlank()) addFormDataPart("content", caption.take(2_000)) }
            .addFormDataPart("files[0]", file.name, file.asRequestBody(mime(file.name).toMediaType()))
            .build()
        http.newCall(Request.Builder().url(c.discordWebhook.trim()).post(body).build()).execute().use { r ->
            return if (r.isSuccessful) "Enviado ao Discord (${file.name})."
            else "ERRO: o Discord recusou (HTTP ${r.code}). Confira o webhook${if (r.code == 413) " — arquivo grande demais para o servidor" else ""}."
        }
    }

    private fun slack(c: Config, file: File, caption: String): String {
        val auth = "Bearer ${c.slackToken.trim()}"
        // 1) pede a URL de upload
        val step1 = http.newCall(Request.Builder().url("https://slack.com/api/files.getUploadURLExternal")
            .header("Authorization", auth)
            .post(okhttp3.FormBody.Builder().add("filename", file.name).add("length", file.length().toString()).build()).build()).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
        if (!step1.optBoolean("ok")) return "ERRO: o Slack recusou (${step1.optString("error")}). O app precisa do escopo files:write e estar no canal."
        // 2) envia os bytes
        http.newCall(Request.Builder().url(step1.getString("upload_url")).post(file.asRequestBody(mime(file.name).toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) return "ERRO: o Slack recusou o upload (HTTP ${r.code})."
        }
        // 3) conclui e publica no canal
        val files = JSONArray().put(JSONObject().put("id", step1.getString("file_id")).put("title", file.name))
        val payload = JSONObject().put("files", files).put("channel_id", c.slackChannel.trim()).apply { if (caption.isNotBlank()) put("initial_comment", caption) }
        val step3 = http.newCall(Request.Builder().url("https://slack.com/api/files.completeUploadExternal").header("Authorization", auth)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
        return if (step3.optBoolean("ok")) "Enviado ao Slack (${file.name})." else "ERRO: o Slack não concluiu o envio (${step3.optString("error")})."
    }

    /**
     * Abre o compartilhamento do [app] com o arquivo pronto. No WhatsApp, com [phone], abre direto a conversa do número.
     * Não envia sozinho: falta o toque em "Enviar".
     */
    suspend fun shareToApp(context: Context, app: String, file: File, text: String, phone: String): String {
        if (!file.isFile) return "ERRO: o arquivo não existe."
        if (file.length() > 100L * 1024 * 1024) return "ERRO: arquivo acima de 100 MB."
        val pm = context.packageManager
        val pkgs = packagesFor(app)
        if (pkgs.isEmpty()) return "ERRO: app não suportado. Use whatsapp, telegram, slack ou discord."
        val pkg = pkgs.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false) }
            ?: return "ERRO: o app $app não está instalado neste aparelho."
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text.take(4_000))
            if (app.equals("whatsapp", true)) whatsappJid(phone)?.let { putExtra("jid", it) }
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            kotlinx.coroutines.delay(1_800)
            "Abri o ${app.replaceFirstChar { it.uppercase() }} com o arquivo ${file.name} pronto" +
                (if (app.equals("whatsapp", true) && whatsappJid(phone) != null) " na conversa do número informado" else "") +
                ". Para concluir, " + (if (app.equals("whatsapp", true) && whatsappJid(phone) != null) "toque em \"Enviar\" com device_accessibility_tap."
                    else "escolha o destino (device_accessibility_tap com o nome do contato/canal) e depois toque em \"Enviar\".")
        }.getOrElse { "ERRO: o app recusou o compartilhamento (${it.javaClass.simpleName})." }
    }
}
