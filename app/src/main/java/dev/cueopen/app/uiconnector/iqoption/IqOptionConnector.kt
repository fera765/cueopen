package dev.cueopen.app.uiconnector.iqoption

import dev.cueopen.app.diag.ErrorLog
import dev.cueopen.app.CueApp
import dev.cueopen.app.diag.ConnectorDebugLog
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.ConnectorRetryBackoff
import dev.cueopen.app.uiconnector.ConnectorRetryDeferredException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Conector IQ Option (primeiro UI Connector do SDK).
 *
 * Login com e-mail/senha → ssid → WebSocket. Lista ativos e candles, abre opções e Forex, fecha Forex
 * e acompanha resultados retornados pelo servidor. Segurança:
 *  - a senha/ssid vivem só aqui (e no cofre cifrado do hub); nunca entram no contexto do LLM;
 *  - o agente só opera na conta REAL se o usuário liberar explicitamente nas configurações;
 *  - o agente respeita valor máximo por operação e máximo de operações abertas;
 *  - resultados de opções são recebidos do servidor; Forex exibe o P/L atual informado pela corretora.
 */
class IqOptionConnector(
    private val kv: KvStore? = null,
    private val httpClientProvider: ((OkHttpClient) -> OkHttpClient)? = null,
) : UiConnector {

    override val spec = UiConnectorSpec(
        id = "iqoption",
        name = "IQ Option",
        description = "Login, ativos abertos, candles, operações Turbo/binárias e posições Forex.",
        version = "1.0.0",
        loginFields = listOf(
            LoginField("email", "E-mail", FieldType.EMAIL, hint = "seu@email.com"),
            LoginField("password", "Senha", FieldType.PASSWORD),
        ),
        settings = listOf(
            SettingSpec(
                "allow_real_agent", "Agente pode operar na conta REAL",
                "Desligado por padrão. Ligado: o agente pode abrir operações com dinheiro real, sempre dentro dos limites abaixo.",
                SettingType.SWITCH, "false", risky = true,
            ),
            SettingSpec("max_amount", "Valor máximo por opção", "Teto por operação Turbo/binária aberta pelo agente, na moeda da conta.", SettingType.NUMBER, "1"),
            SettingSpec("max_open", "Máx. de opções abertas", "Número máximo de opções ainda em execução abertas pelo agente.", SettingType.NUMBER, "3"),
            SettingSpec("daily_stop_loss", "Stop loss diário", "Perda diária nas opções acompanhadas que bloqueia novas operações até amanhã. 0 = sem limite.", SettingType.NUMBER, "10"),
            SettingSpec("daily_take_profit", "Meta diária", "Lucro diário nas opções acompanhadas que bloqueia novas operações até amanhã. 0 = sem limite.", SettingType.NUMBER, "10"),
            SettingSpec("max_consec_losses", "Perdas seguidas para pausar", "Após N perdas seguidas em opções, o agente pausa pelo tempo abaixo.", SettingType.NUMBER, "3"),
            SettingSpec("cooldown_min", "Pausa após perdas seguidas (min)", "Tempo de pausa depois de atingir o limite de perdas seguidas.", SettingType.NUMBER, "30"),
            SettingSpec("require_jev", "Exigir aprovação TypeSafe Jev", "Ligado: cada abertura exige aprovação prévia do gate genérico de ordens. Desligado: permanecem ativos os limites de conta e risco deste conector.", SettingType.SWITCH, "false", risky = true),
            SettingSpec("allow_margin_agent", "Agente pode operar Forex", "Desligado por padrão. Ligado: permite abrir Forex com stop loss e take profit obrigatórios.", SettingType.SWITCH, "false", risky = true),
            SettingSpec("max_margin_amount", "Valor máximo por posição Forex", "Teto do valor investido por posição Forex aberta pelo agente.", SettingType.NUMBER, "1"),
            SettingSpec("max_forex_open", "Máx. de posições Forex abertas", "Limite de posições Forex abertas pelo agente.", SettingType.NUMBER, "3"),
            SettingSpec("max_leverage", "Alavancagem máxima", "Maior alavancagem que o agente pode usar em Forex.", SettingType.NUMBER, "10"),
            SettingSpec("max_stop_loss_pct", "Stop loss máximo (%)", "Stop loss obrigatório máximo em % do valor investido em Forex.", SettingType.NUMBER, "50"),
            SettingSpec("max_take_profit_pct", "Take profit máximo (%)", "Teto de take profit na abertura ou alteração de uma posição Forex.", SettingType.NUMBER, "500"),
            SettingSpec("forex_monitor_interval_sec", "Atualizar posições Forex a cada (s)", "Frequência configurável para consultar e atualizar posições Forex (5–300 s; padrão 30). SL/TP são enviados na abertura; adjust_forex_stops solicita alteração depois.", SettingType.NUMBER, "30"),
        ),
        tools = listOf(
            ToolSpec("open_assets", "Lista todos os ativos abertos agora: opções Turbo/binárias (inclui OTC) e Forex.", listOf(
                ParamSpec("filter", "Filtro opcional pelo ticker (ex.: EUR)", required = false),
                ParamSpec("kind", "Tipo: all, options, turbo, binary ou forex. Padrão all.", required = false, allowed = listOf("all", "options", "turbo", "binary", "forex")),
            )),
            ToolSpec("get_candles", "Busca candles OHLCV reais de um ativo aberto, sem análise ou recomendação de direção.", listOf(
                ParamSpec("asset", "Ticker do ativo (ex.: EURUSD ou EURUSD-OTC)"),
                ParamSpec("timeframe", "Período em segundos (60=M1, 120=M2, 300=M5, 600=M10) ou 1m/2m/5m/10m. Padrão 60.", required = false),
                ParamSpec("count", "Quantidade de candles (1–200). Padrão 30.", "integer", required = false),
            )),
            ToolSpec("open_option", "Abre imediatamente uma operação CALL/PUT Turbo ou binária no ativo aberto. Não analisa nem escolhe ativos. Resultado final é consultado em get_trade_result; não há fechamento antecipado binário exposto.", listOf(
                ParamSpec("asset", "Ticker Turbo/binário aberto"),
                ParamSpec("direction", "call (alta) ou put (baixa)", allowed = listOf("call", "put")),
                ParamSpec("amount", "Valor da operação na moeda da conta", "number"),
                ParamSpec("minutes", "Expiração em minutos (1–60). Até 5 min é Turbo.", "integer", required = false),
            ), risky = true, requiresSignal = true),
            ToolSpec("open_forex", "Abre uma posição Forex a mercado com stop loss e take profit obrigatórios. Só atua em Forex, não CFD nem cripto.", listOf(
                ParamSpec("asset", "Ticker Forex aberto (ex.: EURUSD)"),
                ParamSpec("side", "buy ou sell", allowed = listOf("buy", "sell")),
                ParamSpec("amount", "Valor investido (margem) na moeda da conta", "number"),
                ParamSpec("leverage", "Alavancagem de 1 até o limite configurado", "integer"),
                ParamSpec("stop_loss_pct", "Stop loss obrigatório em % do valor investido", "number"),
                ParamSpec("take_profit_pct", "Take profit obrigatório em % do valor investido", "number"),
            ), risky = true, requiresSignal = true),
            ToolSpec("close_position", "Solicita o fechamento de uma posição Forex aberta, informando seu id. Não fecha opções binárias.", listOf(
                ParamSpec("id", "Id da posição Forex aberta", "integer"),
            ), risky = true),
            ToolSpec("adjust_forex_stops", "Solicita a alteração do stop loss e take profit percentuais de uma posição Forex aberta pelo id. Só reporta aceitação após ACK tpsl-changed 2000; protocolo comunitário não oficial.", listOf(
                ParamSpec("id", "Id da posição Forex aberta", "integer"),
                ParamSpec("stop_loss_pct", "Novo stop loss (%) dentro do limite configurado", "number"),
                ParamSpec("take_profit_pct", "Novo take profit (%) dentro do limite configurado", "number"),
            ), risky = true),
            ToolSpec("positions", "Lista opções acompanhadas por esta sessão e posições Forex abertas com o P/L atual informado pela corretora."),
            ToolSpec("get_trade_result", "Consulta o estado ou resultado final de uma opção acompanhada por esta sessão. O histórico final de posições Forex fechadas não é confirmado pelo protocolo disponível.", listOf(
                ParamSpec("id", "Id da opção retornado na abertura", "integer"),
            )),
        ),
    )

    // ------------------------------------------------------------------ estado
    private data class Tracked(
        val id: Long, val asset: String, val direction: String, val amount: Double,
        val expSec: Long, val demo: Boolean, val currency: String,
        val result: String? = null, val profit: Double? = null,
        val activeId: Int = 0, val entry: Double? = null,
        val byAgent: Boolean = false, val settled: Boolean = false,
        val expiryConfirmed: Boolean = false,
    )

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val client: OkHttpClient get() = httpClientProvider?.invoke(baseClient) ?: baseClient
    private val mutex = Mutex()
    private val connectionRetryBackoff = ConnectorRetryBackoff()
    /** Reserva comum mantida até ACK para novas ordens e troca de conta. */
    private val accountOrderCoordinator = IqOptionOrderCoordinator()
    private val counter = AtomicLong(1)
    private val generation = AtomicLong(0)
    private val _stateChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val stateChanges: SharedFlow<Unit> = _stateChanges
    override val supportsPushUpdates: Boolean = true
    override val fallbackRefreshMs: Long? get() = cachedForexPositions?.let { forexMonitorIntervalMs }
    private val debug: ConnectorDebugLog get() = CueApp.instance.connectorDebugLog
    private val accountMessageFlow = IqAccountMessageFlow()
    private val balances get() = accountMessageFlow.balances
    private val activeBalanceSelection get() = accountMessageFlow.selection
    @Volatile private var marketAccountId: Long? = null
    private val tracked = ConcurrentHashMap<Long, Tracked>()
    private val quotes = ConcurrentHashMap<Int, Double>()
    private val subscribed = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    @Volatile private var allAssets: List<IqProtocol.Asset> = emptyList()
    @Volatile private var allAssetsAt = 0L
    private val marketCircuit = IqMarketCircuitBreaker()
    private val marketProbeLocks = ConcurrentHashMap<String, Mutex>()
    /** Tarefas de fundo (carregar ativos) — a tela nunca espera por elas. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loadingAll = false
    /** Execução manual isolada por uma porta de pedidos mockável em testes JVM. */
    private val manualExecution = IqManualExecution(
        nextRequestId = ::reqId,
        request = IqManualRequestPort { requestId, payload, expectedNames, timeoutMs ->
            request(requestId, payload, *expectedNames.toTypedArray(), timeoutMs = timeoutMs)
        },
    )

    /** Atualiza a lista completa de ativos em segundo plano (sem travar a tela). */
    private fun refreshAssetsInBackground() {
        if (loadingAll || (allAssets.isNotEmpty() && System.currentTimeMillis() - allAssetsAt < 5 * 60_000)) return
        loadingAll = true
        bg.launch { try { runCatching { loadAllAssets(force = true) } } finally { loadingAll = false } }
    }
    private val seen = ArrayDeque<String>()

    @Volatile private var ssid: String? = null
    @Volatile private var ws: WebSocket? = null
    private val authenticated get() = accountMessageFlow.authenticated
    @Volatile private var creds: Map<String, String>? = null
    private val serverClock = IqServerClock()
    @Volatile private var assets: List<IqProtocol.Asset> = emptyList()
    @Volatile private var assetsAt = 0L

    override val isLoggedIn: Boolean get() = creds != null

    override fun secrets(): List<String> = listOfNotNull(ssid, creds?.get("password"), creds?.get("email"))

    private fun serverNowSec(): Long = serverNowMs() / 1000
    private fun serverNowMs(): Long {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        return serverClock.nowMs(elapsed) ?: System.currentTimeMillis()
    }
    private fun synchronizedServerNowSec(): Long? = serverClock.nowSec(android.os.SystemClock.elapsedRealtime())
    private fun reqId() = "cue-" + counter.getAndIncrement()
    private fun err(detail: String) {
        runCatching { ErrorLog.log("uiconnector", "iqoption: $detail", null, "iqoption") }
        runCatching { debug.recordEvent("iqoption", "connector-error", "operation failed; detail omitted") }
    }

    private fun noteSeen(name: String) = synchronized(seen) {
        seen.addLast(name)
        while (seen.size > 40) seen.removeFirst()
    }

    private fun recentNames(): String = synchronized(seen) { seen.toList().distinct().joinToString(",") }

    private fun activeBalance(): IqProtocol.Balance? = accountMessageFlow.activeBalance()

    // ------------------------------------------------------------------ login / sessão
    override suspend fun login(credentials: Map<String, String>): Result<Unit> =
        accountOrderCoordinator.runReserved { loginReserved(credentials) }

    private suspend fun loginReserved(credentials: Map<String, String>): Result<Unit> = try {
        val e = credentials["email"].orEmpty().trim()
        val p = credentials["password"].orEmpty()
        require(e.isNotEmpty() && p.isNotEmpty()) { "Informe e-mail e senha." }
        debug.rememberSecrets("iqoption", listOf(e, p))
        mutex.withLock { connectLocked(e, p) }
        creds = mapOf("email" to e, "password" to p)
        _stateChanges.tryEmit(Unit)
        debug.recordEvent("iqoption", "authentication", "login aceito e carteira inicial confirmada")
        connectionRetryBackoff.reset("iqoption")
        resetMarketCircuit() // login explícito: circuitos antigos não migram entre contas/sessões
        // carrega TODOS os ativos uma vez, já no login, e deixa em cache (o agente não espera por isso depois)
        allAssetsAt = 0L
        refreshAssetsInBackground()
        Result.success(Unit)
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        connectionRetryBackoff.recordFailure("iqoption", android.os.SystemClock.elapsedRealtime(), t.message ?: t.javaClass.simpleName)
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
        debug.rememberSecrets("iqoption", listOfNotNull(ssid))
        val sid = ssid ?: error("Sessão inválida.")
        val req = Request.Builder().url(IqProtocol.WS_URL)
            .header("Origin", IqProtocol.ORIGIN).header("User-Agent", UA).build()
        // Geração: callbacks de um socket antigo (já fechado) nunca podem derrubar a conexão nova.
        val myGen = generation.get()
        val profileWaiter = accountMessageFlow.beginConnection(myGen)
        try {
            val wsStartedAt = System.nanoTime()
            debug.recordEvent("iqoption", "websocket", "connecting", IqProtocol.WS_URL)
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (myGen == generation.get()) {
                        debug.recordHttp("iqoption", "GET", IqProtocol.WS_URL, listOf("Origin", "User-Agent"),
                            (System.nanoTime() - wsStartedAt) / 1_000_000L, response.code, "WebSocket handshake")
                        webSocket.send(IqProtocol.ssidMessage(sid))
                        _stateChanges.tryEmit(Unit)
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) { if (myGen == generation.get()) handle(text, myGen) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (myGen == generation.get()) {
                        debug.recordEvent("iqoption", "websocket", "closed code=$code")
                        dropConnection("socket fechado")
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (myGen == generation.get()) {
                        debug.recordFailure("iqoption", "websocket", t, IqProtocol.WS_URL, "GET", (System.nanoTime() - wsStartedAt) / 1_000_000L)
                        response?.let { debug.recordHttp("iqoption", "GET", IqProtocol.WS_URL, listOf("Origin", "User-Agent"),
                            (System.nanoTime() - wsStartedAt) / 1_000_000L, it.code, "handshake recusado") }
                        dropConnection("falha de transporte WebSocket")
                    }
                }
            })
            withTimeout(20_000) { profileWaiter.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            err("sem perfil/carteira ativa confirmados após autenticar; mensagens recentes: ${recentNames()}")
            closeSocket()
            throw IllegalStateException("A IQ Option não confirmou uma carteira ativa após autenticar. O protocolo pode ter mudado ou a conta está indisponível; novas ordens permanecem bloqueadas. Baixe o log de erros em Configurações.")
        } finally {
            accountMessageFlow.removeWaiter(profileWaiter)
        }
    }

    private fun httpLogin(email: String, password: String): String {
        val req = Request.Builder().url(IqProtocol.LOGIN_URL)
            .post(IqProtocol.loginBody(email, password).toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA).header("Origin", IqProtocol.ORIGIN).header("Accept", "application/json").build()
        val started = System.nanoTime()
        try {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                debug.recordHttp("iqoption", req.method, req.url.toString(), req.headers.names(),
                    (System.nanoTime() - started) / 1_000_000L, r.code, "resposta de autenticação; corpo omitido")
                return when (val o = IqProtocol.parseLogin(r.code, body, r.headers("Set-Cookie"))) {
                    is IqProtocol.LoginOutcome.Success -> o.ssid
                    is IqProtocol.LoginOutcome.Failure -> throw IllegalStateException(o.message)
                    is IqProtocol.LoginOutcome.TwoFactor -> throw IllegalStateException(o.message)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            debug.recordFailure("iqoption", "http-login", t, req.url.toString(), req.method, (System.nanoTime() - started) / 1_000_000L)
            throw t
        }
    }

    private fun dropConnection(reason: String) {
        generation.incrementAndGet() // invalida callbacks tardios do socket perdido
        subscribed.clear()
        accountMessageFlow.markUnauthenticated()
        ws = null
        serverClock.clear()
        val previousFailure = activeBalanceSelection.failureMessage()
        val pendingTarget = activeBalanceSelection.requestedTargetId()
        when {
            activeBalanceSelection.switchPending() && pendingTarget != null -> activeBalanceSelection.finishSwitch(
                pendingTarget, false,
                "Conexão interrompida durante a troca de carteira; conta atual desconhecida. Sincronize explicitamente antes de operar.",
            )
            previousFailure != null -> activeBalanceSelection.failReconciliation(previousFailure)
            else -> activeBalanceSelection.invalidate()
        }
        balances.clear()
        marketAccountId = null
        val failure = IOException("Conexão com a IQ Option perdida: $reason")
        accountMessageFlow.failWaiters(failure)
        debug.recordEvent("iqoption", "websocket", "connection dropped; account selection requires confirmation")
        _stateChanges.tryEmit(Unit)
    }

    private fun closeSocket() {
        generation.incrementAndGet() // invalida os callbacks do socket que está sendo fechado
        runCatching { ws?.close(1000, null) }
        ws = null
        accountMessageFlow.markUnauthenticated()
        subscribed.clear()
        serverClock.clear()
        accountMessageFlow.invalidateAccount()
        balances.clear()
        marketAccountId = null
    }

    override suspend fun logout() = accountOrderCoordinator.runReserved { logoutReserved() }

    private suspend fun logoutReserved() {
        resetMarketCircuit()
        connectionRetryBackoff.reset("iqoption")
        closeSocket()
        ssid = null
        creds = null
        balances.clear()
        marketAccountId = null
        tracked.clear()
        quotes.clear()
        serverClock.clear()
        assets = emptyList()
        allAssets = emptyList()
        accountMessageFlow.invalidateAccount()
        _stateChanges.tryEmit(Unit)
        debug.recordEvent("iqoption", "authentication", "logged out")
    }

    private suspend fun ensureConnected() {
        activeBalanceSelection.failureMessage()?.let { throw IllegalStateException(it) }
        fun ready(): Boolean = authenticated && ws != null && (activeBalance() != null || activeBalanceSelection.switchPending())
        if (ready()) return
        val c = creds ?: error("Sessão da IQ Option não iniciada. Faça login no conector.")
        mutex.withLock {
            if (ready()) return@withLock
            val now = android.os.SystemClock.elapsedRealtime()
            val waitMs = connectionRetryBackoff.remainingMs("iqoption", now)
            if (waitMs > 0L) {
                val waitSec = (waitMs + 999L) / 1_000L
                val reason = connectionRetryBackoff.state("iqoption")?.reason.orEmpty().take(120)
                throw ConnectorRetryDeferredException(
                    "Reconexão IQ Option pausada após falha recente; nova tentativa em ${waitSec}s." +
                        if (reason.isBlank()) "" else " Último erro: $reason"
                )
            }
            try {
                connectLocked(c["email"].orEmpty(), c["password"].orEmpty())
                connectionRetryBackoff.reset("iqoption")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                connectionRetryBackoff.recordFailure("iqoption", android.os.SystemClock.elapsedRealtime(), t.message ?: t.javaClass.simpleName)
                throw t
            }
        }
        // reconectou: restaura somente cotações de operações em aberto
        tracked.values.filter { it.result == null && it.activeId != 0 }.forEach { subscribeQuotes(it.activeId) }
    }

    /** Credenciais presentes não provam que o socket está vivo; exige sessão autenticada e timeSync recente. */
    private fun subscribeQuotes(activeId: Int) {
        if (activeId == 0 || !subscribed.add(activeId)) return
        if (ws?.send(IqProtocol.subscribeQuotesMessage(activeId)) != true) subscribed.remove(activeId)
    }

    /** Larga a assinatura de cotações quando não há mais operação aberta naquele ativo. */
    private fun pruneQuotes() {
        val needed = tracked.values.filter { it.result == null && it.expSec + 120 > serverNowSec() }.map { it.activeId }.toMutableSet()
        subscribed.filter { it !in needed }.forEach { id ->
            subscribed.remove(id)
            runCatching { ws?.send(IqProtocol.unsubscribeQuotesMessage(id)) }
            quotes.remove(id)
        }
    }

    // ------------------------------------------------------------------ mensagens
    private fun handle(text: String, eventGeneration: Long) {
        val root = IqProtocol.parseObject(text) ?: return
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull ?: return
        noteSeen(name)
        val msg = root["msg"] as? JsonObject
        val accountUpdate = accountMessageFlow.onMessage(root, eventGeneration)
        var stateChanged = accountUpdate?.stateChanged ?: false
        if (accountUpdate != null) {
            val confirmed = accountUpdate.activeBalanceId
            if (confirmed != null) {
                val previous = marketAccountId
                marketAccountId = confirmed
                if (previous != null && previous != confirmed) resetMarketCircuit()
            }
            if (accountUpdate.serverBalanceId != null && !accountUpdate.accepted) {
                val label = if (accountUpdate.eventName == "profile") "profile" else "balance event"
                debug.recordEvent("iqoption", "account-event", "stale or unrelated $label ignored; active wallet unchanged")
            }
        }
        when (name) {
            "timeSync" -> {
                val serverMs = (root["msg"] as? JsonPrimitive)?.longOrNull
                if (serverMs != null) {
                    serverClock.update(serverMs, android.os.SystemClock.elapsedRealtime())
                }
            }
            "option-closed", "socket-option-closed" -> {
                val c = IqProtocol.parseOptionClosed(root)
                val t = if (c != null) tracked[c.optionId] else null
                if (c != null && t != null) {
                    val done = t.copy(result = c.result, profit = c.profit)
                    tracked[c.optionId] = done
                    if (done != t) stateChanged = true
                    if (done.byAgent && !done.settled) { settle(done); tracked[c.optionId] = done.copy(settled = true) }
                }
            }
            "candle-generated" -> {
                val q = if (msg != null) IqProtocol.parseQuote(msg) else null
                if (q != null) {
                    quotes[q.activeId] = q.price
                    // operação sem preço de entrada confirmado: usa a 1ª cotação após a abertura
                    tracked.values.filter { it.activeId == q.activeId && it.entry == null && it.result == null }
                        .forEach { current ->
                            val updated = current.copy(entry = q.price)
                            tracked[current.id] = updated
                            if (updated != current) stateChanged = true
                        }
                }
            }
            "socket-option-opened", "option-opened" -> {
                // operações abertas fora do app (web/celular) também aparecem e são acompanhadas ao vivo
                val o = IqProtocol.parseOptionOpened(root)
                if (o != null) {
                    val existing = tracked[o.optionId]
                    if (existing != null) {
                        val updated = existing.copy(expSec = o.expSec, expiryConfirmed = true, entry = existing.entry ?: o.openPrice)
                        tracked[o.optionId] = updated
                        if (updated != existing) stateChanged = true
                    } else {
                    val acc = activeBalance()
                    val ticker = (allAssets + assets).firstOrNull { it.id == o.activeId }?.ticker ?: "#${o.activeId}"
                    tracked[o.optionId] = Tracked(o.optionId, ticker, o.direction, o.amount, o.expSec, acc?.isPractice ?: true, acc?.currency ?: "",
                        activeId = o.activeId, entry = o.openPrice, expiryConfirmed = true)
                    stateChanged = true
                    }
                    subscribeQuotes(o.activeId)
                }
            }
        }
        if (stateChanged) _stateChanges.tryEmit(Unit)
    }

    private suspend fun request(reqId: String, payload: String, vararg names: String, timeoutMs: Long = 12_000): JsonObject {
        ensureConnected()
        val w = accountMessageFlow.registerWaiter(reqId.takeIf { it.isNotBlank() }, names.toSet())
        val started = System.nanoTime()
        return try {
            if (ws?.send(payload) != true) {
                accountMessageFlow.markUnauthenticated()
                error("Conexão com a IQ Option indisponível; tente novamente.")
            }
            debug.recordEvent("iqoption", "websocket-request", "sent type=${names.joinToString("+").take(100)} request_id=${reqId.take(40)}")
            withTimeout(timeoutMs) { w.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            // O logger registra apenas categoria/tipo/duração; nunca a solicitação nem o conteúdo recebido.
            debug.recordFailure("iqoption", "websocket-request", t, IqProtocol.WS_URL, "WEBSOCKET", (System.nanoTime() - started) / 1_000_000L)
            error("A IQ Option não respondeu a tempo.")
        } finally {
            accountMessageFlow.removeWaiter(w)
        }
    }

    private suspend fun loadAssets(force: Boolean = false): List<IqProtocol.Asset> {
        if (!force && assets.isNotEmpty()) {
            // cache: responde na hora; se estiver velho (> 5 min), atualiza em segundo plano
            if (System.currentTimeMillis() - assetsAt > 5 * 60_000) refreshAssetsInBackground()
            return assets
        }
        val requestGeneration = marketCircuit.contextGeneration()
        val id = reqId()
        val root = request(id, IqProtocol.initializationDataMessage(id), "initialization-data", timeoutMs = 20_000)
        val list = IqProtocol.parseAssets(root["msg"] as? JsonObject ?: JsonObject(emptyMap()))
        if (list.isEmpty()) {
            err("initialization-data sem ativos; mensagens recentes: ${recentNames()}")
            error("A IQ Option não devolveu a lista de ativos.")
        }
        if (requestGeneration != marketCircuit.contextGeneration()) return assets
        assets = list
        assetsAt = System.currentTimeMillis()
        return list
    }

    /** Carrega opções Turbo/binárias e os instrumentos Forex disponíveis. */
    private suspend fun loadAllAssets(force: Boolean = false): List<IqProtocol.Asset> {
        if (!force && allAssets.isNotEmpty()) {
            if (System.currentTimeMillis() - allAssetsAt > 5 * 60_000) refreshAssetsInBackground()
            return allAssets
        }
        val contextAtStart = marketCircuit.contextGeneration()
        val out = ArrayList(loadAssets(force))
        if (contextAtStart != marketCircuit.contextGeneration()) return allAssets
        val type = "forex"
        val probeLock = marketProbeLocks.computeIfAbsent(type) { Mutex() }
        val forex = probeLock.withLock {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (contextAtStart != marketCircuit.contextGeneration() || marketCircuit.isOpen(type, nowElapsed)) {
                emptyList()
            } else try {
                // O wrapper comunitário envia get-instruments com request_id vazio e recebe
                // a resposta pelo nome "instruments"; não correlacionar com um ID inventado.
                val root = request("", IqProtocol.forexInstrumentsMessage(), "instruments", timeoutMs = 15_000)
                val list = IqProtocol.parseForexInstruments(root["msg"] as? JsonObject ?: JsonObject(emptyMap()), serverNowSec())
                if (contextAtStart != marketCircuit.contextGeneration()) emptyList()
                else {
                    marketCircuit.recordResponse(type, android.os.SystemClock.elapsedRealtime(), list.size)?.let { decision ->
                        if (decision.shouldLog) reportMarketFailure(type, decision.state.reason, decision.state.retryAtElapsedMs)
                    }
                    list
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (contextAtStart == marketCircuit.contextGeneration()) {
                    val decision = marketCircuit.recordFailure(type, android.os.SystemClock.elapsedRealtime(), marketFailureReason(t))
                    if (decision.shouldLog) reportMarketFailure(type, decision.state.reason, decision.state.retryAtElapsedMs)
                }
                emptyList()
            }
        }
        out += forex
        allAssets = out
        allAssetsAt = System.currentTimeMillis()
        return out
    }

    private fun marketFailureReason(t: Throwable): String = when {
        t.message.orEmpty().contains("não respondeu a tempo", true) -> "timeout"
        t is IOException -> "transport"
        else -> "request/protocol"
    }

    private fun reportMarketFailure(type: String, reason: String, retryAtElapsedMs: Long) {
        val remainingSec = ((retryAtElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L
        err("$type temporariamente indisponível durante probing (motivo=$reason; suporte não confirmado); nova tentativa após ${countdown(remainingSec)}.")
    }

    private fun resetMarketCircuit() {
        marketCircuit.resetContext()
        assets = emptyList()
        assetsAt = 0L
        allAssets = emptyList()
        allAssetsAt = 0L
        cachedForexPositions = null
        cachedForexPositionsAt = 0L
    }

    private fun marketRetryHint(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        val state = marketCircuit.activeStatuses(now)["forex"] ?: return ""
        val left = ((state.retryAtElapsedMs - now).coerceAtLeast(0L) + 999L) / 1_000L
        return "\nForex não verificado; nova tentativa em ${countdown(left)} (suporte não confirmado)."
    }

    /** Sugestões de tickers abertos parecidos, para o agente acertar na próxima chamada. */
    private fun similar(asset: String): String {
        val list = runCatching { IqProtocol.suggestAssets(allAssets.ifEmpty { assets }, asset) }.getOrDefault(emptyList())
        return if (list.isEmpty()) " Use open_assets para ver os tickers abertos." else " Parecidos abertos agora: ${list.joinToString()}. (Ou use open_assets.)"
    }

    private fun kindLabel(k: String) = when (k) { "turbo" -> "Turbo"; "binary" -> "Binária"; "forex" -> "Forex"; else -> k }

    private fun countdown(sec: Long) = "${sec / 60}:${String.format(Locale.US, "%02d", sec % 60)}"

    private fun expiryUtc(epochSec: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss 'UTC'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochSec * 1_000L))

    /** Situação ao vivo de uma opção aberta: tempo restante + ganhando/perdendo agora pela cotação real. */
    private fun liveStatus(t: Tracked, now: Long): String {
        val price = quotes[t.activeId]
        val outcome = IqProtocol.liveOutcome(t.direction, t.entry, price)
        val time = if (t.expSec > now) countdown(t.expSec - now) else "resultado…"
        val arrow = when (outcome) { "WIN" -> " ▲ ganhando"; "LOSS" -> " ▼ perdendo"; "EMPATE" -> " = empate"; else -> " · aguardando cotação" }
        return time + arrow
    }

    private fun fmtPx(v: Double?) = v?.let { java.math.BigDecimal(it).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } ?: "—"

    // ------------------------------------------------------------------ posições Forex
    @Volatile private var cachedForexPositions: List<IqProtocol.MarginPos>? = null
    @Volatile private var cachedForexPositionsAt = 0L
    @Volatile private var forexMonitorIntervalMs = IqForexMonitoringInterval.DEFAULT_MS

    private suspend fun marginPositions(force: Boolean = false): List<IqProtocol.MarginPos> {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedForexPositions?.takeIf { !force && now - cachedForexPositionsAt < forexMonitorIntervalMs }?.let { return it }
        val acc = activeBalance() ?: return emptyList()
        val id = reqId()
        val root = request(id, IqProtocol.getPositionsMessage(id, acc.id), "positions", timeoutMs = 12_000)
        val rows = IqProtocol.parsePositions(root["msg"] as? JsonObject ?: JsonObject(emptyMap())
        ).filter { it.type.equals("forex", ignoreCase = true) }
        cachedForexPositions = rows
        cachedForexPositionsAt = now
        return rows
    }

    private suspend fun openForex(args: Map<String, String>, settings: ConnectorSettings): String =
        accountOrderCoordinator.runReserved { openForexReserved(args, settings) }

    private suspend fun openForexReserved(args: Map<String, String>, settings: ConnectorSettings): String {
        ensureConnected()
        val acc = accountMessageFlow.orderBalance() ?: return "RECUSADO: nenhuma carteira selecionada consta no perfil autenticado; sincronize a conta antes de operar."
        IqForexAccessPolicy.denialReason(acc.isReal, settings.bool("allow_margin_agent"), settings.bool("allow_real_agent"))?.let { return it }
        val side = args["side"].orEmpty().lowercase()
        if (side != "buy" && side != "sell") return "side deve ser buy ou sell."
        val amount = args["amount"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val lev = args["leverage"]?.toDoubleOrNull()?.toInt() ?: 0
        val sl = args["stop_loss_pct"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val tp = args["take_profit_pct"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        if (!amount.isFinite() || amount <= 0.0) return "amount deve ser finito e maior que zero."
        val maxAmount = settings.num("max_margin_amount", 1.0)
        if (amount > maxAmount) return "RECUSADO: valor $amount excede o máximo por posição Forex ($maxAmount)."
        val maxLev = settings.num("max_leverage", 10.0).toInt().coerceAtLeast(1)
        if (lev < 1 || lev > maxLev) return "RECUSADO: alavancagem deve ser de 1 a $maxLev."
        val maxSl = settings.num("max_stop_loss_pct", 50.0)
        if (!sl.isFinite() || sl <= 0 || sl > maxSl) return "RECUSADO: stop loss obrigatório, entre 0 e $maxSl%."
        val maxTp = settings.num("max_take_profit_pct", 500.0)
        if (tp <= 0 || !tp.isFinite() || tp > maxTp) return "RECUSADO: take profit obrigatório, entre 0 e $maxTp%."
        val maxOpen = settings.num("max_forex_open", 3.0).toInt().coerceAtLeast(1)
        val positions = marginPositions(force = true)
        if (positions.size >= maxOpen) return "RECUSADO: já existem ${positions.size} posições Forex abertas (máximo $maxOpen)."
        val query = args["asset"].orEmpty()
        fun norm(x: String) = x.uppercase().replace("/", "").replace(" ", "").replace("_", "")
        val a = loadAllAssets().firstOrNull { it.kind == "forex" && it.instrumentId.isNotEmpty() && (norm(it.ticker) == norm(query) || norm(it.instrumentId) == norm(query)) }
            ?: return "Ativo Forex '$query' não encontrado. Use open_assets."
        if (!a.open) return "O ativo Forex ${a.ticker} está fechado agora."
        val ack = manualExecution.openForex(IqForexOrder(acc.id, a.instrumentId, side, amount, lev, sl, tp))
        val orderId = ack.optionId ?: run { err("ordem Forex recusada: ${ack.error}"); return "RECUSADA pela IQ Option: ${ack.error}" }
        cachedForexPositions = null
        return "Ordem Forex ENVIADA na conta ${acc.label}: ${a.ticker} ${side.uppercase()} ${String.format(Locale.US, "%.2f", amount)} ×$lev · SL $sl% · TP $tp% (id $orderId). Consulte positions para o P/L atual."
    }

    private suspend fun closeForex(idRaw: String?, settings: ConnectorSettings): String =
        accountOrderCoordinator.runReserved { closeForexReserved(idRaw, settings) }

    private suspend fun closeForexReserved(idRaw: String?, settings: ConnectorSettings): String {
        ensureConnected()
        val acc = accountMessageFlow.orderBalance() ?: return "RECUSADO: nenhuma carteira selecionada consta no perfil autenticado; sincronize a conta antes de operar."
        IqForexAccessPolicy.denialReason(acc.isReal, settings.bool("allow_margin_agent"), settings.bool("allow_real_agent"))?.let { return it }
        val positionId = idRaw?.toLongOrNull()?.takeIf { it > 0L } ?: return "id inválido."
        val open = marginPositions(force = true)
        if (open.none { it.id == positionId }) return "RECUSADO: a posição Forex $positionId não aparece como aberta na conta ativa; nenhum comando de fechamento foi enviado."
        val ack = manualExecution.closeForex(positionId)
        cachedForexPositions = null
        return if (ack.accepted) "Fechamento da posição Forex $positionId solicitado à IQ Option${ack.status?.let { " (status $it)" } ?: ""}. O resultado financeiro final não é confirmado por este protocolo; consulte positions para a situação atual."
        else "A IQ Option recusou fechar a posição Forex $positionId (status ${ack.status ?: "desconhecido"})."
    }

    private suspend fun adjustForexStops(args: Map<String, String>, settings: ConnectorSettings): String =
        accountOrderCoordinator.runReserved { adjustForexStopsReserved(args, settings) }

    private suspend fun adjustForexStopsReserved(args: Map<String, String>, settings: ConnectorSettings): String {
        ensureConnected()
        val acc = accountMessageFlow.orderBalance() ?: return "RECUSADO: nenhuma carteira selecionada consta no perfil autenticado; sincronize a conta antes de operar."
        IqForexAccessPolicy.denialReason(acc.isReal, settings.bool("allow_margin_agent"), settings.bool("allow_real_agent"))?.let { return it }
        val positionId = args["id"]?.toLongOrNull()?.takeIf { it > 0L } ?: return "id inválido."
        val stopLossPct = args["stop_loss_pct"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val takeProfitPct = args["take_profit_pct"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val maxSl = settings.num("max_stop_loss_pct", 50.0)
        if (!stopLossPct.isFinite() || stopLossPct <= 0.0 || stopLossPct > maxSl)
            return "RECUSADO: stop loss deve estar entre 0 e $maxSl%."
        val maxTp = settings.num("max_take_profit_pct", 500.0)
        if (!takeProfitPct.isFinite() || takeProfitPct <= 0.0 || takeProfitPct > maxTp)
            return "RECUSADO: take profit deve estar entre 0 e $maxTp%."
        if (marginPositions(force = true).none { it.id == positionId })
            return "RECUSADO: a posição Forex $positionId não aparece aberta na carteira ativa; nenhum ajuste foi enviado."
        val ack = manualExecution.adjustForexStops(positionId, stopLossPct, takeProfitPct)
        cachedForexPositions = null
        return if (ack.accepted)
            "A IQ Option confirmou a solicitação de ajuste da posição Forex $positionId (status ${ack.status}). Revise a posição na plataforma; o protocolo não confirma o P/L final."
        else if (ack.status == null)
            "A alteração da posição Forex $positionId não foi confirmada pela IQ Option; consulte positions antes de tentar novamente."
        else
            "A IQ Option recusou alterar a posição Forex $positionId (status ${ack.status})."
    }

    override fun refreshMs(): Long = when {
        tracked.values.any { it.result == null } -> 1_000
        cachedForexPositions != null -> forexMonitorIntervalMs
        else -> 4_000
    }

    // ------------------------------------------------------------------ ações de negócio
    private fun money(b: IqProtocol.Balance) = String.format(Locale.US, "%.2f %s", b.amount, b.currency).trim()

    private suspend fun switchAccount(target: String): String = accountOrderCoordinator.runReserved {
        switchAccountReserved(target)
    }

    private suspend fun switchAccountReserved(target: String): String {
        ensureConnected()
        val current = accountMessageFlow.orderBalance()
            ?: return "RECUSADO: o perfil autenticado não confirmou uma carteira; sincronize a conta antes de selecionar outra."
        val wantReal = when (target.lowercase()) {
            "real" -> true
            "demo", "practice" -> false
            else -> throw IllegalArgumentException("Conta desconhecida. Use real ou practice.")
        }
        val b = balances.values.filter { if (wantReal) it.isReal else it.isPractice }.minByOrNull { it.id }
            ?: return "Esta conta não tem uma carteira ${if (wantReal) "REAL" else "DEMO"} disponível no perfil atual."
        if (current.id == b.id)
            return "A carteira ${b.label} já está selecionada nesta sessão; as consultas e operações usam explicitamente a carteira do perfil."
        val selectionGeneration = generation.get()
        if (!accountMessageFlow.selectKnownBalanceLocally(b.id, selectionGeneration))
            return "RECUSADO: a carteira solicitada não consta no perfil autenticado desta sessão."
        cachedForexPositions = null
        cachedForexPositionsAt = 0L
        _stateChanges.tryEmit(Unit)
        val targetType = if (b.isReal) "REAL" else "DEMO"
        debug.recordEvent("iqoption", "account-switch", "locally selected target=$targetType; requests route by profile balance id")
        _stateChanges.tryEmit(Unit)
        return "Carteira ${b.label} selecionada no app. Consultas e operações enviarão explicitamente a carteira presente no perfil. Isso não altera nem confirma a carteira padrão no servidor; a permissão separada para operar REAL continua obrigatória."
    }

    private suspend fun syncAccount(): String = accountOrderCoordinator.runReserved {
        val credentials = creds ?: throw IllegalStateException("Faça login novamente para sincronizar a carteira com o servidor.")
        debug.recordEvent("iqoption", "account-sync", "explicit account resynchronization started")
        try {
            mutex.withLock { connectLocked(credentials["email"].orEmpty(), credentials["password"].orEmpty()) }
            val confirmed = activeBalance() ?: throw IllegalStateException("O novo socket não confirmou uma carteira ativa.")
            resetMarketCircuit()
            refreshAssetsInBackground()
            _stateChanges.tryEmit(Unit)
            debug.recordEvent("iqoption", "account-sync", "server explicitly confirmed target=${if (confirmed.isReal) "REAL" else "DEMO"}")
            "Sincronização concluída: o servidor confirmou a carteira ${confirmed.label} (${money(confirmed)})."
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && t !is TimeoutCancellationException) throw t
            val reason = "Não foi possível confirmar a carteira após reconectar: ${friendly(t)}. Nenhuma ordem será liberada; tente sincronizar novamente."
            accountMessageFlow.failReconciliation(reason)
            debug.recordFailure("iqoption", "account-sync", t, IqProtocol.WS_URL, "GET")
            _stateChanges.tryEmit(Unit)
            throw IllegalStateException(reason)
        }
    }

    private suspend fun candlesText(asset: String, tf: String?, count: Int?): String {
        val all = loadAllAssets()
        val a = IqProtocol.resolveAsset(all, asset) ?: return "Ativo '$asset' não encontrado." + similar(asset)
        if (a.id <= 0) return "A IQ Option não forneceu um active_id válido para ${a.ticker}; candles indisponíveis por este protocolo."
        val size = IqProtocol.timeframeSeconds(tf ?: "60")
            ?: return "timeframe inválido. Use segundos (60=M1, 120=M2, 300=M5, 600=M10) ou 1m/2m/5m/10m."
        val n = (count ?: 30).coerceIn(1, 200)
        val id = reqId()
        val root = request(id, IqProtocol.getCandlesMessage(id, a.id, size, n, serverNowSec()), "candles", timeoutMs = 20_000)
        val list = IqProtocol.parseCandles(root["msg"] as? JsonObject ?: JsonObject(emptyMap())).sortedBy { it.from }
        if (list.isEmpty()) return "A IQ Option não devolveu candles para ${a.ticker} (${size}s)."
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            appendLine("Candles ${a.ticker} · ${size}s · ${list.size} · horário UTC · open high low close volume")
            list.forEach { c -> appendLine("${fmt.format(Date(c.from * 1000))} ${c.open} ${c.max} ${c.min} ${c.close} ${c.volume}") }
        }.trim()
    }


    // ------------------------------------------------------------------ proteção diária de operações binárias
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun loadRisk(): RiskState {
        val saved = IqTradeRisk.decode(kv?.get("iq_risk")) ?: RiskState()
        return if (saved.day == today()) saved else RiskState(day = today(), consecLosses = saved.consecLosses, pausedUntil = saved.pausedUntil)
    }
    private fun saveRisk(state: RiskState) { kv?.put("iq_risk", IqTradeRisk.encode(state)); memRisk = state }
    @Volatile private var memRisk: RiskState? = null
    private fun risk(): RiskState = if (kv != null) loadRisk() else (memRisk?.takeIf { it.day == today() } ?: RiskState(day = today()))

    private fun settle(trade: Tracked) = synchronized(this) {
        val pnl = IqTradeRisk.netPnl(trade.result, trade.amount, trade.profit)
        val result = resultLabel(trade.result)
        val old = risk()
        val updated = old.copy(
            dayPnl = old.dayPnl + pnl,
            dayTrades = old.dayTrades + 1,
            dayWins = old.dayWins + if (result == "WIN") 1 else 0,
            consecLosses = when (result) { "LOSS" -> old.consecLosses + 1; "WIN" -> 0; else -> old.consecLosses },
        )
        saveRisk(updated)
    }

    /** Salvaguardas diárias para novas opções abertas pelo agente. */
    private fun riskCheck(settings: ConnectorSettings?): String? {
        val state = risk()
        val limits = IqRiskLimits(
            dailyStopLoss = settings?.num("daily_stop_loss", 10.0) ?: 10.0,
            dailyTakeProfit = settings?.num("daily_take_profit", 10.0) ?: 10.0,
            maxConsecutiveLosses = (settings?.num("max_consec_losses", 3.0) ?: 3.0).toInt(),
            cooldownMillis = ((settings?.num("cooldown_min", 30.0) ?: 30.0) * 60_000).toLong(),
        )
        val decision = IqRiskPolicy.evaluate(state, System.currentTimeMillis(), limits)
        if (decision.updatedState != state) saveRisk(decision.updatedState)
        val until = decision.pauseUntilMillis?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
        return when (decision.block) {
            IqRiskBlock.PAUSED -> "RECUSADO (proteção): agente em pausa até $until após perdas seguidas."
            IqRiskBlock.DAILY_STOP_LOSS -> "RECUSADO (stop loss diário): resultado ${String.format(Locale.US, "%.2f", state.dayPnl)} atingiu −${limits.dailyStopLoss}."
            IqRiskBlock.DAILY_TAKE_PROFIT -> "RECUSADO (meta diária): +${String.format(Locale.US, "%.2f", state.dayPnl)} atingiu ${limits.dailyTakeProfit}."
            IqRiskBlock.CONSECUTIVE_LOSSES -> "RECUSADO (proteção): ${decision.consecutiveLossesAtBlock} perdas seguidas; pausa até $until."
            null -> null
        }
    }

    private fun openCount(): Int = tracked.values.count { it.result == null && it.expSec + 120 > serverNowSec() }

    private suspend fun openOption(
        asset: String, direction: String, amount: Double, minutes: Int, settings: ConnectorSettings?, byAgent: Boolean,
    ): String = accountOrderCoordinator.runReserved {
        openOptionReserved(asset, direction, amount, minutes, settings, byAgent)
    }

    private suspend fun openOptionReserved(
        asset: String, direction: String, amount: Double, minutes: Int, settings: ConnectorSettings?, byAgent: Boolean,
    ): String {
        ensureConnected()
        val side = direction.trim().lowercase()
        if (side != "call" && side != "put") return "direction deve ser call ou put."
        if (!amount.isFinite() || amount <= 0.0) return "amount deve ser finito e maior que zero."
        if (minutes !in 1..60) return "minutes deve estar entre 1 e 60."
        val account = accountMessageFlow.orderBalance() ?: return "RECUSADO: nenhuma carteira selecionada consta no perfil autenticado; sincronize a conta antes de operar."
        if (byAgent && settings != null) {
            if (account.isReal && !settings.bool("allow_real_agent"))
                return "RECUSADO: a carteira selecionada é REAL e o usuário não liberou o agente para operar nela. Selecione DEMO no conector ou libere a carteira REAL nas configurações."
            val maxAmount = settings.num("max_amount", 1.0)
            if (amount > maxAmount) return "RECUSADO: valor $amount excede o máximo por opção ($maxAmount)."
            val maxOpen = settings.num("max_open", 3.0).toInt().coerceAtLeast(1)
            if (openCount() >= maxOpen) return "RECUSADO: já existem ${openCount()} opções abertas (máximo $maxOpen). Aguarde fechar."
            riskCheck(settings)?.let { return it }
        }
        val optionAssets = loadAssets()
        val active = IqProtocol.resolveAsset(optionAssets, asset) ?: run {
            val forex = runCatching { loadAllAssets() }.getOrDefault(emptyList()).firstOrNull { it.kind == "forex" && it.ticker.equals(asset.trim(), true) }
            return if (forex != null) "${forex.ticker} é Forex; use open_forex." else "Ativo '$asset' não encontrado. Use open_assets."
        }
        if (active.kind !in setOf("turbo", "binary")) return "Somente opções Turbo/binárias podem ser abertas por open_option."
        if (!active.open) return "O ativo ${active.ticker} está fechado/suspenso agora."
        val synchronizedNow = synchronizedServerNowSec()
            ?: return "RECUSADO: não há timeSync recente da IQ Option. Não vou calcular vencimento pelo relógio local; reconecte e tente novamente."
        val requestedExpiration = IqProtocol.expirationFor(synchronizedNow, minutes)
        subscribeQuotes(active.id)
        val ack = manualExecution.openOption(IqOptionOrder(account.id, active.id, side, requestedExpiration, amount, IqProtocol.isTurbo(minutes)))
        val optionId = ack.optionId ?: run { err("opção recusada: ${ack.error}"); return "RECUSADA pela IQ Option: ${ack.error}" }
        val prior = tracked[optionId]
        val actualExpiry = prior?.takeIf { it.expiryConfirmed }?.expSec ?: ack.expSec?.takeIf { it > 0L }
        val expiry = actualExpiry ?: requestedExpiration
        val expiryConfirmed = prior?.expiryConfirmed == true || ack.expSec?.let { it > 0L } == true
        tracked[optionId] = (prior ?: Tracked(optionId, active.ticker, side, amount, expiry, account.isPractice, account.currency)).copy(
            asset = active.ticker, direction = side, amount = amount, expSec = expiry,
            demo = account.isPractice, currency = account.currency, activeId = active.id,
            entry = prior?.entry ?: ack.openPrice ?: quotes[active.id], byAgent = byAgent,
            expiryConfirmed = expiryConfirmed,
        )
        val nowAfterAck = synchronizedServerNowSec() ?: synchronizedNow
        val waitSeconds = IqTradeWaitDirective.secondsUntilExpiry(expiry, nowAfterAck)
        val expiryKind = if (expiryConfirmed) "confirmado pela IQ Option" else "solicitado com relógio sincronizado; aguardando confirmação do servidor"
        val output = "Opção ABERTA na conta ${account.label}: ${active.ticker} ${side.uppercase()} ${money(account.copy(amount = amount))} · vencimento ${expiryUtc(expiry)} ($expiryKind; ${minutes} min; id $optionId). O resultado final vem do evento de fechamento da IQ Option; não farei consultas repetidas."
        return if (byAgent && tracked[optionId]?.result == null) IqTradeWaitDirective.append(output, optionId, waitSeconds) else output
    }

    private fun positionsText(): String {
        val now = serverNowSec()
        val open = tracked.values.filter { it.result == null }.sortedBy { it.expSec }
        val closed = tracked.values.filter { it.result != null }.sortedByDescending { it.expSec }.take(10)
        return buildString {
            appendLine("== Opções em execução (${open.size}) ==")
            if (open.isEmpty()) appendLine("nenhuma")
            open.forEach { appendLine("${it.id} ${it.asset} ${it.direction.uppercase()} ${it.amount} ${if (it.demo) "DEMO" else "REAL"} · entrada ${fmtPx(it.entry)} · agora ${fmtPx(quotes[it.activeId])} · ${IqTradeRisk.favorDiff(it.asset, it.direction, it.entry, quotes[it.activeId])} · ${liveStatus(it, now)}") }
            appendLine("== Últimas opções fechadas ==")
            if (closed.isEmpty()) appendLine("nenhuma")
            closed.forEach { appendLine("${it.id} ${it.asset} ${it.direction.uppercase()} ${it.amount} ${if (it.demo) "DEMO" else "REAL"} → ${resultLabel(it.result)}" + (it.profit?.let { p -> " (" + String.format(Locale.US, "%.2f", p) + ")" } ?: "")) }
            append("Os resultados finais das opções vêm dos eventos recebidos da IQ Option nesta sessão.")
        }.trim()
    }

    private fun resultLabel(r: String?): String = when (r?.lowercase()) {
        "win" -> "WIN"
        "loose", "loss", "lose" -> "LOSS"
        "equal" -> "EMPATE"
        null -> "—"
        else -> r.uppercase()
    }

    private fun tradeResultText(idRaw: String?): String {
        val id = idRaw?.toLongOrNull()?.takeIf { it > 0L } ?: return "id inválido."
        val trade = tracked[id] ?: return "A opção $id não está no acompanhamento desta sessão. Use positions; o protocolo disponível não confirma histórico de Forex já fechado."
        val result = trade.result
        return if (result == null) {
            val now = synchronizedServerNowSec()
            val remaining = now?.let { (trade.expSec - it).coerceAtLeast(0L) }
            val expiryText = if (trade.expiryConfirmed) "vencimento confirmado" else "vencimento solicitado, sem confirmação explícita do evento"
            val output = if (remaining == null) {
                "Não há timeSync recente da IQ Option para calcular com segurança o prazo restante da opção $id. O resultado virá do evento de fechamento; não vou consultar usando horário local."
            } else if (remaining > 0L) {
                "Opção $id ${trade.asset} ${trade.direction.uppercase()} ainda em execução; $expiryText para ${expiryUtc(trade.expSec)} · faltam ~${remaining}s. O resultado vem do evento de fechamento; nenhuma consulta será repetida antes do prazo."
            } else {
                "O vencimento de ${expiryUtc(trade.expSec)} da opção $id já passou, mas o evento final ainda não chegou nesta sessão. O protocolo usado não oferece uma consulta de histórico confirmada; não vou fazer polling. Verifique positions mais tarde."
            }
            if (trade.byAgent && remaining != null && remaining > 0L)
                IqTradeWaitDirective.append(output, id, IqTradeWaitDirective.secondsUntilExpiry(trade.expSec, now))
            else output
        } else {
            "Resultado final da opção $id ${trade.asset} ${trade.direction.uppercase()}: ${resultLabel(result)}" +
                (trade.profit?.let { " · valor informado pela IQ Option ${String.format(Locale.US, "%.2f", it)}" } ?: ".")
        }
    }

    // ------------------------------------------------------------------ UI declarativa
    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> {
        forexMonitorIntervalMs = IqForexMonitoringInterval.milliseconds(settings.num("forex_monitor_interval_sec", 30.0))
        val blocks = mutableListOf<UiBlock>()
        if (activeBalanceSelection.switchFailed()) {
            blocks += UiBlock.Header("IQ Option", "A carteira ativa não foi confirmada", "BLOQUEADA", Tone.BAD)
            blocks += UiBlock.Note(activeBalanceSelection.failureMessage() ?: "Estado da carteira desconhecido. Nenhuma ordem será liberada.", Tone.BAD)
            blocks += UiBlock.Button("sync-account", "Sincronizar conta com o servidor")
            return blocks
        }
        val connectError = runCatching { ensureConnected() }.exceptionOrNull()
        val acc = activeBalance()
        if (connectError == null && acc == null && activeBalanceSelection.switchPending()) {
            blocks += UiBlock.Header("IQ Option", "Troca de conta em andamento", "TROCANDO", Tone.WARN)
            blocks += UiBlock.Note("Aguardando confirmação da carteira solicitada. Nenhuma nova ordem será liberada até confirmar o ID correto.", Tone.WARN)
            return blocks
        }
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
        pruneQuotes()
        open.forEach { if (it.activeId != 0) subscribeQuotes(it.activeId) }
        blocks += UiBlock.Table(
            "Operações em execução (ao vivo)", listOf("Ativo", "Dir.", "Valor", "Situação", "Pips", "Entrada → Agora"),
            open.map { listOf(it.asset, it.direction.uppercase(), String.format(Locale.US, "%.2f", it.amount),
                liveStatus(it, now), IqTradeRisk.favorDiff(it.asset, it.direction, it.entry, quotes[it.activeId]), "${fmtPx(it.entry)} → ${fmtPx(quotes[it.activeId])}") },
            empty = "Nenhuma operação em execução.",
        )
        blocks += UiBlock.Table(
            "Últimas operações", listOf("Ativo", "Dir.", "Valor", "Resultado"),
            closed.sortedByDescending { it.expSec }.take(8).map { listOf(it.asset, it.direction.uppercase(), String.format(Locale.US, "%.2f", it.amount),
                resultLabel(it.result) + (it.profit?.let { p -> " " + String.format(Locale.US, "%.2f", p) } ?: "")) },
            empty = "Sem operações fechadas nesta sessão.",
        )
        val riskState = risk()
        val riskStats = arrayListOf(
            Stat("Resultado diário", String.format(Locale.US, "%+.2f", riskState.dayPnl), if (riskState.dayPnl > 0) Tone.GOOD else if (riskState.dayPnl < 0) Tone.BAD else Tone.NEUTRAL),
            Stat("Perdas seguidas", riskState.consecLosses.toString(), if (riskState.consecLosses >= 2) Tone.WARN else Tone.NEUTRAL),
        )
        if (riskState.pausedUntil > System.currentTimeMillis()) riskStats += Stat("Pausa até", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(riskState.pausedUntil)), Tone.WARN)
        blocks += UiBlock.Stats(riskStats)
        val forex = runCatching { withTimeout(4_000) { marginPositions() } }.getOrNull()
        if (forex == null) blocks += UiBlock.Note("Posições Forex indisponíveis no momento.", Tone.WARN)
        else blocks += UiBlock.Table("Posições Forex abertas", listOf("Ativo", "Lado", "Valor", "P/L"),
            forex.map { listOf(it.instrument, it.side.uppercase(), "${String.format(Locale.US, "%.2f", it.amount)} ×${it.leverage}", it.pnl?.let { p -> String.format(Locale.US, "%.2f", p) } ?: it.status) },
            empty = "Nenhuma posição Forex aberta.")
        // nunca espera a lista completa: usa o que já tem e atualiza em segundo plano
        refreshAssetsInBackground()
        val all = allAssets.ifEmpty { assets }.takeIf { it.isNotEmpty() }
        if (all == null) blocks += UiBlock.Note("Carregando a lista de ativos…", Tone.NEUTRAL)
        if (all != null) {
            val openA = all.filter { it.open }
            blocks += UiBlock.Stats(listOf("turbo", "binary", "forex").mapNotNull { k ->
                val n = openA.count { it.kind == k }; if (all.none { it.kind == k }) null else Stat(kindLabel(k), n.toString())
            }.take(6))
            blocks += UiBlock.Table("Ativos abertos (${openA.size})", listOf("Ativo", "Tipo"),
                openA.sortedWith(compareBy({ listOf("turbo", "binary", "forex").indexOf(it.kind) }, { it.ticker })).map { listOf(it.ticker, kindLabel(it.kind)) },
                empty = "Nenhum ativo aberto agora.")
        }
        blocks += UiBlock.Note("Resultados de opções vêm de eventos do servidor nesta sessão; Forex mostra o P/L atual retornado pela corretora. Operar envolve risco de perda.", Tone.NEUTRAL)
        return blocks
    }

    private fun maskEmail(e: String): String {
        val at = e.indexOf('@')
        return if (at <= 1) e else e.first() + "***" + e.substring(at)
    }

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> = try {
        when (actionId) {
            "account" -> when (args["value"]) {
                "real" -> Result.success(switchAccount("real"))
                "practice" -> Result.success(switchAccount("practice"))
                else -> Result.failure(IllegalArgumentException("Valor de conta inválido. Use real ou practice."))
            }
            "sync-account" -> Result.success(syncAccount())
            else -> Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException && t !is TimeoutCancellationException) throw t
        err("ação $actionId falhou: ${t.message}")
        Result.failure(IllegalStateException(friendly(t)))
    }

    // ------------------------------------------------------------------ portão de validação
    override fun orderIntents(tool: String, args: Map<String, String>): List<dev.cueopen.app.uiconnector.OrderIntent> {
        if (tool != "open_option" && tool != "open_forex") return emptyList()
        val asset = args["asset"].orEmpty().trim()
        val sideInput = if (tool == "open_option") args["direction"] else args["side"]
        val side = dev.cueopen.app.trading.Sides.parse(sideInput)
        if (asset.isEmpty() || side == null) return emptyList()
        return listOf(dev.cueopen.app.uiconnector.OrderIntent(asset, side))
    }

    // ------------------------------------------------------------------ ferramentas do agente
    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String = when (name) {
        "open_assets" -> {
            val filter = args["filter"].orEmpty().trim().uppercase()
            val kind = args["kind"].orEmpty().ifBlank { "all" }.lowercase()
            val list = loadAllAssets().filter { asset ->
                asset.open && (filter.isEmpty() || asset.ticker.uppercase().contains(filter)) &&
                    (kind == "all" || asset.kind == kind || (kind == "options" && asset.kind in setOf("turbo", "binary")))
            }
            if (list.isEmpty()) "Nenhum ativo aberto" + (if (filter.isNotEmpty()) " para '$filter'" else "") + (if (kind != "all") " ($kind)" else ".")
            else list.groupBy { it.kind }.entries.joinToString("\n") { (assetKind, rows) ->
                "${kindLabel(assetKind)} (${rows.size}): " + rows.joinToString(", ") { it.ticker }
            } + marketRetryHint()
        }
        "get_candles" -> candlesText(args["asset"].orEmpty(), args["timeframe"], args["count"]?.toIntOrNull())
        "open_option" -> openOption(
            args["asset"].orEmpty(), args["direction"].orEmpty(),
            args["amount"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0,
            args["minutes"]?.toIntOrNull() ?: 1, settings, byAgent = true,
        )
        "open_forex" -> openForex(args, settings)
        "close_position" -> closeForex(args["id"], settings)
        "adjust_forex_stops" -> adjustForexStops(args, settings)
        "positions" -> {
            ensureConnected()
            val forex = marginPositions(force = true)
            val base = positionsText()
            if (forex.isEmpty()) base else base + "\n== Forex aberto (P/L atual) ==\n" + forex.joinToString("\n") {
                "${it.id} ${it.instrument} ${it.side.uppercase()} ${it.amount} ×${it.leverage} · ${it.status} · P/L ${it.pnl?.let { p -> String.format(Locale.US, "%.2f", p) } ?: "—"}"
            }
        }
        "get_trade_result" -> tradeResultText(args["id"])
        else -> "Ferramenta desconhecida: $name"
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
