package dev.cueopen.app.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Login por código (OAuth 2.0 Device Authorization Grant, RFC 8628): o app pede um código, o usuário confirma no site
 * do provedor e o app recebe o token — sem senha no app e sem servidor intermediário. Usa um app OAuth público
 * (só Client ID, sem secret) registrado pelo próprio usuário; nunca o Client ID de outro produto.
 */
object DeviceFlow {
    data class Start(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSec: Int, val expiresInSec: Int)
    data class Tokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content.orEmpty()
    private fun JsonObject.num(k: String, def: Long): Long = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: def

    fun parse(txt: String): JsonObject = runCatching { Json.parseToJsonElement(txt).jsonObject }.getOrElse {
        buildJsonObject { put("error", "resposta_invalida"); put("error_description", txt.take(160)) }
    }

    private fun post(url: String, form: Map<String, String>): Pair<Int, JsonObject> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> if (v.isNotBlank()) add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").post(body).build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            return r.code to parse(txt)
        }
    }

    fun parseStart(o: JsonObject): Start? {
        val device = o.str("device_code"); val user = o.str("user_code")
        val uri = o.str("verification_uri_complete").ifBlank { o.str("verification_uri") }
        if (device.isBlank() || user.isBlank() || uri.isBlank()) return null
        return Start(device, user, uri, o.num("interval", 5).toInt().coerceIn(1, 30), o.num("expires_in", 900).toInt())
    }

    /** Resultado de uma consulta: tokens, "continuar esperando" (null + slowDown) ou erro definitivo (exceção). */
    fun parsePoll(o: JsonObject, now: Long): Pair<Tokens?, Boolean> {
        val access = o.str("access_token")
        if (access.isNotBlank()) {
            val exp = o.num("expires_in", 0L).let { if (it > 0) now + it * 1000 else 0L }
            return Tokens(access, o.str("refresh_token"), exp) to false
        }
        return when (o.str("error")) {
            "authorization_pending" -> null to false
            "slow_down" -> null to true
            "access_denied" -> error("Login recusado no site do provedor.")
            "expired_token" -> error("O código expirou. Tente de novo.")
            else -> error("O provedor recusou o login (${o.str("error")}: ${o.str("error_description").take(140)}).")
        }
    }

    suspend fun start(a: ProviderCatalog.DeviceAuth, clientId: String): Start = withContext(Dispatchers.IO) {
        val (code, o) = post(a.deviceUrl, mapOf("client_id" to clientId.trim(), "scope" to a.scope))
        parseStart(o) ?: error("O provedor não iniciou o login (HTTP $code: ${o.str("error_description").ifBlank { o.str("error") }.take(140)}). Confira o Client ID.")
    }

    /** Consulta até o usuário confirmar (respeitando interval/slow_down) ou o código expirar. */
    suspend fun poll(a: ProviderCatalog.DeviceAuth, clientId: String, s: Start): Tokens = withContext(Dispatchers.IO) {
        var interval = s.intervalSec
        val deadline = System.currentTimeMillis() + s.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val (_, o) = post(a.tokenUrl, mapOf("client_id" to clientId.trim(), "device_code" to s.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            val (tokens, slow) = parsePoll(o, System.currentTimeMillis())
            if (tokens != null) return@withContext tokens
            if (slow) interval += 5
        }
        error("O código expirou antes da confirmação. Tente de novo.")
    }

    suspend fun refresh(a: ProviderCatalog.DeviceAuth, clientId: String, refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val (_, o) = post(a.tokenUrl, mapOf("client_id" to clientId.trim(), "grant_type" to "refresh_token", "refresh_token" to refreshToken))
        parsePoll(o, System.currentTimeMillis()).first ?: error("Não foi possível renovar o login.")
    }
}
