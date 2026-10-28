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
import dev.cueopen.app.uiconnector.custom.TechnicalSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
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
class IqOptionConnector(private val kv: KvStore? = null) : UiConnector {

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
            SettingSpec(
                "short_max_minutes", "Duração máxima de operação CURTA (min)",
                "Até este tempo a operação é curta: o agente pode comprar (call) e vender (put). Acima disso é LONGA: só compra (call).",
                SettingType.NUMBER, "5",
            ),
            SettingSpec("mm_enabled", "Gerenciamento Masaniello (agente)",
                "Ligado: o VALOR de cada operação do agente é calculado pelo Masaniello (ciclo de N operações buscando K acertos) e as operações são uma por vez. O ciclo nunca perde mais que a banca do ciclo.",
                SettingType.SWITCH, "true", risky = true),
            SettingSpec("mm_bank", "Masaniello: banca do ciclo", "Valor máximo que um ciclo pode perder. Ao acertar K de N, o ciclo fecha no lucro-alvo.", SettingType.NUMBER, "10"),
            SettingSpec("mm_trades", "Masaniello: operações por ciclo (N)", "Quantidade de operações do ciclo.", SettingType.NUMBER, "10"),
            SettingSpec("mm_wins", "Masaniello: acertos esperados (K)", "Acertos necessários dentro das N. Use uma taxa abaixo da sua taxa real de acerto (ex.: 5 de 10).", SettingType.NUMBER, "5"),
            SettingSpec("payout_pct", "Payout médio (%)", "Retorno da corretora em caso de acerto (ex.: 85). Usado no Masaniello e no backtest.", SettingType.NUMBER, "85"),
            SettingSpec("daily_stop_loss", "Stop loss diário", "Perda no dia (moeda da conta) que pausa o agente até amanhã. 0 = sem limite.", SettingType.NUMBER, "10"),
            SettingSpec("daily_take_profit", "Meta diária (stop gain)", "Lucro no dia que encerra as operações do agente até amanhã. 0 = sem meta.", SettingType.NUMBER, "10"),
            SettingSpec("max_consec_losses", "Perdas seguidas para pausar", "Após N perdas seguidas o agente pausa pelo tempo abaixo.", SettingType.NUMBER, "3"),
            SettingSpec("cooldown_min", "Pausa após perdas seguidas (min)", "Tempo de pausa depois de atingir as perdas seguidas.", SettingType.NUMBER, "30"),
            SettingSpec("require_confluence", "Exigir confluência dos indicadores",
                "Ligado: CALL só se o resumo técnico (35 indicadores, velas de 1 min) for de compra; PUT só se for de venda.", SettingType.SWITCH, "true"),
            SettingSpec("confluence_strong", "Confluência só com sinal FORTE", "Ligado: exige 'Forte compra'/'Forte venda' (menos operações, mais seletivas).", SettingType.SWITCH, "false"),
            SettingSpec(
                "max_entry_delay_sec", "Janela de entrada no início da vela (s)",
                "O agente só abre operação nos primeiros N segundos da vela de 1 min (assim a operação cobre a vela analisada, do começo). Fora disso a ordem é recusada e ele espera a próxima vela. 0 = desliga.",
                SettingType.NUMBER, "10",
            ),
            SettingSpec(
                "entry_against_candle", "Entrada contra o candle (recomendado)",
                "Ligado: antes de cada compra o app confere o candle de 1 min. CALL (compra) só entra com o preço ABAIXO da abertura; PUT (venda) só ACIMA da abertura. Evita comprar no topo e vender no fundo.",
                SettingType.SWITCH, "true",
            ),
            SettingSpec(
                "allow_margin_agent", "Agente pode operar Forex/CFD/Cripto (margem)",
                "Desligado: o agente só opera opções. Ligado: ele pode abrir posições alavancadas em Forex, CFD e cripto, sempre com stop loss e take profit.",
                SettingType.SWITCH, "false", risky = true,
            ),
            SettingSpec("max_margin_amount", "Valor máximo por posição de margem (agente)", "Teto do valor investido em cada posição Forex/CFD/cripto aberta pelo agente.", SettingType.NUMBER, "1"),
            SettingSpec("max_leverage", "Alavancagem máxima (agente)", "Maior alavancagem que o agente pode usar em Forex/CFD/cripto.", SettingType.NUMBER, "10"),
            SettingSpec("max_stop_loss_pct", "Stop loss máximo (%)", "Perda máxima permitida por posição de margem, em % do valor investido.", SettingType.NUMBER, "50"),
        ),
        tools = listOf(
            ToolSpec("balance", "Saldo das contas DEMO e REAL e qual está ativa."),
            ToolSpec("set_account", "Troca a conta ativa (demo ou real). A conta real só é aceita se o usuário liberou o agente para ela.",
                listOf(ParamSpec("account", "demo ou real", allowed = listOf("demo", "real"))), risky = true),
            ToolSpec("open_assets", "Lista TODOS os ativos abertos agora, por tipo: turbo e binária (inclui OTC), digital, forex, CFD (ações, índices, commodities) e cripto.",
                listOf(ParamSpec("filter", "Filtro opcional de texto (ex.: EUR)", required = false),
                    ParamSpec("kind", "Tipo: all, options (turbo+binária), digital, forex, cfd, crypto. Padrão all.", required = false,
                        allowed = listOf("all", "options", "turbo", "binary", "digital", "forex", "cfd", "crypto")))),
            ToolSpec("get_candles", "Velas OHLC reais de um ativo (abertura, máxima, mínima, fechamento, volume).", listOf(
                ParamSpec("asset", "Ticker do ativo, ex.: EURUSD"),
                ParamSpec("timeframe", "Tamanho da vela em segundos (60=1min, 300=5min…) ou 1m/5m/1h. Padrão 60.", required = false),
                ParamSpec("count", "Quantidade de velas (1–200). Padrão 30.", "integer", required = false),
            )),
            ToolSpec("mm_status", "Gerenciamento e regras: ciclo Masaniello (acertos/operações restantes, banca, próxima entrada, lucro-alvo), resultado do dia, perdas seguidas e se o agente está pausado."),
            ToolSpec("backtest", "Backtest com velas REAIS do ativo: aplica a suíte técnica vela a vela e simula operações de 1 vela, com taxa de acerto, ponto de equilíbrio pelo payout, resultado com valor fixo e com Masaniello. Use para escolher ativo/tempo gráfico ANTES de operar.", listOf(
                ParamSpec("asset", "Ticker do ativo"),
                ParamSpec("timeframe", "Tempo gráfico (60, 300… ou 1m/5m). Padrão 60.", required = false),
                ParamSpec("count", "Velas (300–1000). Padrão 600.", "integer", required = false),
                ParamSpec("strong", "true = só sinais fortes", "boolean", required = false),
            )),
            ToolSpec("candle_clock", "Relógio da vela pelo horário do SERVIDOR da IQ Option: quando a vela atual abriu, quantos segundos faltam para a próxima, se dá para entrar agora e quantos segundos dormir (sleep seconds) para acordar logo antes da próxima vela. Use SEMPRE antes de esperar/entrar.", listOf(
                ParamSpec("timeframe", "Tempo gráfico (60, 300, 900… s ou 1m/5m/15m). Padrão 60.", required = false),
            )),
            ToolSpec("indicators", "Análise técnica completa (padrão TradingView) sobre velas REAIS do ativo: mais de 30 indicadores (médias, RSI, MACD, Estocástico, ADX, Bollinger, Ichimoku, Supertrend, PSAR…) com sinal de compra/venda e o resumo. Calculado no aparelho, em milissegundos.", listOf(
                ParamSpec("asset", "Ticker do ativo, ex.: EURUSD-OTC"),
                ParamSpec("timeframe", "Tamanho da vela (60, 300, 900… segundos ou 1m/5m/15m/1h). Padrão 60.", required = false),
                ParamSpec("count", "Quantidade de velas (50–1000). Padrão 300.", "integer", required = false),
            )),
            ToolSpec("buy", "Abre uma operação (call = compra/sobe, put = venda/desce) na conta ativa, respeitando os limites do usuário. " +
                "EXIGE validação prévia com typesafe_jev_check (venue=iqoption, id=ticker, direction=call|put). " +
                "Curta (até o limite do usuário): call e put. Longa: somente call. " +
                "Entrada contra o candle de 1 min: call só abaixo da abertura, put só acima (senão RECUSADO; espere e tente de novo). " +
                "REGRAS DURAS: com o Masaniello ligado o VALOR é definido pelo gerenciamento (o amount informado é ignorado) e é uma operação por vez; " +
                "stop loss/meta diária, pausa após perdas seguidas e confluência dos indicadores são impostos pelo app. Consulte mm_status antes de operar.", listOf(
                ParamSpec("asset", "Ticker do ativo, ex.: EURUSD"),
                ParamSpec("direction", "call (compra/sobe) ou put (venda/desce)", allowed = listOf("call", "put")),
                ParamSpec("amount", "Valor da operação na moeda da conta", "number"),
                ParamSpec("minutes", "Expiração em minutos (1–60). Padrão 1.", "integer", required = false),
            ), risky = true, requiresSignal = true),
            ToolSpec("open_position", "Abre uma posição alavancada a mercado em FOREX, CFD ou CRIPTO (buy = compra, sell = venda), com stop loss e take profit obrigatórios em %. " +
                "Só funciona se o usuário liberou margem para o agente. EXIGE validação prévia com typesafe_jev_check (venue=iqoption, id=ativo, direction=buy|sell).", listOf(
                ParamSpec("asset", "Ativo de margem (ex.: EURUSD, XAUUSD, AAPL, BTCUSD) — veja open_assets kind=forex/cfd/crypto"),
                ParamSpec("side", "buy (compra) ou sell (venda)", allowed = listOf("buy", "sell")),
                ParamSpec("amount", "Valor investido (margem) na moeda da conta", "number"),
                ParamSpec("leverage", "Alavancagem (ex.: 10). Limitada pelo usuário.", "integer"),
                ParamSpec("stop_loss_pct", "Stop loss em % do valor investido (ex.: 20)", "number"),
                ParamSpec("take_profit_pct", "Take profit em % do valor investido (ex.: 30)", "number"),
            ), risky = true, requiresSignal = true),
            ToolSpec("close_position", "Fecha uma posição de margem (Forex/CFD/cripto) pelo id.", listOf(ParamSpec("id", "Id da posição", "integer")), risky = true),
            ToolSpec("positions", "Operações em execução com resultado AO VIVO (ganhando/perdendo agora), as últimas fechadas e as posições de Forex/CFD/cripto."),
        ),
    )

    // ------------------------------------------------------------------ estado
    private class Waiter(val reqId: String?, val names: Set<String>, val deferred: CompletableDeferred<JsonObject>)

    private data class Tracked(
        val id: Long, val asset: String, val direction: String, val amount: Double,
        val expSec: Long, val demo: Boolean, val currency: String,
        val result: String? = null, val profit: Double? = null,
        val activeId: Int = 0, val entry: Double? = null,
        val byAgent: Boolean = false, val mm: Boolean = false, val odds: Double = 1.85, val settled: Boolean = false,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val counter = AtomicLong(1)
    private val generation = AtomicLong(0)
    private val waiters = CopyOnWriteArrayList<Waiter>()
    private val balances = ConcurrentHashMap<Long, IqProtocol.Balance>()
    private val tracked = ConcurrentHashMap<Long, Tracked>()
    private val quotes = ConcurrentHashMap<Int, Double>()
    private val subscribed = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val marginIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    @Volatile private var allAssets: List<IqProtocol.Asset> = emptyList()
    @Volatile private var allAssetsAt = 0L
    /** Tarefas de fundo (carregar ativos) — a tela nunca espera por elas. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loadingAll = false

    /** Atualiza a lista completa de ativos em segundo plano (sem travar a tela). */
    private fun refreshAssetsInBackground() {
        if (loadingAll || (allAssets.isNotEmpty() && System.currentTimeMillis() - allAssetsAt < 5 * 60_000)) return
        loadingAll = true
        bg.launch { try { runCatching { loadAllAssets(force = true) } } finally { loadingAll = false } }
    }
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
        // carrega TODOS os ativos uma vez, já no login, e deixa em cache (o agente não espera por isso depois)
        allAssetsAt = 0L
        refreshAssetsInBackground()
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
        subscribed.clear()
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
        subscribed.clear()
    }

    override suspend fun logout() {
        closeSocket()
        ssid = null
        creds = null
        balances.clear()
        tracked.clear()
        quotes.clear()
        marginIds.clear()
        assets = emptyList()
        allAssets = emptyList()
        activeBalanceId = null
    }

    private suspend fun ensureConnected() {
        if (authenticated && ws != null) return
        val c = creds ?: error("Sessão da IQ Option não iniciada. Faça login no conector.")
        mutex.withLock {
            if (!(authenticated && ws != null)) connectLocked(c["email"].orEmpty(), c["password"].orEmpty())
        }
        // reconectou: volta a assinar as cotações das operações em aberto (resultado ao vivo)
        tracked.values.filter { it.result == null && it.activeId != 0 }.forEach { subscribeQuotes(it.activeId) }
    }

    private fun subscribeQuotes(activeId: Int) {
        if (activeId == 0 || !subscribed.add(activeId)) return
        if (ws?.send(IqProtocol.subscribeQuotesMessage(activeId)) != true) subscribed.remove(activeId)
    }

    /** Larga a assinatura de cotações quando não há mais operação aberta naquele ativo. */
    private fun pruneQuotes() {
        val needed = tracked.values.filter { it.result == null && it.expSec + 120 > serverNowSec() }.map { it.activeId }.toSet()
        subscribed.filter { it !in needed }.forEach { id -> subscribed.remove(id); runCatching { ws?.send(IqProtocol.unsubscribeQuotesMessage(id)) }; quotes.remove(id) }
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
                if (c != null && t != null) {
                    val done = t.copy(result = c.result, profit = c.profit)
                    tracked[c.optionId] = done
                    if (done.byAgent && !done.settled) { settle(done); tracked[c.optionId] = done.copy(settled = true) }
                }
            }
            "candle-generated" -> {
                val q = if (msg != null) IqProtocol.parseQuote(msg) else null
                if (q != null) {
                    quotes[q.activeId] = q.price
                    // operação sem preço de entrada confirmado: usa a 1ª cotação após a abertura
                    tracked.values.filter { it.activeId == q.activeId && it.entry == null && it.result == null }
                        .forEach { tracked[it.id] = it.copy(entry = q.price) }
                }
            }
            "socket-option-opened", "option-opened" -> {
                // operações abertas fora do app (web/celular) também aparecem e são acompanhadas ao vivo
                val o = IqProtocol.parseOptionOpened(root)
                if (o != null && !tracked.containsKey(o.optionId)) {
                    val acc = activeBalance()
                    val ticker = (allAssets + assets).firstOrNull { it.id == o.activeId }?.ticker ?: "#${o.activeId}"
                    tracked[o.optionId] = Tracked(o.optionId, ticker, o.direction, o.amount, o.expSec, acc?.isPractice ?: true, acc?.currency ?: "",
                        activeId = o.activeId, entry = o.openPrice)
                    subscribeQuotes(o.activeId)
                }
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
        if (!force && assets.isNotEmpty()) {
            // cache: responde na hora; se estiver velho (> 5 min), atualiza em segundo plano
            if (System.currentTimeMillis() - assetsAt > 5 * 60_000) refreshAssetsInBackground()
            return assets
        }
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

    /** Todos os mercados: opções (turbo/binária, inclui OTC) + digital + forex + CFD + cripto. Cada fonte é opcional. */
    private suspend fun loadAllAssets(force: Boolean = false): List<IqProtocol.Asset> {
        if (!force && allAssets.isNotEmpty()) {
            if (System.currentTimeMillis() - allAssetsAt > 5 * 60_000) refreshAssetsInBackground()
            return allAssets
        }
        val out = ArrayList(loadAssets(force))
        val now = serverNowSec()
        // as 4 fontes extras em PARALELO e com tempo curto: uma fonte lenta/indisponível não atrasa as outras
        val extra = kotlinx.coroutines.coroutineScope {
            val jobs = listOf("digital", "forex", "cfd", "crypto").map { type ->
                async {
                    runCatching {
                        val id = reqId()
                        if (type == "digital") {
                            val root = request(id, IqProtocol.underlyingListMessage(id), "underlying-list", timeoutMs = 6_000)
                            IqProtocol.parseUnderlying(root["msg"] as? JsonObject ?: JsonObject(emptyMap()), now)
                        } else {
                            val root = request(id, IqProtocol.instrumentsMessage(id, type), "instruments", timeoutMs = 6_000)
                            IqProtocol.parseInstruments(root["msg"] as? JsonObject ?: JsonObject(emptyMap()), type, now)
                        }
                    }.getOrElse { err("$type: ${it.message}"); emptyList() }
                }
            }
            jobs.awaitAll().flatten()
        }
        out += extra
        allAssets = out
        allAssetsAt = System.currentTimeMillis()
        return out
    }

    private fun kindLabel(k: String) = when (k) { "turbo" -> "Turbo"; "binary" -> "Binária"; "digital" -> "Digital"; "forex" -> "Forex"; "cfd" -> "CFD"; "crypto" -> "Cripto"; else -> k }

    private fun countdown(sec: Long) = "${sec / 60}:${String.format(Locale.US, "%02d", sec % 60)}"

    /** Situação ao vivo de uma opção aberta: tempo restante + ganhando/perdendo agora pela cotação real. */
    private fun liveStatus(t: Tracked, now: Long): String {
        val price = quotes[t.activeId]
        val outcome = IqProtocol.liveOutcome(t.direction, t.entry, price)
        val time = if (t.expSec > now) countdown(t.expSec - now) else "resultado…"
        val arrow = when (outcome) { "WIN" -> " ▲ ganhando"; "LOSS" -> " ▼ perdendo"; "EMPATE" -> " = empate"; else -> " · aguardando cotação" }
        return time + arrow
    }

    private fun fmtPx(v: Double?) = v?.let { java.math.BigDecimal(it).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } ?: "—"

    // ------------------------------------------------------------------ margem (Forex / CFD / cripto)
    private suspend fun marginPositions(): List<IqProtocol.MarginPos> {
        val acc = activeBalance() ?: return emptyList()
        val id = reqId()
        val root = request(id, IqProtocol.getPositionsMessage(id, acc.id), "positions", timeoutMs = 12_000)
        return IqProtocol.parsePositions(root["msg"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    private suspend fun openMargin(args: Map<String, String>, settings: ConnectorSettings): String {
        ensureConnected()
        if (!settings.bool("allow_margin_agent")) return "RECUSADO: o usuário não liberou o agente para operar Forex/CFD/cripto (configurações do conector IQ Option)."
        val acc = activeBalance() ?: return "Nenhuma conta ativa."
        if (acc.isReal && !settings.bool("allow_real_agent")) return "RECUSADO: a conta ativa é REAL e o agente não foi liberado para ela."
        val side = args["side"].orEmpty().lowercase()
        if (side != "buy" && side != "sell") return "side deve ser buy ou sell."
        val amount = args["amount"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val lev = args["leverage"]?.toDoubleOrNull()?.toInt() ?: 0
        val sl = args["stop_loss_pct"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val tp = args["take_profit_pct"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        if (amount <= 0) return "amount deve ser maior que zero."
        val maxAmount = settings.num("max_margin_amount", 1.0)
        if (amount > maxAmount) return "RECUSADO: valor $amount excede o máximo por posição ($maxAmount)."
        val maxLev = settings.num("max_leverage", 10.0).toInt().coerceAtLeast(1)
        if (lev < 1 || lev > maxLev) return "RECUSADO: alavancagem deve ser de 1 a $maxLev."
        val maxSl = settings.num("max_stop_loss_pct", 50.0)
        if (sl <= 0 || sl > maxSl) return "RECUSADO: stop loss obrigatório, entre 0 e $maxSl%."
        if (tp <= 0) return "RECUSADO: take profit obrigatório (> 0%)."
        val q = args["asset"].orEmpty()
        fun norm(x: String) = x.uppercase().replace("/", "").replace(" ", "").replace("_", "")
        val a = loadAllAssets().filter { it.instrumentId.isNotEmpty() }.let { l -> l.firstOrNull { norm(it.ticker) == norm(q) || norm(it.instrumentId) == norm(q) } }
            ?: return "Ativo de margem '$q' não encontrado. Use open_assets kind=forex/cfd/crypto."
        if (!a.open) return "O ativo ${a.ticker} está fechado agora."
        val id = reqId()
        val root = request(id, IqProtocol.placeMarginOrderMessage(id, acc.id, a.kind, a.instrumentId, side, amount, lev, sl, tp),
            "order-placed-temp", "result", timeoutMs = 15_000)
        val ack = IqProtocol.parseOrderAck(root)
        val oid = ack.optionId ?: run { err("margem recusada: ${ack.error}"); return "RECUSADA pela IQ Option: ${ack.error}" }
        marginIds += oid
        return "Ordem de ${kindLabel(a.kind)} ENVIADA na conta ${acc.label}: ${a.ticker} ${side.uppercase()} ${String.format(Locale.US, "%.2f", amount)} ×$lev · SL $sl% · TP $tp% (ordem $oid). Acompanhe em positions."
    }

    private suspend fun closeMargin(idRaw: String?): String {
        ensureConnected()
        val pid = idRaw?.toDoubleOrNull()?.toLong() ?: return "id inválido."
        val id = reqId()
        val root = request(id, IqProtocol.closePositionMessage(id, pid), "result", "position-closed", timeoutMs = 12_000)
        val status = (root["status"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return if (status == null || status in 2000..2999) "Fechamento da posição $pid solicitado." else "A IQ Option recusou fechar a posição $pid (status $status)."
    }

    override fun refreshMs(): Long = if (tracked.values.any { it.result == null }) 1_000 else 4_000

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
            if (first != 0.0) appendLine(String.format(Locale.US, "Variação no período: %.4f%% (fechamento %s → %s)", (last - first) / first * 100, first, last))
            val nowSec = serverNowMs() / 1000; val open = nowSec / size * size
            append("Agora (servidor UTC): ${fmt.format(Date(nowSec * 1000))}:${String.format(Locale.US, "%02d", nowSec % 60)} · vela atual aberta há ${nowSec - open}s · próxima em ${open + size - nowSec}s (use candle_clock para o tempo exato de espera)")
        }.trim()
    }

    // ------------------------------------------------------------------ gerenciamento (Masaniello) e regras duras
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun loadRisk(): RiskState {
        val r = IqRules.decodeRisk(kv?.get("iq_risk")) ?: RiskState()
        return if (r.day == today()) r else RiskState(day = today(), consecLosses = r.consecLosses, pausedUntil = r.pausedUntil)
    }
    private fun saveRisk(r: RiskState) { kv?.put("iq_risk", IqRules.encodeRisk(r)) ; memRisk = r }
    @Volatile private var memRisk: RiskState? = null
    private fun risk(): RiskState = if (kv != null) loadRisk() else (memRisk?.takeIf { it.day == today() } ?: RiskState(day = today()))

    @Volatile private var memMm: MmState? = null
    private fun mmFresh(settings: ConnectorSettings?): MmState {
        val n = (settings?.num("mm_trades", 10.0) ?: 10.0).toInt().coerceIn(2, 50)
        val k = (settings?.num("mm_wins", 5.0) ?: 5.0).toInt().coerceIn(1, n)
        val b = (settings?.num("mm_bank", 10.0) ?: 10.0).coerceAtLeast(1.0)
        return MmState(n, k, b, b)
    }
    private fun mmState(settings: ConnectorSettings?): MmState {
        val saved = if (kv != null) IqRules.decodeMm(kv.get("iq_mm")) else memMm
        val fresh = mmFresh(settings)
        // configurações mudaram e o ciclo ainda não começou → adota os novos N/K/banca
        if (saved == null || (saved.wins + saved.losses == 0 && (saved.n != fresh.n || saved.k != fresh.k || saved.startBank != fresh.startBank)))
            return fresh.copy(cycle = saved?.cycle ?: 1, cyclesWon = saved?.cyclesWon ?: 0, cyclesLost = saved?.cyclesLost ?: 0).also { saveMm(it) }
        return saved
    }
    private fun saveMm(m: MmState) { kv?.put("iq_mm", IqRules.encodeMm(m)); memMm = m }
    private fun odds(settings: ConnectorSettings?) = 1 + (settings?.num("payout_pct", 85.0) ?: 85.0).coerceIn(10.0, 100.0) / 100.0

    /** Resultado real de uma operação do agente → atualiza o dia, a sequência de perdas e o ciclo Masaniello. */
    private fun settle(t: Tracked) = synchronized(this) {
        val pnl = IqRules.netPnl(t.result, t.amount, t.profit)
        val res = resultLabel(t.result)
        var r = risk()
        r = r.copy(dayPnl = r.dayPnl + pnl, dayTrades = r.dayTrades + 1, dayWins = r.dayWins + if (res == "WIN") 1 else 0,
            consecLosses = when (res) { "LOSS" -> r.consecLosses + 1; "WIN" -> 0; else -> r.consecLosses })
        saveRisk(r)
        if (t.mm) {
            var m = (if (kv != null) IqRules.decodeMm(kv.get("iq_mm")) else memMm) ?: return@synchronized
            m = when (res) {
                "WIN" -> m.copy(n = m.n - 1, k = m.k - 1, bank = m.bank + pnl, wins = m.wins + 1, lastResult = "WIN")
                "LOSS" -> m.copy(n = m.n - 1, bank = m.bank + pnl, losses = m.losses + 1, lastResult = "LOSS")
                else -> m.copy(lastResult = "EMPATE")
            }
            if (m.k <= 0) m = MmState(m.startBank.let { _ -> m.n + m.wins + m.losses }, m.wins, m.startBank, m.startBank, m.cycle + 1, cyclesWon = m.cyclesWon + 1, cyclesLost = m.cyclesLost, lastResult = "CICLO GANHO (+${String.format(Locale.US, "%.2f", m.bank - m.startBank)})")
            else if (m.k > m.n || m.bank <= 0.01) m = MmState(m.n + m.wins + m.losses, m.k + m.wins, m.startBank, m.startBank, m.cycle + 1, cyclesWon = m.cyclesWon, cyclesLost = m.cyclesLost + 1, lastResult = "CICLO ENCERRADO (${String.format(Locale.US, "%.2f", m.bank - m.startBank)})")
            saveMm(m)
        }
    }

    /** Regras duras antes de qualquer operação do agente. null = liberado. */
    private fun riskCheck(settings: ConnectorSettings?): String? {
        val r = risk()
        val now = System.currentTimeMillis()
        if (r.pausedUntil > now) return "RECUSADO (regra de proteção): agente em pausa até ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.pausedUntil))} após perdas seguidas. Não opere; aguarde."
        val sl = settings?.num("daily_stop_loss", 10.0) ?: 10.0
        val tp = settings?.num("daily_take_profit", 10.0) ?: 10.0
        if (sl > 0 && r.dayPnl <= -sl) return "RECUSADO (stop loss diário): resultado do dia ${String.format(Locale.US, "%.2f", r.dayPnl)} atingiu o limite de −$sl. Operações do agente encerradas até amanhã."
        if (tp > 0 && r.dayPnl >= tp) return "RECUSADO (meta diária atingida): +${String.format(Locale.US, "%.2f", r.dayPnl)} ≥ $tp. Operações do agente encerradas até amanhã — preserve o lucro."
        val maxL = (settings?.num("max_consec_losses", 3.0) ?: 3.0).toInt()
        if (maxL > 0 && r.consecLosses >= maxL) {
            val until = now + ((settings?.num("cooldown_min", 30.0) ?: 30.0) * 60_000).toLong()
            saveRisk(r.copy(pausedUntil = until, consecLosses = 0))
            return "RECUSADO (regra de proteção): ${r.consecLosses} perdas seguidas. Agente pausado até ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(until))}."
        }
        return null
    }

    private fun mmText(settings: ConnectorSettings?): String {
        val r = risk()
        return buildString {
            if (settings?.bool("mm_enabled") != false) {
                val m = mmState(settings); val o = odds(settings)
                val st = Masaniello.stake(m.bank, m.n, m.k, o)
                appendLine("Masaniello — ciclo ${m.cycle}: faltam ${m.k} acerto(s) em ${m.n} operação(ões) · banca ${String.format(Locale.US, "%.2f", m.bank)} (início ${String.format(Locale.US, "%.2f", m.startBank)}) · " +
                    "próxima entrada ${st?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} · alvo do ciclo ${String.format(Locale.US, "%.2f", Masaniello.target(m.startBank, m.n + m.wins + m.losses, m.k + m.wins, o))} · ciclos ganhos/encerrados ${m.cyclesWon}/${m.cyclesLost}" +
                    if (m.lastResult.isNotBlank()) " · último: ${m.lastResult}" else "")
            } else appendLine("Masaniello desligado.")
            append("Hoje: ${String.format(Locale.US, "%.2f", r.dayPnl)} em ${r.dayTrades} operação(ões) (${r.dayWins} acerto(s)) · perdas seguidas ${r.consecLosses}" +
                (if (r.pausedUntil > System.currentTimeMillis()) " · PAUSADO até ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.pausedUntil))}" else "") +
                " · stop ${settings?.num("daily_stop_loss", 10.0)} · meta ${settings?.num("daily_take_profit", 10.0)}")
        }
    }

    /** Confluência: o resumo dos 35 indicadores (velas de 1 min) precisa concordar com a direção. null = ok. */
    private suspend fun confluenceCheck(a: IqProtocol.Asset, dir: String, settings: ConnectorSettings?): String? {
        if (settings?.text("require_confluence")?.ifEmpty { "true" } == "false") return null
        val id = reqId()
        val root = runCatching { request(id, IqProtocol.getCandlesMessage(id, a.id, 60, 150, serverNowSec()), "candles", timeoutMs = 6_000) }.getOrNull()
            ?: return "RECUSADO: não consegui ler as velas para conferir a confluência. Tente de novo."
        val list = IqProtocol.parseCandles(root["msg"] as? JsonObject ?: JsonObject(emptyMap())).sortedBy { it.from }
        if (list.size < 60) return "RECUSADO: poucas velas para conferir a confluência (${list.size})."
        val r = TechnicalSuite.analyze(TechnicalSuite.Candles(list.map { it.from.toDouble() }, list.map { it.open }, list.map { it.max }, list.map { it.min }, list.map { it.close }, list.map { it.volume }))
        val want = IqRules.direction(r.summary, settings?.bool("confluence_strong") == true)
        return if (want == dir) null else
            "RECUSADO (sem confluência): o resumo técnico de ${a.ticker} está '${r.summary}' (${r.buys} compra × ${r.sells} venda) — não confirma ${dir.uppercase()}. Não force a entrada; procure outro ativo ou espere."
    }

    private suspend fun backtestText(asset: String, tf: String?, count: Int?, strong: Boolean, settings: ConnectorSettings): String {
        val a = IqProtocol.resolveAsset(loadAssets(), asset) ?: return "Ativo '$asset' não encontrado."
        val size = IqProtocol.timeframeSeconds(tf ?: "60") ?: return "timeframe inválido."
        val n = (count ?: 600).coerceIn(300, 1000)
        val id = reqId()
        val root = request(id, IqProtocol.getCandlesMessage(id, a.id, size, n, serverNowSec()), "candles", timeoutMs = 20_000)
        val list = IqProtocol.parseCandles(root["msg"] as? JsonObject ?: JsonObject(emptyMap())).sortedBy { it.from }
        if (list.size < 200) return "Poucas velas para backtest (${list.size})."
        val payout = odds(settings) - 1
        val m = mmFresh(settings)
        val b = withContext(Dispatchers.Default) {
            IqRules.backtest(list.map { IqRules.Bar(it.open, it.max, it.min, it.close, it.volume) }, payout, strong, 120, m.n, m.k, m.bank)
        }
        val edge = b.winRate - b.breakEven
        return buildString {
            appendLine("Backtest ${a.ticker} · ${size}s · ${list.size} velas reais · sinais ${if (strong) "FORTES" else "compra/venda"} · payout ${String.format(Locale.US, "%.0f", payout * 100)}%")
            appendLine("Operações ${b.trades} (call ${b.calls} / put ${b.puts}) · acertos ${b.wins} · erros ${b.losses} · empates ${b.ties}")
            appendLine("Taxa de acerto ${String.format(Locale.US, "%.1f", b.winRate * 100)}% · equilíbrio ${String.format(Locale.US, "%.1f", b.breakEven * 100)}% · vantagem ${String.format(Locale.US, "%+.1f", edge * 100)} p.p. · maior sequência de erros ${b.maxLossStreak}")
            appendLine("Valor fixo 1: ${String.format(Locale.US, "%+.2f", b.flatPnl)} · Masaniello ${m.k}/${m.n} banca ${m.bank}: ${String.format(Locale.US, "%+.2f", b.mmFinal - m.bank)} (ciclos ganhos ${b.mmCyclesWon}, encerrados ${b.mmCyclesLost})")
            append(if (b.trades < 30) "Amostra pequena: não conclua nada com menos de 30 operações."
                else if (edge > 0.03) "Sinal com vantagem neste período: candidato a operar (resultado passado não garante o futuro)."
                else "SEM vantagem estatística neste período: NÃO opere este ativo/tempo gráfico com esta estratégia.")
        }
    }

    private fun serverNowMs(): Long = System.currentTimeMillis() + serverOffsetMs

    private fun hms(epochSec: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochSec * 1000))

    /** Situação da vela atual no relógio do servidor + quanto dormir para acordar antes da próxima. */
    private fun clockText(tf: Int, settings: ConnectorSettings?): String {
        val nowMs = serverNowMs()
        val nowSec = nowMs / 1000
        val open = nowSec / tf * tf
        val next = open + tf
        val elapsed = nowSec - open
        val toNext = (next * 1000 - nowMs) / 1000.0
        val window = (settings?.num("max_entry_delay_sec", 10.0) ?: 10.0).toInt()
        val lead = 6
        val sleepSec = (toNext - lead).let { if (it < 3) it + tf else it }.toInt().coerceAtLeast(1)
        val canEnter = tf == 60 && (window <= 0 || elapsed <= window)
        return buildString {
            appendLine("Servidor: ${hms(nowSec)} · vela de ${tf}s aberta às ${hms(open)} (há ${elapsed}s) · próxima abre às ${hms(next)} (em ${String.format(Locale.US, "%.1f", toNext)}s)")
            if (tf == 60) appendLine(if (canEnter) "JANELA DE ENTRADA ABERTA: dá para entrar agora no início desta vela (até ${window}s após a abertura); uma operação de 1 min expira às ${hms(next)}."
                else "Fora da janela de entrada (${window}s): NÃO entre no meio da vela.")
            append("Para pegar o início da próxima vela: sleep seconds=$sleepSec (acorda ~${lead}s antes de ${hms(if (toNext - lead < 3) next + tf else next)}), então chame candle_clock/indicators e entre nos primeiros segundos.")
        }
    }

    /** Guardrail: só entra no começo da vela de 1 min (a operação cobre a vela analisada). null = ok. */
    private fun entryWindowCheck(settings: ConnectorSettings?): String? {
        val window = (settings?.num("max_entry_delay_sec", 10.0) ?: 10.0).toInt()
        if (window <= 0) return null
        val nowMs = serverNowMs(); val nowSec = nowMs / 1000
        val elapsed = nowSec % 60
        if (elapsed <= window) return null
        val next = nowSec / 60 * 60 + 60
        val toNext = (next * 1000 - nowMs) / 1000.0
        val sleepSec = (toNext - 6).let { if (it < 3) it + 60 else it }.toInt().coerceAtLeast(1)
        return "RECUSADO (fora da janela de entrada): a vela atual abriu há ${elapsed}s (limite ${window}s); entrar agora pegaria o meio da vela. " +
            "A próxima vela abre às ${hms(next)} (em ${String.format(Locale.US, "%.0f", toNext)}s): chame sleep seconds=$sleepSec e entre nos primeiros segundos dela."
    }

    /**
     * Filtro de entrada contra o candle de 1 min (depois da validação TypeSafe Jev, antes da ordem):
     * CALL só com preço atual ABAIXO da abertura do candle; PUT só ACIMA. null = pode entrar; senão o motivo (RECUSADO…).
     */
    private suspend fun entryCheck(a: IqProtocol.Asset, dir: String): String? {
        val id = reqId()
        val root = runCatching { request(id, IqProtocol.getCandlesMessage(id, a.id, 60, 1, serverNowSec()), "candles", timeoutMs = 5_000) }.getOrNull()
            ?: return "RECUSADO: não consegui ler o candle atual de ${a.ticker} para conferir a entrada. Tente de novo."
        val cdl = IqProtocol.parseCandles(root["msg"] as? JsonObject ?: JsonObject(emptyMap())).maxByOrNull { it.from }
            ?: return "RECUSADO: candle atual de ${a.ticker} indisponível. Tente de novo."
        val now = cdl.close // fechamento do candle em formação = preço atual no servidor
        val open = cdl.open
        fun px(v: Double) = fmtPx(v)
        return when {
            dir == "call" && now < open -> null
            dir == "put" && now > open -> null
            dir == "call" -> "RECUSADO (entrada contra o candle): CALL exige preço ABAIXO da abertura do candle de 1 min, mas está ${if (now == open) "igual à" else "acima da"} abertura (${px(now)} vs ${px(open)}). Aguarde o preço recuar abaixo de ${px(open)} e tente de novo em 2–3 s enquanto a janela de entrada estiver aberta; se ela fechar, use candle_clock e espere a próxima vela."
            else -> "RECUSADO (entrada contra o candle): PUT exige preço ACIMA da abertura do candle de 1 min, mas está ${if (now == open) "igual à" else "abaixo da"} abertura (${px(now)} vs ${px(open)}). Aguarde o preço subir acima de ${px(open)} e tente de novo em 2–3 s enquanto a janela de entrada estiver aberta; se ela fechar, use candle_clock e espere a próxima vela."
        }
    }

    /** Busca as velas reais e passa para a suíte de indicadores (que não busca nada sozinha). */
    private suspend fun indicatorsText(asset: String, tf: String?, count: Int?): String {
        val a = IqProtocol.resolveAsset(loadAssets(), asset) ?: return "Ativo '$asset' não encontrado. Use open_assets para ver os tickers."
        val size = IqProtocol.timeframeSeconds(tf ?: "60") ?: return "timeframe inválido. Use 60, 300, 900, 3600… ou 1m/5m/15m/1h."
        val n = (count ?: 300).coerceIn(50, 1000)
        val id = reqId()
        val root = request(id, IqProtocol.getCandlesMessage(id, a.id, size, n, serverNowSec()), "candles", timeoutMs = 20_000)
        val list = IqProtocol.parseCandles(root["msg"] as? JsonObject ?: JsonObject(emptyMap())).sortedBy { it.from }
        if (list.size < 30) return "Poucas velas para análise (${list.size}). Tente outro timeframe ou ativo."
        val k = dev.cueopen.app.uiconnector.custom.TechnicalSuite.Candles(
            list.map { it.from.toDouble() }, list.map { it.open }, list.map { it.max }, list.map { it.min }, list.map { it.close }, list.map { it.volume })
        val r = dev.cueopen.app.uiconnector.custom.TechnicalSuite.analyze(k)
        return dev.cueopen.app.uiconnector.custom.TechnicalSuite.report(r, "${a.ticker} · ${size}s")
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
            if (amount > maxAmount && !settings.bool("mm_enabled")) return "RECUSADO: valor $amount excede o máximo permitido por operação ($maxAmount). Reduza o valor."
            val maxOpen = settings.num("max_open", 3.0).toInt()
            if (openCount() >= maxOpen) return "RECUSADO: já existem ${openCount()} operações abertas (máximo $maxOpen). Aguarde fechar."
            val shortMax = settings.num("short_max_minutes", 5.0).toInt().coerceAtLeast(1)
            if (minutes > shortMax && dir != "call")
                return "RECUSADO: operação LONGA (mais de $shortMax min) só pode ser de COMPRA (call). Venda (put) só em operações curtas (até $shortMax min)."
        }
        val a = IqProtocol.resolveAsset(loadAssets(), asset) ?: run {
            val m = runCatching { loadAllAssets() }.getOrDefault(emptyList()).firstOrNull { it.instrumentId.isNotEmpty() && it.ticker.equals(asset.trim(), true) }
            return if (m != null) "${m.ticker} é ${kindLabel(m.kind)} (margem): use open_position em vez de buy." else "Ativo '$asset' não encontrado. Use open_assets."
        }
        if (!a.open) return "O ativo ${a.ticker} está fechado/suspenso agora."
        if (byAgent) entryWindowCheck(settings)?.let { return it }
        var finalAmount = amount
        var useMm = false
        if (byAgent) {
            riskCheck(settings)?.let { return it }
            if (settings?.bool("mm_enabled") != false) {
                if (tracked.values.any { it.byAgent && it.result == null && it.expSec + 120 > serverNowSec() })
                    return "RECUSADO (Masaniello): já existe uma operação do agente em aberto; no gerenciamento as operações são UMA POR VEZ. Aguarde o resultado."
                val m = mmState(settings)
                val st = Masaniello.stake(m.bank, m.n, m.k, odds(settings)) ?: return "RECUSADO: ciclo Masaniello encerrado; consulte mm_status."
                finalAmount = maxOf(1.0, Math.round(st * 100) / 100.0) // mínimo da corretora: 1
                val maxAmount = settings?.num("max_amount", 1.0) ?: 1.0
                if (finalAmount > maxAmount) return "RECUSADO: a entrada do Masaniello agora é ${String.format(Locale.US, "%.2f", finalAmount)}, acima do seu limite por operação ($maxAmount). Aumente o limite ou reduza a banca do ciclo nas configurações."
                useMm = true
            }
            confluenceCheck(a, dir, settings)?.let { return it }
        }
        if (byAgent && (settings?.text("entry_against_candle")?.ifEmpty { "true" } ?: "true") == "true") {
            entryCheck(a, dir)?.let { return it }
        }
        val exp = IqProtocol.expirationFor(serverNowSec(), minutes)
        subscribeQuotes(a.id) // cotação ao vivo para acompanhar ganhando/perdendo
        val id = reqId()
        val root = request(id, IqProtocol.openOptionMessage(id, acc.id, a.id, dir, exp, finalAmount, IqProtocol.isTurbo(minutes)), "option", "option-rejected", timeoutMs = 15_000)
        val ack = IqProtocol.parseOptionAck(root)
        val optId = ack.optionId
        if (optId == null) {
            err("ordem recusada: ${ack.error}")
            return "RECUSADA pela IQ Option: ${ack.error}"
        }
        tracked[optId] = Tracked(optId, a.ticker, dir, finalAmount, exp, acc.isPractice, acc.currency, activeId = a.id, entry = ack.openPrice ?: quotes[a.id],
            byAgent = byAgent, mm = useMm, odds = odds(settings))
        return "Ordem ABERTA na conta ${acc.label}: ${a.ticker} ${dir.uppercase()} ${money(acc.copy(amount = finalAmount))}" + (if (useMm) " (valor do Masaniello)" else "") + " · expira em ~${minutes} min (id $optId). O resultado chega do servidor; consulte positions."
    }

    private fun positionsText(): String {
        val now = serverNowSec()
        val open = tracked.values.filter { it.result == null }.sortedBy { it.expSec }
        val closed = tracked.values.filter { it.result != null }.sortedByDescending { it.expSec }.take(10)
        return buildString {
            appendLine("== Em execução (${open.size}) ==")
            if (open.isEmpty()) appendLine("nenhuma")
            open.forEach { appendLine("${it.id} ${it.asset} ${it.direction.uppercase()} ${it.amount} ${if (it.demo) "DEMO" else "REAL"} · entrada ${fmtPx(it.entry)} · agora ${fmtPx(quotes[it.activeId])} · ${IqRules.favorDiff(it.asset, it.direction, it.entry, quotes[it.activeId])} · " + liveStatus(it, now)) }
            appendLine("== Últimas fechadas ==")
            if (closed.isEmpty()) appendLine("nenhuma")
            closed.forEach { appendLine("${it.id} ${it.asset} ${it.direction.uppercase()} ${it.amount} ${if (it.demo) "DEMO" else "REAL"} → ${resultLabel(it.result)}" + (it.profit?.let { p -> " (" + String.format(Locale.US, "%.2f", p) + ")" } ?: "")) }
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
        pruneQuotes()
        open.forEach { if (it.activeId != 0) subscribeQuotes(it.activeId) }
        blocks += UiBlock.Table(
            "Operações em execução (ao vivo)", listOf("Ativo", "Dir.", "Valor", "Situação", "Pips", "Entrada → Agora"),
            open.map { listOf(it.asset, it.direction.uppercase(), String.format(Locale.US, "%.2f", it.amount),
                liveStatus(it, now), IqRules.favorDiff(it.asset, it.direction, it.entry, quotes[it.activeId]), "${fmtPx(it.entry)} → ${fmtPx(quotes[it.activeId])}") },
            empty = "Nenhuma operação em execução.",
        )
        blocks += UiBlock.Table(
            "Últimas operações", listOf("Ativo", "Dir.", "Valor", "Resultado"),
            closed.sortedByDescending { it.expSec }.take(8).map { listOf(it.asset, it.direction.uppercase(), String.format(Locale.US, "%.2f", it.amount),
                resultLabel(it.result) + (it.profit?.let { p -> " " + String.format(Locale.US, "%.2f", p) } ?: "")) },
            empty = "Sem operações fechadas nesta sessão.",
        )
        // gerenciamento e regras do agente
        run {
            val rk = risk()
            val items = ArrayList<Stat>()
            if (settings.bool("mm_enabled") || settings.text("mm_enabled").isEmpty()) {
                val m = mmState(settings)
                val st = Masaniello.stake(m.bank, m.n, m.k, odds(settings))
                items += Stat("Masaniello", "${m.k} acertos em ${m.n}", Tone.NEUTRAL)
                items += Stat("Próx. entrada", st?.let { String.format(Locale.US, "%.2f", maxOf(1.0, it)) } ?: "—")
                items += Stat("Banca do ciclo", String.format(Locale.US, "%.2f", m.bank), if (m.bank >= m.startBank) Tone.GOOD else Tone.BAD)
            }
            items += Stat("Hoje", String.format(Locale.US, "%+.2f", rk.dayPnl), if (rk.dayPnl > 0) Tone.GOOD else if (rk.dayPnl < 0) Tone.BAD else Tone.NEUTRAL)
            items += Stat("Perdas seguidas", rk.consecLosses.toString(), if (rk.consecLosses >= 2) Tone.WARN else Tone.NEUTRAL)
            if (rk.pausedUntil > System.currentTimeMillis()) items += Stat("Pausado até", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(rk.pausedUntil)), Tone.WARN)
            blocks += UiBlock.Stats(items.take(6))
            blocks += UiBlock.Button("mm_reset", "Reiniciar ciclo Masaniello", risky = true)
        }
        if (marginIds.isNotEmpty()) {
            val mp = runCatching { withTimeout(4_000) { marginPositions() } }.getOrNull()
            if (mp != null) blocks += UiBlock.Table("Forex / CFD / Cripto", listOf("Ativo", "Lado", "Valor", "P/L"),
                mp.map { listOf(it.instrument, it.side.uppercase(), "${String.format(Locale.US, "%.2f", it.amount)} ×${it.leverage}", it.pnl?.let { p -> String.format(Locale.US, "%.2f", p) } ?: it.status) },
                empty = "Nenhuma posição de margem aberta.")
        }
        // nunca espera a lista completa: usa o que já tem e atualiza em segundo plano
        refreshAssetsInBackground()
        val all = allAssets.ifEmpty { assets }.takeIf { it.isNotEmpty() }
        if (all == null) blocks += UiBlock.Note("Carregando a lista de ativos…", Tone.NEUTRAL)
        if (all != null) {
            val openA = all.filter { it.open }
            blocks += UiBlock.Stats(listOf("turbo", "binary", "digital", "forex", "cfd", "crypto").mapNotNull { k ->
                val n = openA.count { it.kind == k }; if (all.none { it.kind == k }) null else Stat(kindLabel(k), n.toString())
            }.take(6))
            blocks += UiBlock.Table("Ativos abertos (${openA.size})", listOf("Ativo", "Tipo"),
                openA.sortedWith(compareBy({ listOf("turbo", "binary", "digital", "forex", "cfd", "crypto").indexOf(it.kind) }, { it.ticker })).take(60).map { listOf(it.ticker, kindLabel(it.kind)) },
                empty = "Nenhum ativo aberto agora.")
        }
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
            "mm_reset" -> { val m = mmState(settings); saveMm(mmFresh(settings).copy(cycle = m.cycle + 1, cyclesWon = m.cyclesWon, cyclesLost = m.cyclesLost)); Result.success("Novo ciclo Masaniello iniciado.") }
            else -> Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        err("ação $actionId falhou: ${t.message}")
        Result.failure(IllegalStateException(friendly(t)))
    }

    // ------------------------------------------------------------------ portão de validação
    override fun orderIntents(tool: String, args: Map<String, String>): List<dev.cueopen.app.uiconnector.OrderIntent> {
        if (tool != "buy" && tool != "open_position") return emptyList()
        val asset = args["asset"].orEmpty().trim()
        val side = dev.cueopen.app.trading.Sides.parse(if (tool == "buy") args["direction"] else args["side"])
        if (asset.isEmpty() || side == null) return emptyList()
        return listOf(dev.cueopen.app.uiconnector.OrderIntent(asset, side))
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
            val k = args["kind"].orEmpty().ifBlank { "all" }.lowercase()
            val list = loadAllAssets().filter { a ->
                a.open && (f.isEmpty() || a.ticker.uppercase().contains(f)) &&
                    (k == "all" || a.kind == k || (k == "options" && (a.kind == "turbo" || a.kind == "binary")))
            }
            if (list.isEmpty()) "Nenhum ativo aberto" + (if (f.isNotEmpty()) " para '$f'" else "") + (if (k != "all") " ($k)" else "") + "."
            else list.groupBy { it.kind }.entries.joinToString("\n") { (kind, l) ->
                "${kindLabel(kind)} (${l.size})${if (kind in setOf("turbo", "binary")) " — use buy" else if (kind == "digital") " — listado (opere via buy se também existir em turbo/binária)" else " — use open_position"}: " +
                    l.take(120).joinToString(", ") { it.ticker }
            }
        }
        "mm_status" -> mmText(settings)
        "backtest" -> backtestText(args["asset"].orEmpty(), args["timeframe"], args["count"]?.toDoubleOrNull()?.toInt(), args["strong"] == "true", settings)
        "candle_clock" -> clockText(IqProtocol.timeframeSeconds(args["timeframe"] ?: "60") ?: 60, settings)
        "indicators" -> indicatorsText(args["asset"].orEmpty(), args["timeframe"], args["count"]?.toDoubleOrNull()?.toInt())
        "get_candles" -> candlesText(args["asset"].orEmpty(), args["timeframe"], args["count"]?.toDoubleOrNull()?.toInt())
        "buy" -> buy(
            args["asset"].orEmpty(), args["direction"].orEmpty(),
            args["amount"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0,
            args["minutes"]?.toDoubleOrNull()?.toInt() ?: 1,
            settings, byAgent = true,
        )
        "positions" -> {
            ensureConnected()
            val base = positionsText()
            val margin = if (marginIds.isEmpty()) emptyList() else runCatching { marginPositions() }.getOrDefault(emptyList())
            if (margin.isEmpty()) base else base + "\n== Forex/CFD/cripto ==\n" + margin.joinToString("\n") {
                "${it.id} ${it.instrument} ${it.side.uppercase()} ${it.amount} ×${it.leverage} · ${it.status} · P/L ${it.pnl?.let { p -> String.format(Locale.US, "%.2f", p) } ?: "—"}"
            }
        }
        "open_position" -> openMargin(args, settings)
        "close_position" -> closeMargin(args["id"])
        else -> "Ferramenta desconhecida: $name"
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
