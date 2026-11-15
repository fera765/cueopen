package dev.cueopen.app.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * "Entrar com OpenRouter" (OAuth PKCE): abre o login no navegador e recebe o retorno num endereço localhost do próprio
 * aparelho (o OpenRouter aceita callbacks localhost em qualquer porta). O código é trocado por uma chave de API da conta
 * do usuário — sem digitar chave e sem servidor intermediário.
 */
object OpenRouterOAuth {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    fun newVerifier(): String = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authUrl(callback: String, challenge: String): String =
        "https://openrouter.ai/auth?callback_url=" + Uri.encode(callback) + "&code_challenge=" + challenge + "&code_challenge_method=S256"

    /** Extrai o `code` da primeira linha da requisição HTTP ("GET /callback?code=abc HTTP/1.1"). */
    fun codeFromRequestLine(line: String): String? {
        val target = line.split(' ').getOrNull(1) ?: return null
        if (!target.startsWith("/callback")) return null
        return target.substringAfter('?', "").split('&').firstOrNull { it.startsWith("code=") }
            ?.removePrefix("code=")?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
    }

    /** Faz o login completo e devolve a chave da API. Lança erro com mensagem legível se algo falhar ou passar de 5 min. */
    suspend fun login(context: Context): String = withContext(Dispatchers.IO) {
        val verifier = newVerifier()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5 * 60_000
            val callback = "http://localhost:${server.localPort}/callback"
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl(callback, challenge(verifier)))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            var code: String? = null
            // O navegador pode pedir /favicon.ico antes; aceita algumas conexões até chegar o /callback.
            repeat(4) {
                if (code != null) return@repeat
                val socket = runCatching { server.accept() }.getOrElse { error("O login não voltou em 5 minutos. Tente de novo.") }
                socket.use { s ->
                    val first = s.getInputStream().bufferedReader().readLine().orEmpty()
                    code = codeFromRequestLine(first)
                    val page = if (code != null) "<h2>Pronto! Pode voltar ao Fluwy Bot.</h2>" else "<h2>Aguardando o login…</h2>"
                    val body = "<html><head><meta charset=utf-8><meta name=viewport content='width=device-width'></head><body style='font-family:sans-serif;padding:24px'>$page</body></html>"
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                }
            }
            val authCode = code ?: error("O OpenRouter não devolveu o código de autorização.")
            val payload = JSONObject().put("code", authCode).put("code_verifier", verifier).put("code_challenge_method", "S256")
            http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/auth/keys")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("O OpenRouter recusou a troca do código (HTTP ${r.code}).")
                JSONObject(txt).optString("key").takeIf { it.isNotBlank() } ?: error("Resposta do OpenRouter sem chave.")
            }
        }
    }
}
