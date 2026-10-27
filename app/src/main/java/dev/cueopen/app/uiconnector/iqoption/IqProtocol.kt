package dev.cueopen.app.uiconnector.iqoption

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Protocolo (não oficial) da plataforma IQ Option: login HTTP + WebSocket JSON.
 * Tudo aqui é PURO (sem Android/rede): monta e interpreta mensagens. A rede fica em
 * [IqOptionConnector]. A IQ Option não publica API oficial; o formato abaixo segue o que clientes
 * open source (ex.: iqoptionapi) documentam e pode mudar sem aviso — por isso o parsing é defensivo
 * e qualquer mensagem inesperada é registrada no log de erros.
 */
object IqProtocol {
    const val LOGIN_URL = "https://auth.iqoption.com/api/v2/login"
    const val WS_URL = "wss://iqoption.com/echo/websocket"
    const val ORIGIN = "https://iqoption.com"

    /** Tipos de conta (balance.type): 1 = REAL, 4 = TREINAMENTO (demo). */
    const val TYPE_REAL = 1
    const val TYPE_PRACTICE = 4

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Balance(val id: Long, val type: Int, val amount: Double, val currency: String) {
        val isReal get() = type == TYPE_REAL
        val isPractice get() = type == TYPE_PRACTICE
        val label get() = if (isReal) "REAL" else if (isPractice) "DEMO" else "tipo $type"
    }

    data class Profile(val balances: List<Balance>, val activeBalanceId: Long?)
    data class Asset(val id: Int, val ticker: String, val kind: String, val open: Boolean)
    data class Candle(val from: Long, val open: Double, val close: Double, val min: Double, val max: Double, val volume: Double)
    data class OptionAck(val optionId: Long?, val error: String?)
    data class OptionClosed(val optionId: Long, val result: String, val profit: Double?)

    sealed interface LoginOutcome {
        data class Success(val ssid: String) : LoginOutcome
        data class Failure(val message: String) : LoginOutcome
        data class TwoFactor(val message: String) : LoginOutcome
    }

    // ------------------------------------------------------------------ parse helpers
    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement?.lng(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    // ------------------------------------------------------------------ login
    fun loginBody(email: String, password: String): String = buildJsonObject {
        put("identifier", email)
        put("password", password)
    }.toString()

    /**
     * Interpreta a resposta do login. O ssid pode vir no corpo (`ssid`) ou no cookie `ssid`.
     * Nunca inclui a senha na mensagem de erro.
     */
    fun parseLogin(httpCode: Int, body: String, setCookies: List<String>): LoginOutcome {
        val o = parseObject(body)
        val code = o?.get("code").str()
        val ssid = o?.get("ssid").str()
            ?: o?.get("data").obj()?.get("ssid").str()
            ?: setCookies.firstNotNullOfOrNull { c ->
                Regex("(?:^|;\\s*)ssid=([^;]+)").find(c)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "deleted" }
            }
        if (!ssid.isNullOrBlank() && (code == null || code == "success" || httpCode in 200..299)) return LoginOutcome.Success(ssid)
        if (code == "verify" || o?.get("method").str() != null && code != "success")
            return LoginOutcome.TwoFactor("A IQ Option pediu verificação em duas etapas (2FA). Esse fluxo ainda não é suportado pelo conector: desative o 2FA da conta ou use a plataforma oficial.")
        val msg = when {
            code == "invalid_credentials" || httpCode == 401 || httpCode == 403 && code == null -> "E-mail ou senha incorretos."
            code == "too_many_requests" || httpCode == 429 -> "Muitas tentativas de login. Aguarde alguns minutos e tente novamente."
            code != null -> "Login recusado pela IQ Option (código: $code)."
            httpCode in 500..599 -> "A IQ Option está indisponível agora (HTTP $httpCode)."
            else -> "Login falhou (HTTP $httpCode) e a resposta não trouxe sessão."
        }
        return LoginOutcome.Failure(msg)
    }

    // ------------------------------------------------------------------ mensagens de saída
    fun ssidMessage(ssid: String): String = buildJsonObject { put("name", "ssid"); put("msg", ssid) }.toString()

    fun changeBalanceMessage(balanceId: Long): String = buildJsonObject {
        put("name", "api_profile_changebalance")
        putJsonObject("msg") { put("balance_id", balanceId) }
    }.toString()

    private fun sendMessage(reqId: String, name: String, version: String, body: JsonObject): String = buildJsonObject {
        put("name", "sendMessage")
        put("request_id", reqId)
        putJsonObject("msg") {
            put("name", name)
            put("version", version)
            put("body", body)
        }
    }.toString()

    fun initializationDataMessage(reqId: String): String = sendMessage(reqId, "get-initialization-data", "3.0", buildJsonObject { })

    fun getCandlesMessage(reqId: String, activeId: Int, sizeSeconds: Int, count: Int, toSec: Long): String =
        sendMessage(reqId, "get-candles", "2.0", buildJsonObject {
            put("active_id", activeId)
            put("split_normalization", true)
            put("size", sizeSeconds)
            put("to", toSec)
            put("count", count)
        })

    /** direction: "call" (sobe) ou "put" (desce). Expiração em epoch segundos. */
    fun openOptionMessage(reqId: String, balanceId: Long, activeId: Int, direction: String, expiredSec: Long, price: Double, turbo: Boolean): String =
        sendMessage(reqId, "binary-options.open-option", "1.0", buildJsonObject {
            put("user_balance_id", balanceId)
            put("active_id", activeId)
            put("option_type_id", if (turbo) 3 else 1)
            put("direction", direction.lowercase())
            put("expired", expiredSec)
            put("price", price)
            put("refund_value", 0)
            put("value", 0)
        })

    // ------------------------------------------------------------------ mensagens de entrada
    private fun parseBalance(o: JsonObject, fallbackCurrency: String): Balance? {
        val id = o["id"].lng() ?: return null
        val type = o["type"].lng()?.toInt() ?: return null
        return Balance(id, type, o["amount"].dbl() ?: 0.0, o["currency"].str() ?: fallbackCurrency)
    }

    /** `profile`: lista de contas + conta ativa. */
    fun parseProfile(msg: JsonObject): Profile? {
        val cur = msg["currency"].str() ?: ""
        val list = msg["balances"].arr()?.mapNotNull { (it as? JsonObject)?.let { b -> parseBalance(b, cur) } }.orEmpty()
        if (list.isEmpty()) return null
        val active = msg["balance_id"].lng()
        return Profile(list, active)
    }

    /** `balance-changed`: saldo atual de UMA conta (formato aninhado ou plano). */
    fun parseBalanceChanged(msg: JsonObject): Balance? {
        val o = msg["current_balance"].obj() ?: msg
        return parseBalance(o, msg["currency"].str() ?: "")
    }

    /** `initialization-data`: ativos de opções turbo/binárias com flag de aberto. */
    fun parseAssets(msg: JsonObject): List<Asset> {
        val out = linkedMapOf<Int, Asset>()
        for (kind in listOf("turbo", "binary")) {
            val actives = msg[kind].obj()?.get("actives").obj() ?: continue
            for ((key, v) in actives) {
                val a = v.obj() ?: continue
                val id = a["id"].lng()?.toInt() ?: key.toIntOrNull() ?: continue
                val raw = a["ticker"].str() ?: a["name"].str() ?: continue
                val ticker = raw.removePrefix("front.")
                val enabled = (a["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                val suspended = (a["is_suspended"] as? JsonPrimitive)?.booleanOrNull ?: false
                val open = enabled && !suspended
                val prev = out[id]
                // se existe em turbo e binary, mantém turbo (expirações curtas) e aberto se qualquer um estiver aberto
                out[id] = if (prev == null) Asset(id, ticker, kind, open) else prev.copy(open = prev.open || open)
            }
        }
        return out.values.toList()
    }

    fun parseCandles(msg: JsonObject): List<Candle> =
        msg["candles"].arr()?.mapNotNull { e ->
            val c = e.obj() ?: return@mapNotNull null
            val from = c["from"].lng() ?: return@mapNotNull null
            val close = c["close"].dbl() ?: return@mapNotNull null
            Candle(from, c["open"].dbl() ?: close, close, c["min"].dbl() ?: close, c["max"].dbl() ?: close, c["volume"].dbl() ?: 0.0)
        }.orEmpty()

    /** Resposta ao open-option (mensagem `option`): sucesso traz `msg.id`; falha traz uma mensagem. */
    fun parseOptionAck(root: JsonObject): OptionAck {
        val status = root["status"].lng()
        val msg = root["msg"]
        val m = msg.obj()
        val id = m?.get("id").lng()
        if (id != null && (status == null || status in 2000..2999)) return OptionAck(id, null)
        val text = m?.get("message").str() ?: msg.str() ?: m?.get("error").str()
        return OptionAck(null, text ?: "A IQ Option recusou a ordem" + (status?.let { " (status $it)" } ?: "") + ".")
    }

    /** `option-closed` / `socket-option-closed`: resultado final de uma operação. */
    fun parseOptionClosed(root: JsonObject): OptionClosed? {
        val m = root["msg"].obj() ?: return null
        val id = m["option_id"].lng() ?: m["id"].lng() ?: return null
        val result = m["win"].str() ?: m["result"].str() ?: return null
        val profit = m["profit_amount"].dbl() ?: m["win_amount"].dbl()
        return OptionClosed(id, result, profit)
    }

    // ------------------------------------------------------------------ regras de negócio puras
    /**
     * Expiração (epoch s) para uma opção de [minutes] minutos. A plataforma só aceita expirar no
     * fechamento de um minuto, e exige > 30 s de antecedência: se faltarem ≤ 30 s para o próximo
     * minuto, usa o seguinte.
     */
    fun expirationFor(serverNowSec: Long, minutes: Int): Long {
        val m = minutes.coerceIn(1, 60)
        var base = (serverNowSec / 60) * 60 + 60
        if (base - serverNowSec <= 30) base += 60
        return base + (m - 1) * 60L
    }

    /** Opções ≤ 5 min são "turbo" (tipo 3); acima disso, "binary" (tipo 1). */
    fun isTurbo(minutes: Int): Boolean = minutes <= 5

    /** Resolve "EURUSD", "eur/usd", "EURUSD-OTC" no ticker do ativo. */
    fun resolveAsset(assets: List<Asset>, query: String): Asset? {
        fun norm(s: String) = s.uppercase().replace("/", "").replace(" ", "").replace("_", "")
        val q = norm(query)
        if (q.isEmpty()) return null
        return assets.firstOrNull { norm(it.ticker) == q }
            ?: assets.firstOrNull { norm(it.ticker).replace("-OTC", "") == q && it.open }
    }

    /** Aceita "60", "60s", "1m", "5m", "1h" → segundos. Só tamanhos suportados pela plataforma. */
    fun timeframeSeconds(raw: String): Int? {
        val t = raw.trim().lowercase()
        val sec = when {
            t.endsWith("s") -> t.dropLast(1).toIntOrNull()
            t.endsWith("m") -> t.dropLast(1).toIntOrNull()?.times(60)
            t.endsWith("h") -> t.dropLast(1).toIntOrNull()?.times(3600)
            else -> t.toIntOrNull()
        } ?: return null
        return sec.takeIf { it in setOf(1, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 14400, 86400) }
    }
}
