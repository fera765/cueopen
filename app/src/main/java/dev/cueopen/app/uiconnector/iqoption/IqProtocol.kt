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
    /** kind: turbo | binary | forex. [instrumentId] é o id do instrumento Forex. */
    data class Asset(val id: Int, val ticker: String, val kind: String, val open: Boolean, val instrumentId: String = "")
    data class Quote(val activeId: Int, val price: Double)
    data class OptionOpened(val optionId: Long, val activeId: Int, val direction: String, val amount: Double, val expSec: Long, val openPrice: Double?)
    data class MarginPos(val id: Long, val instrument: String, val type: String, val side: String, val amount: Double, val leverage: Int,
                         val openPrice: Double?, val pnl: Double?, val status: String)
    data class Candle(val from: Long, val open: Double, val close: Double, val min: Double, val max: Double, val volume: Double)
    data class OptionAck(val optionId: Long?, val error: String?, val openPrice: Double? = null, val expSec: Long? = null)
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


    /** Instrumentos Forex; a biblioteca comunitária envia request_id vazio neste canal. */
    fun forexInstrumentsMessage(): String = sendMessage("", "get-instruments", "4.0", buildJsonObject { put("type", "forex") })

    /** Assina as cotações (velas de 1 s) de um ativo — usadas para o resultado AO VIVO das operações. */
    fun subscribeQuotesMessage(activeId: Int): String = buildJsonObject {
        put("name", "subscribeMessage")
        putJsonObject("msg") {
            put("name", "candle-generated")
            putJsonObject("params") { putJsonObject("routingFilters") { put("active_id", activeId); put("size", 1) } }
        }
    }.toString()

    fun unsubscribeQuotesMessage(activeId: Int): String = buildJsonObject {
        put("name", "unsubscribeMessage")
        putJsonObject("msg") {
            put("name", "candle-generated")
            putJsonObject("params") { putJsonObject("routingFilters") { put("active_id", activeId); put("size", 1) } }
        }
    }.toString()

    /** Ordem Forex a mercado com stop loss e take profit em % (obrigatórios). */
    fun placeForexOrderMessage(reqId: String, balanceId: Long, instrumentId: String, side: String, amount: Double,
                               leverage: Int, stopLossPct: Double, takeProfitPct: Double): String =
        sendMessage(reqId, "place-order-temp", "4.0", buildJsonObject {
            put("user_balance_id", balanceId)
            put("client_platform_id", 9)
            put("instrument_type", "forex")
            put("instrument_id", instrumentId)
            put("side", side.lowercase())
            put("type", "market")
            put("amount", amount)
            put("leverage", leverage)
            put("limit_price", JsonNull)
            put("stop_price", JsonNull)
            put("stop_lose_kind", "percent")
            put("stop_lose_value", stopLossPct)
            put("take_profit_kind", "percent")
            put("take_profit_value", takeProfitPct)
            put("use_trail_stop", false)
            put("auto_margin_call", false)
            put("use_token_for_commission", false)
        })

    fun closePositionMessage(reqId: String, positionId: Long): String = sendMessage(reqId, "close-position", "1.0", buildJsonObject { put("position_id", positionId) })

    /** Payload `change-tpsl` da biblioteca comunitária; a API não documenta esse comando oficialmente. */
    fun changeForexStopsMessage(positionId: Long, stopLossPct: Double, takeProfitPct: Double): String = buildJsonObject {
        put("name", "sendMessage")
        putJsonObject("msg") {
            put("name", "change-tpsl")
            put("version", "2.0")
            putJsonObject("body") {
                put("position_id", positionId)
                put("stop_lose_kind", "percent")
                put("stop_lose_value", stopLossPct)
                put("take_profit_kind", "percent")
                put("take_profit_value", takeProfitPct)
                put("use_trail_stop", false)
                putJsonObject("extra") {
                    put("stop_lose_kind", "percent")
                    put("take_profit_kind", "percent")
                }
            }
        }
    }.toString()

    fun getPositionsMessage(reqId: String, balanceId: Long): String = sendMessage(reqId, "get-positions", "3.0", buildJsonObject {
        put("instrument_types", JsonArray(listOf("forex").map { JsonPrimitive(it) }))
        put("user_balance_id", balanceId)
    })

    // ------------------------------------------------------------------ mensagens de entrada
    private fun openNow(a: JsonObject, nowSec: Long): Boolean {
        val enabled = (a["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val suspended = (a["is_suspended"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!enabled || suspended) return false
        val sched = a["schedule"].arr() ?: return true
        if (sched.isEmpty()) return true
        return sched.any { w -> val p = w.arr(); val st = p?.getOrNull(0).lng(); val en = p?.getOrNull(1).lng(); st != null && en != null && nowSec in st until en }
    }


    fun parseForexInstruments(msg: JsonObject, nowSec: Long): List<Asset> =
        msg["instruments"].arr().orEmpty().mapNotNull { e ->
            val a = e.obj() ?: return@mapNotNull null
            val instId = a["id"].str() ?: return@mapNotNull null
            val id = a["active_id"].lng()?.toInt() ?: 0
            val name = (a["ticker"].str() ?: a["name"].str() ?: instId).removePrefix("front.")
            Asset(id, name, "forex", openNow(a, nowSec), instId)
        }

    fun parseQuote(msg: JsonObject): Quote? {
        val id = msg["active_id"].lng()?.toInt() ?: return null
        val p = msg["close"].dbl() ?: msg["value"].dbl() ?: return null
        return Quote(id, p)
    }

    /** Operação aberta em qualquer lugar (app, web, celular): `socket-option-opened` / `option-opened`. */
    fun parseOptionOpened(root: JsonObject): OptionOpened? {
        val m = root["msg"].obj() ?: return null
        val id = m["option_id"].lng() ?: m["id"].lng() ?: return null
        val active = m["active_id"].lng()?.toInt() ?: return null
        val dir = (m["direction"].str() ?: m["dir"].str() ?: return null).lowercase()
        val amount = m["amount"].dbl() ?: m["price"].dbl() ?: m["sum"].dbl() ?: 0.0
        val exp = m["expiration_time"].lng() ?: m["expired"].lng() ?: m["exp"].lng() ?: return null
        val open = m["value"].dbl() ?: m["open_quote"].dbl()
        return OptionOpened(id, active, dir, amount, if (exp > 1e11) exp / 1000 else exp, open)
    }

    fun parseOrderAck(root: JsonObject): OptionAck {
        val status = root["status"].lng()
        val m = root["msg"].obj()
        val id = m?.get("id").lng()
        if (id != null && (status == null || status in 2000..2999)) return OptionAck(id, null)
        val text = m?.get("message").str() ?: root["msg"].str() ?: m?.get("error").str()
        return OptionAck(null, text ?: "A IQ Option recusou a ordem" + (status?.let { " (status $it)" } ?: "") + ".")
    }

    fun parsePositions(msg: JsonObject): List<MarginPos> =
        (msg["positions"].arr() ?: msg["items"].arr()).orEmpty().mapNotNull { e ->
            val p = e.obj() ?: return@mapNotNull null
            val id = p["id"].lng() ?: return@mapNotNull null
            val raw = p["raw_event"].obj()
            MarginPos(
                id = id,
                instrument = (p["instrument_id"].str() ?: p["instrument_name"].str() ?: raw?.get("instrument_id").str() ?: "?").removePrefix("front."),
                type = p["instrument_type"].str() ?: "?",
                side = (p["side"].str() ?: raw?.get("side").str() ?: p["direction"].str() ?: "?").lowercase(),
                amount = p["invest"].dbl() ?: p["margin"].dbl() ?: p["amount"].dbl() ?: 0.0,
                leverage = (p["leverage"].lng() ?: raw?.get("leverage").lng() ?: 1L).toInt(),
                openPrice = p["open_quote"].dbl() ?: p["open_price"].dbl() ?: raw?.get("open_quote").dbl(),
                pnl = p["pnl_net"].dbl() ?: p["pnl"].dbl() ?: p["close_profit"].dbl() ?: raw?.get("pnl").dbl(),
                status = p["status"].str() ?: "open",
            )
        }

    /** Resultado ao vivo de uma opção: call ganha se o preço atual > entrada; put se < entrada. */
    fun liveOutcome(direction: String, entry: Double?, price: Double?): String = when {
        entry == null || price == null -> "—"
        price == entry -> "EMPATE"
        (direction.lowercase() == "call") == (price > entry) -> "WIN"
        else -> "LOSS"
    }
    private fun parseBalance(o: JsonObject, fallbackCurrency: String): Balance? {
        val id = o["id"].lng() ?: return null
        val type = o["type"].lng()?.toInt() ?: return null
        return Balance(id, type, o["amount"].dbl() ?: 0.0, o["currency"].str() ?: fallbackCurrency)
    }

    /** `profile`: lista de contas + conta ativa. Algumas respostas de troca omitem a lista, mas ainda declaram balance_id. */
    fun parseProfile(msg: JsonObject): Profile? {
        val cur = msg["currency"].str() ?: ""
        val list = msg["balances"].arr()?.mapNotNull { (it as? JsonObject)?.let { b -> parseBalance(b, cur) } }.orEmpty()
        val active = msg["balance_id"].lng()
        if (list.isEmpty() && active == null) return null
        return Profile(list, active)
    }

    /** `balance-changed`: saldo atual de UMA conta (formato aninhado ou plano). */
    fun parseBalanceChanged(msg: JsonObject): Balance? {
        val o = msg["current_balance"].obj() ?: msg
        return parseBalance(o, msg["currency"].str() ?: "")
    }

    /** Retorna um ID de carteira somente quando a mensagem declara explicitamente qual está ativa. */
    fun confirmedActiveBalanceId(root: JsonObject): Long? {
        val msg = root["msg"].obj() ?: return null
        return when (root["name"].str()) {
            "profile" -> parseProfile(msg)?.activeBalanceId
            // `balance-changed` declara a carteira atual em formato aninhado ou plano;
            // o coordenador ainda compara o ID recebido com o alvo solicitado.
            "balance-changed" -> if (msg["current_balance"] == null || msg["current_balance"].obj() != null)
                parseBalanceChanged(msg)?.id else null
            else -> null
        }
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
        if (id != null && (status == null || status in 2000..2999)) return OptionAck(
            id, null, m?.get("value").dbl()?.takeIf { it > 0 },
            (m?.get("expiration_time").lng() ?: m?.get("expired").lng() ?: m?.get("exp").lng())?.let(::epochSeconds),
        )
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

    private fun epochSeconds(value: Long): Long = if (value > 100_000_000_000L) value / 1000 else value

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
        val q = normTicker(query)
        if (q.isEmpty()) return null
        val base = q.removeSuffix("-OTC").removeSuffix("OTC")
        return assets.firstOrNull { normTicker(it.ticker) == q }
            ?: assets.firstOrNull { normTicker(it.ticker).replace("-OTC", "") == q && it.open }
            // o modelo costuma inventar o sufixo: "BTCUSD-OTC" quando só existe "BTCUSD" (ou o contrário)
            ?: assets.firstOrNull { normTicker(it.ticker).replace("-OTC", "") == base && it.open }
            ?: assets.firstOrNull { normTicker(it.ticker).replace("-OTC", "") == base }
    }

    private fun normTicker(s: String) = s.uppercase().replace("/", "").replace(" ", "").replace("_", "").replace("OTC", "-OTC").replace("--OTC", "-OTC")

    /** Tickers parecidos com a busca (para o agente corrigir sem chamar open_assets de novo). */
    fun suggestAssets(assets: List<Asset>, query: String, n: Int = 6): List<String> {
        val base = normTicker(query).removeSuffix("-OTC")
        if (base.length < 2) return emptyList()
        val head = base.take(3)
        return assets.asSequence().filter { it.open }.map { it.ticker }.distinct()
            .sortedBy { t -> val tt = normTicker(t).removeSuffix("-OTC"); when { tt.startsWith(base) -> 0; tt.contains(base) -> 1; tt.startsWith(head) -> 2; tt.contains(head) -> 3; else -> 9 } }
            .filter { t -> val tt = normTicker(t); tt.contains(head) || base.contains(tt.removeSuffix("-OTC").take(3)) }
            .take(n).toList()
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
