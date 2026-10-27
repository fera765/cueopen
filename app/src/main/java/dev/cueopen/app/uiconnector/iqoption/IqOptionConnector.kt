package dev.cueopen.app.uiconnector.iqoption

import dev.cueopen.app.diag.ErrorLog
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.FieldType
import dev.cueopen.app.uiconnector.LoginField
import dev.cueopen.app.uiconnector.ParamSpec
import dev.cueopen.app.uiconnector.SettingSpec
import dev.cueopen.app.uiconnector.SettingType
import dev.cueopen.app.uiconnector.Stat
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.ToolSpec
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import dev.cueopen.app.uiconnector.UiConnectorSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Conector IQ Option (primeiro UI Connector do SDK).
 *
 * Login com e-mail/senha → ssid → WebSocket. Mostra saldo, alterna conta DEMO/REAL, lista ativos
 * abertos, busca velas e abre operações. Segurança:
 *  - a senha/ssid vivem só aqui (e no cofre cifrado do hub); nunca entram no contexto do LLM;
 *  - o agente só opera na conta REAL se o usuário liberar explicitamente nas configurações;
 *  - o agente respeita valor máximo por operação e máximo de operações abertas;
 *  - "operações em execução" mostra o que foi aberto por este app e é fechado por eventos reais do servidor.
 */
class IqOptionConnector : UiConnector {

    override val spec = UiConnectorSpec(
        id = "iqoption",
        name = "IQ Option",
        description = "Opções binárias/turbo: saldo, conta Demo/Real, ativos abertos, velas e operações.",
        version = "1.0.0",
        loginFields = listOf(
            LoginField("email", "E-mail", FieldType.EMAIL, hint = "seu@email.com"),
            LoginField("password", "Senha", FieldType.PASSWORD),
        ),
        settings = listOf(
            SettingSpec(
                "allow_real_agent", "Agente pode operar na conta REAL",
                "Desligado: o agente só opera na conta DEMO. Ligado: ele pode abrir operações com dinheiro real, sempre dentro dos limites abaixo.",
                SettingType.SWITCH, "false", risky = true,
            ),
            SettingSpec("max_amount", "Valor máximo por operação (agente)", "Teto do valor de cada operação aberta pelo agente, na moeda da conta.", SettingType.NUMBER, "1"),
            SettingSpec("max_open", "Máx. de operações abertas (agente)", "O agente não abre novas operações acima deste número.", SettingType.NUMBER, "3"),
        ),
        tools = listOf(
            ToolSpec("balance", "Saldo das contas DEMO e REAL e qual está ativa."),
            ToolSpec("set_account", "Troca a conta ativa (demo ou real). A conta real só é aceita se o usuário liberou o agente para ela.",
                listOf(ParamSpec("account", "demo ou real", allowed = listOf("demo", "real"))), risky = true),
            ToolSpec("open_assets", "Lista os ativos abertos para operar agora.",
                listOf(ParamSpec("filter", "Filtro opcional de texto (ex.: EUR)", required = false))),
            ToolSpec("get_candles", "Velas OHLC reais de um ativo (abertura, máxima, mínima, fechamento, volume).", listOf(
                ParamSpec("asset", "Ticker do ativo, ex.: EURUSD"),
                ParamSpec("timeframe", "Tamanho da vela em segundos (60=1min, 300=5min…) ou 1m/5m/1h. Padrão 60.", required = false),
                ParamSpec("count", "Quantidade de velas (1–200). Padrão 30.", "integer", required = false),
            )),
            ToolSpec("buy", "Abre uma operação (call = sobe, put = desce) na conta ativa, respeitando os limites do usuário.", listOf(
                ParamSpec("asset", "Ticker do ativo, ex.: EURUSD"),
                ParamSpec("direction", "call (sobe) ou put (desce)", allowed = listOf("call", "put")),
                ParamSpec("amount", "Valor da operação na moeda da conta", "number"),
                ParamSpec("minutes", "Expiração em minutos (1–60). Padrão 1.", "integer", required = false),
            ), risky = true),
            ToolSpec("positions", "Operações em execução e as últimas fechadas (abertas por este app)."),
        ),
    )

    // ------------------------------------------------------------------ estado
    private class Waiter(val reqId: String?, val names: Set<String>, val deferred: CompletableDeferred<JsonObject>)

    private data class Tracked(
        val id: Long, val asset: String, val direction: String, val amount: Double,
        val expSec: Long, val demo: Boolean, val currency: String,
        val result: String? = null, val profit: Double? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val counter = AtomicLong(1)
    private val generation = AtomicLong(0)
    private val waiters = CopyOnWriteArrayList<Waiter>()
    private val balances = ConcurrentHashMap<Long, IqProtocol.Balance>()
    private val tracked = ConcurrentHashMap<Long, Tracked>()
    private val seen = ArrayDeque<String>()

    @Volatile private var ssid: String? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var authenticated = false
    @Volatile private var creds: Map<String, String>? = null
    @Volatile private var activeBalanceId: Long? = null
    @Volatile private var serverOffsetMs = 0L
    @Volatile private var assets: List<IqProtocol.Asset> = emptyList()
    @Volatile private var assetsAt = 0L

    override val isLoggedIn: Boolean get() = creds != null

    override fun secrets(): List<String> = listOfNotNull(ssid, creds?.get("password"), creds?.get("email"))

    private fun serverNowSec(): Long = (System.currentTimeMillis() + serverOffsetMs) / 1000
    private fun reqId() = "cue-" + counter.getAndIncrement()
    private fun err(detail: String) = runCatching { ErrorLog.log("uiconnector", "iqoption: $detail", null, "iqoption") }

    private fun noteSeen(name: String) = synchronized(seen) {
        seen.addLast(name)
        while (seen.size > 40) seen.removeFirst()
    }

    private fun recentNames(): String = synchronized(seen) { seen.toList().distinct().joinToString(",") }

    private fun activeBalance(): IqProtocol.Balance? = activeBalanceId?.let { balances[it] }

    // ------------------------------------------------------------------ login / sessão
    override suspend fun login(credentials: Map<String, String>): Result<Unit> = try {
        val e = credentials["email"].orEmpty().trim()
        val p = credentials["password"].orEmpty()
        require(e.isNotEmpty() && p.isNotEmpty()) { "Informe e-mail e senha." }
        mutex.withLock { connectLocked(e, p) }
        creds = mapOf("email" to e, "password" to p)
        Result.success(Unit)
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        err("login falhou: ${t.message}")
        Result.failure(IllegalStateException(friendly(t)))
    }

    private fun friendly(t: Throwable): String = when (t) {
        is TimeoutCancellationException -> "A IQ Option não respondeu a tempo. Verifique a conexão e tente novamente."
        is IOException -> "Sem conexão com a IQ Option: ${t.message ?: t.javaClass.simpleName}"
        else -> t.message ?: t.javaClass.simpleName
    }

    private suspend fun connectLocked(email: String, password: String) {
        closeSocket()
        ssid = withContext(Dispatchers.IO) { httpLogin(email, password) }
        val profileWaiter = Waiter(null, setOf("profile"), CompletableDeferred())
        waiters += profileWaiter
        try {
            val sid = ssid ?: error("Sessão inválida.")
            val req = Request.Builder().url(IqProtocol.WS_URL)
                .header("Origin", IqProtocol.ORIGIN).header("User-Agent", UA).build()
            // Geração: callbacks de um socket antigo (já fechado) nunca podem derrubar a conexão nova.
            val myGen = generation.get()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { if (myGen == generation.get()) webSocket.send(IqProtocol.ssidMessage(sid)) }
                override fun onMessage(webSocket: WebSocket, text: String) { if (myGen == generation.get()) handle(text) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (myGen == generation.get()) dropConnection("fechado ($code)") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (myGen == generation.get()) dropConnection(t.message ?: t.javaClass.simpleName) }
            })
            withTimeout(20_000) { profileWaiter.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            err("sem 'profile' após autenticar; mensagens recentes: ${recentNames()}")
            closeSocket()
            throw IllegalStateException("A IQ Option não confirmou a sessão. O protocolo pode ter mudado ou o login foi bloqueado; baixe o log de erros em Configurações.")
        } finally {
            waiters.remove(profileWaiter)
        }
    }

    private fun httpLogin(email: String, password: String): String {
        val req = Request.Builder().url(IqProtocol.LOGIN_URL)
            .post(IqProtocol.loginBody(email, password).toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA).header("Origin", IqProtocol.ORIGIN).header("Accept", "application/json").build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            return when (val o = IqProtocol.parseLogin(r.code, body, r.headers("Set-Cookie"))) {
                is IqProtocol.LoginOutcome.Success -> o.ssid
                is IqProtocol.LoginOutcome.Failure -> throw IllegalStateException(o.message)
                is IqProtocol.LoginOutcome.TwoFactor -> throw IllegalStateException(o.message)
            }
        }
    }

    private fun dropConnection(reason: String) {
        authenticated = false
        ws = null
        val failure = IOException("Conexão com a IQ Option perdida: $reason")
        waiters.forEach { it.deferred.completeExceptionally(failure) }
        waiters.clear()
    }

    private fun closeSocket() {
        generation.incrementAndGet() // invalida os callbacks do socket que está sendo fechado
        runCatching { ws?.close(1000, null) }
        ws = null
        authenticated = false
    }

    override suspend fun logout() {
        closeSocket()
        ssid = null
        creds = null
        balances.clear()
        tracked.clear()
        assets = emptyList()
        activeBalanceId = null
    }

    private suspend fun ensureConnected() {
        if (authenticated && ws != null) return
        val c = creds ?: error("Sessão da IQ Option não iniciada. Faça login no conector.")
        mutex.withLock {
            if (!(authenticated && ws != null)) connectLocked(c["email"].orEmpty(), c["password"].orEmpty())
        }
    }

    // ------------------------------------------------------------------ mensagens
    private fun handle(text: String) {
        val root = IqProtocol.parseObject(text) ?: return
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull ?: return
        noteSeen(name)
        val msg = root["msg"] as? JsonObject
        when (name) {
            "timeSync" -> {
                val serverMs = (root["msg"] as? JsonPrimitive)?.longOrNull
                if (serverMs != null) serverOffsetMs = serverMs - System.currentTimeMillis()
            }
            "profile" -> {
                val p = if (msg != null) IqProtocol.parseProfile(msg) else null
                if (p != null) {
                    p.balances.forEach { balances[it.id] = it }
                    val wanted = p.activeBalanceId
                    if (wanted != null && balances.containsKey(wanted)) activeBalanceId = wanted
                    if (activeBalanceId == null) activeBalanceId = (p.balances.firstOrNull { it.isPractice } ?: p.balances.first()).id
                    authenticated = true
                }
            }
            "balance-changed" -> {
                val b = if (msg != null) IqProtocol.parseBalanceChanged(msg) else null
                if (b != null) {
                    balances[b.id] = b
                    if (msg?.containsKey("current_balance") == true) activeBalanceId = b.id
                }
            }
            "option-closed", "socket-option-closed" -> {
                val c = IqProtocol.parseOptionClosed(root)
                val t = if (c != null) tracked[c.optionId] else null
                if (c != null && t != null) tracked[c.optionId] = t.copy(result = c.result, profit = c.profit)
            }
        }
        val rid = (root["request_id"] as? JsonPrimitive)?.contentOrNull
        for (w in waiters) {
            val match = (w.reqId != null && rid == w.reqId) || ((w.reqId == null || rid == null) && name in w.names)
            if (match && w.deferred.complete(root)) waiters.remove(w)
        }
    }

    private suspend fun request(reqId: String, payload: String, vararg names: String, timeoutMs: Long = 12_000): JsonObject {
        ensureConnected()
        val w = Waiter(reqId, names.toSet(), CompletableDeferred())
        waiters += w
        return try {
            if (ws?.send(payload) != true) {
                authenticated = false
                error("Conexão com a IQ Option indisponível; tente novamente.")
            }
            withTimeout(timeoutMs) { w.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            err("timeout aguardando ${names.joinToString("/")}; mensagens recentes: ${recentNames()}")
            error("A IQ Option não respondeu a tempo.")
        } finally {
            waiters.remove(w)
        }
    }

    private suspend fun loadAssets(force: Boolean = false): List<IqProtocol.Asset> {
        if (!force && assets.isNotEmpty() && System.currentTimeMillis() - assetsAt < 60_000) return assets
        val id = reqId()
        val root = request(id, IqProtocol.initializationDataMessage(id), "initialization-data", timeoutMs = 20_000)
        val list = IqProtocol.parseAssets(root["msg"] as? JsonObject ?: JsonObject(emptyMap()))
        if (list.isEmpty()) {
            err("initialization-data sem ativos; mensagens recentes: ${recentNames()}")
            error("A IQ Option não devolveu a lista de ativos.")
        }
        assets = list
        assetsAt = System.currentTimeMillis()
        return list
    }

    // ------------------------------------------------------------------ ações de negócio
    private fun money(b: IqProtocol.Balance) = String.format(Locale.US, "%.2f %s", b.amount, b.currency).trim()

    private suspend fun switchAccount(target: String): String {
        ensureConnected()
        val wantReal = target.equals("real", true)
        val b = balances.values.firstOrNull { if (wantReal) it.isReal else it.isPractice }
            ?: error("Esta conta não tem uma carteira ${if (wantReal) "REAL" else "DEMO"} disponível.")
        if (activeBalanceId == b.id) return "A conta ${b.label} já está ativa (${money(b)})."
        val waiter = Waiter(null, setOf("balance-changed", "profile"), CompletableDeferred())
        waiters += waiter
        val confirmed = try {
            ws?.send(IqProtocol.changeBalanceMessage(b.id))
            runCatching { withTimeout(8_000) { waiter.deferred.await() } }.isSuccess
        } finally { waiters.remove(waiter) }
        activeBalanceId = b.id // as ordens carregam user_balance_id explícito → a conta local é a fonte da verdade
        val saldo = money(balances[b.id] ?: b)
        return "Conta ${b.label} ativa ($saldo" + if (confirmed) ")." else "; sem confirmação do servidor, mas as ordens usarão esta conta)."
    }

    private suspend fun candlesText(asset: String, tf: String?, count: Int?): String {
        val a = IqProtocol.resolveAsset(loadAssets(), asset) ?: return "Ativo '$asset' não encontrado. Use open_assets para ver os tickers."
        val size = IqProtocol.timeframeSeconds(tf ?: "60")
            ?: return "timeframe inválido. Use 60, 300, 900, 3600… (segundos) ou 1m/5m/15m/1h."
        val n = (count ?: 30).coerceIn(1, 200)
        val id = reqId()
        val root = request(id, IqProtocol.getCandlesMessage(id, a.id, size, n, serverNowSec()), "candles", timeoutMs = 20_000)
        val list = IqProtocol.parseCandles(root["msg"] as? JsonObject ?: JsonObject(emptyMap())).sortedBy { it.from }
        if (list.isEmpty()) return "A IQ Option não devolveu velas para ${a.ticker} (${size}s)."
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            appendLine("Velas ${a.ticker} · ${size}s · ${list.size} (horário UTC) — open high low close volume")
            list.forEach { c ->
                appendLine("${fmt.format(Date(c.from * 1000))} ${c.open} ${c.max} ${c.min} ${c.close} ${c.volume.toLong()}")
            }
            val first = list.first().close; val last = list.last().close
            if (first != 0.0) append(String.format(Locale.US, "Variação no período: %.4f%% (fechamento %s → %s)", (last - first) / first * 100, first, last))
        }.trim()
    }

    private fun openCount(): Int = tracked.values.count { it.result == null && it.expSec + 120 > serverNowSec() }

    private suspend fun buy(asset: String, direction: String, amount: Double, minutes: Int, settings: ConnectorSettings?, byAgent: Boolean): String {
        ensureConnected()
        val dir = direction.trim().lowercase()
        if (dir != "call" && dir != "put") return "direction deve ser call ou put."
        if (amount <= 0.0) return "amount deve ser maior que zero."
        if (minutes !in 1..60) return "minutes deve estar entre 1 e 60."
        val acc = activeBalance() ?: return "Nenhuma conta ativa."
        if (byAgent && settings != null) {
            if (acc.isReal && !settings.bool("allow_real_agent"))
                return "RECUSADO: a conta ativa é REAL e o usuário não liberou o agente para operar nela. Troque para demo (set_account) ou peça ao usuário para liberar nas configurações do conector."
            val maxAmount = settings.num("max_amount", 1.0)
            if (amount > maxAmount) return "RECUSADO: valor $amount excede o máximo permitido por operação ($maxAmount). Reduza o valor."
            val maxOpen = settings.num("max_open", 3.0).toInt()
            if (openCount() >= maxOpen) return "RECUSADO: já existem ${openCount()} operações abertas (máximo $maxOpen). Aguarde fechar."
        }
        val a = IqProtocol.resolveAsset(loadAssets(), asset) ?: return "Ativo '$asset' não encontrado. Use open_assets."
        if (!a.open) return "O ativo ${a.ticker} está fechado/suspenso agora."
        val exp = IqProtocol.expirationFor(serverNowSec(), minutes)
        val id = reqId()
        val root = request(id, IqProtocol.openOptionMessage(id, acc.id, a.id, dir, exp, amount, IqProtocol.isTurbo(minutes)), "option", "option-rejected", timeoutMs = 15_000)
        val ack = IqProtocol.parseOptionAck(root)
        val optId = ack.optionId
        if (optId == null) {
            err("ordem recusada: ${ack.error}")
            return "RECUSADA pela IQ Option: ${ack.error}"
        }
        tracked[optId] = Tracked(optId, a.ticker, dir, amount, exp, acc.isPractice, acc.currency)
        return "Ordem ABERTA na conta ${acc.label}: ${a.ticker} ${dir.uppercase()} ${money(acc.copy(amount = amount))} · expira em ~${minutes} min (id $optId). O resultado chega do servidor; consulte positions."
    }

    private fun positionsText(): String {
        val now = serverNowSec()
        val open = tracked.values.filter { it.result == null }.sortedBy { it.expSec }
        val closed = tracked.values.filter { it.result != null }.sortedByDescending { it.expSec }.take(10)
        return buildString {
            appendLine("== Em execução (${open.size}) ==")
            if (open.isEmpty()) appendLine("nenhuma")
            open.forEach { appendLine("${it.id} ${it.asset} ${it.direction.uppercase()} ${it.amount} ${if (it.demo) "DEMO" else "REAL"} · " + (if (it.expSec > now) "expira em ${it.expSec - now}s" else "aguardando resultado")) }
            appendLine("== Últimas fechadas ==")
            if (closed.isEmpty()) appendLine("nenhuma")
            closed.forEach { appendLine("${it.id} ${it.asset} ${it.direction.uppercase()} ${it.amount} ${if (it.demo) "DEMO" else "REAL"} → ${resultLabel(it.result)}") }
            append("Obs.: lista as operações abertas por este app, confirmadas pelo servidor.")
        }.trim()
    }

    private fun resultLabel(r: String?): String = when (r?.lowercase()) {
        "win" -> "WIN"
        "loose", "loss", "lose" -> "LOSS"
        "equal" -> "EMPATE"
        null -> "—"
        else -> r.uppercase()
    }

    // ------------------------------------------------------------------ UI declarativa
    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> {
        val blocks = mutableListOf<UiBlock>()
        val connectError = runCatching { ensureConnected() }.exceptionOrNull()
        val acc = activeBalance()
        if (connectError != null || acc == null) {
            blocks += UiBlock.Header("IQ Option", "Desconectado", "OFFLINE", Tone.BAD)
            blocks += UiBlock.Note(connectError?.let { friendly(it) } ?: "Aguardando dados da conta…", Tone.WARN)
            return blocks
        }
        blocks += UiBlock.Header("IQ Option", maskEmail(creds?.get("email").orEmpty()), "Conta ${acc.label}", if (acc.isReal) Tone.WARN else Tone.GOOD)
        blocks += UiBlock.Segmented(
            actionId = "account", title = "Conta",
            options = listOf("practice" to "Demo", "real" to "Real"),
            selected = if (acc.isReal) "real" else "practice",
            confirmValues = setOf("real"),
            confirmText = "Trocar para a conta REAL? Operações passam a usar dinheiro de verdade.",
        )
        val now = serverNowSec()
        val open = tracked.values.filter { it.result == null }.sortedBy { it.expSec }
        val closed = tracked.values.filter { it.result != null }
        val wins = closed.count { resultLabel(it.result) == "WIN" }
        val losses = closed.count { resultLabel(it.result) == "LOSS" }
        blocks += UiBlock.Stats(listOf(
            Stat("Saldo", money(acc), if (acc.isReal) Tone.WARN else Tone.NEUTRAL),
            Stat("Em execução", open.size.toString()),
            Stat("Sessão W/L", "$wins/$losses", if (wins > losses) Tone.GOOD else if (losses > wins) Tone.BAD else Tone.NEUTRAL),
        ))
        blocks += UiBlock.Table(
            "Operações em execução", listOf("Ativo", "Dir.", "Valor", "Expira"),
            open.map { listOf(it.asset, it.direction.uppercase(), String.format(Locale.US, "%.2f", it.amount),
                if (it.expSec > now) "${(it.expSec - now) / 60}:${String.format(Locale.US, "%02d", (it.expSec - now) % 60)}" else "resultado…") },
            empty = "Nenhuma operação em execução.",
        )
        blocks += UiBlock.Table(
            "Últimas operações", listOf("Ativo", "Dir.", "Valor", "Resultado"),
            closed.sortedByDescending { it.expSec }.take(8).map { listOf(it.asset, it.direction.uppercase(), String.format(Locale.US, "%.2f", it.amount), resultLabel(it.result)) },
            empty = "Sem operações fechadas nesta sessão.",
        )
        val assetRows = runCatching { loadAssets().filter { it.open }.take(15).map { listOf(it.ticker, if (it.kind == "turbo") "Turbo" else "Binária") } }.getOrNull()
        if (assetRows != null) blocks += UiBlock.Table("Ativos abertos", listOf("Ativo", "Tipo"), assetRows, empty = "Nenhum ativo aberto agora.")
        blocks += UiBlock.Note("Mostra as operações abertas por este app; resultados vêm de eventos reais do servidor. Operar envolve risco de perda.", Tone.NEUTRAL)
        return blocks
    }

    private fun maskEmail(e: String): String {
        val at = e.indexOf('@')
        return if (at <= 1) e else e.first() + "***" + e.substring(at)
    }

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> = try {
        when (actionId) {
            "account" -> Result.success(switchAccount(if (args["value"] == "real") "real" else "demo"))
            else -> Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        err("ação $actionId falhou: ${t.message}")
        Result.failure(IllegalStateException(friendly(t)))
    }

    // ------------------------------------------------------------------ ferramentas do agente
    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String = when (name) {
        "balance" -> {
            ensureConnected()
            val act = activeBalanceId
            if (balances.isEmpty()) "Sem dados de saldo ainda."
            else balances.values.sortedBy { it.type }.joinToString("\n") { "${it.label}: ${money(it)}" + if (it.id == act) " (ativa)" else "" }
        }
        "set_account" -> {
            val acc = args["account"].orEmpty().lowercase()
            when {
                acc != "demo" && acc != "real" -> "account deve ser demo ou real."
                acc == "real" && !settings.bool("allow_real_agent") ->
                    "RECUSADO: o usuário não liberou o agente para a conta REAL. Continue na DEMO."
                else -> switchAccount(acc)
            }
        }
        "open_assets" -> {
            val f = args["filter"].orEmpty().trim().uppercase()
            val list = loadAssets().filter { it.open && (f.isEmpty() || it.ticker.uppercase().contains(f)) }
            if (list.isEmpty()) "Nenhum ativo aberto" + (if (f.isNotEmpty()) " para '$f'" else "") + "."
            else "Ativos abertos (${list.size}): " + list.take(80).joinToString(", ") { "${it.ticker}${if (it.kind == "turbo") "" else "·bin"}" }
        }
        "get_candles" -> candlesText(args["asset"].orEmpty(), args["timeframe"], args["count"]?.toDoubleOrNull()?.toInt())
        "buy" -> buy(
            args["asset"].orEmpty(), args["direction"].orEmpty(),
            args["amount"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0,
            args["minutes"]?.toDoubleOrNull()?.toInt() ?: 1,
            settings, byAgent = true,
        )
        "positions" -> positionsText()
        else -> "Ferramenta desconhecida: $name"
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
