package dev.cueopen.app.uiconnector.sniper

import android.content.Context
import dev.cueopen.app.trading.TradeCoordinator
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.ParamSpec
import dev.cueopen.app.uiconnector.SettingSpec
import dev.cueopen.app.uiconnector.SettingType
import dev.cueopen.app.uiconnector.Stat
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.ToolSpec
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import dev.cueopen.app.uiconnector.UiConnectorSpec
import dev.cueopen.app.wallet.SolanaMarketDataClient
import dev.cueopen.app.wallet.SolanaNetwork
import dev.cueopen.app.wallet.SolanaRpcClient
import dev.cueopen.app.wallet.SolanaSwapClient
import dev.cueopen.app.wallet.SolanaSwapExecutor
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.SwapResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Sniper Trader — caçador de meme coins novas na Solana, usando a carteira do próprio app.
 *
 * Compra logo após o lançamento (filtrando liquidez mínima e idade máxima) e sai sozinho protegendo o lucro.
 * TUDO é medido pelo SOL que a venda realmente devolveria AGORA (cotação real na Jupiter), não por um preço
 * teórico — assim o take profit considera taxas e slippage de compra e venda e nunca "fecha no lucro" e devolve
 * menos SOL do que entrou. A chave privada nunca sai do cofre nem chega ao LLM. Só Mainnet. ALTO RISCO.
 */
class SniperConnector(private val ctx: Context) : UiConnector {

    override val spec = UiConnectorSpec(
        id = "sniper",
        name = "Sniper Trader",
        description = "Caçador de meme coins novas na Solana: compra recém-lançadas (com filtros) e sai protegendo o lucro (trailing a partir do take profit, stop no zero-a-zero). Usa a carteira do app.",
        version = "1.1.0",
        loginFields = emptyList(),
        settings = listOf(
            SettingSpec("enable_trading", "Permitir o agente comprar/vender (REAL)",
                "DESLIGADO (padrão): o agente só pesquisa. LIGADO: ele compra e vende meme coins com SOL REAL da sua carteira. ALTO RISCO: tokens novos podem ir a zero.",
                SettingType.SWITCH, "false", risky = true),
            SettingSpec("buy_sol", "SOL por entrada", "Quanto SOL gastar em cada compra.", SettingType.NUMBER, "0.02", risky = true),
            SettingSpec("max_positions", "Máx. de posições abertas", "Quantas meme coins segurar ao mesmo tempo.", SettingType.NUMBER, "3"),
            SettingSpec("take_profit_pct", "Take profit / ativação do trailing (%)", "Ao atingir este lucro REAL (em SOL, já com taxas), o alvo deixa de ser fixo: passa a seguir o topo (trailing) para capturar altas de 100%, 1000%… e o stop sobe para o zero-a-zero.", SettingType.NUMBER, "60"),
            SettingSpec("stop_loss_pct", "Stop loss (%)", "Antes de ativar o trailing, vende se a perda REAL atingir esta porcentagem.", SettingType.NUMBER, "35"),
            SettingSpec("trailing_pct", "Trailing stop (%)", "Depois do take profit, vende se cair esta % a partir do topo. 0 = usa metade do take profit.", SettingType.NUMBER, "0"),
            SettingSpec("breakeven_floor_pct", "Piso do zero-a-zero (%)", "Depois do take profit, o stop nunca desce abaixo deste lucro mínimo (cobre as taxas de venda).", SettingType.NUMBER, "1"),
            SettingSpec("min_liquidity_usd", "Liquidez mínima (US$)", "Ignora tokens com menos liquidez que isto.", SettingType.NUMBER, "8000"),
            SettingSpec("max_age_min", "Idade máxima do token (min)", "Só considera tokens lançados há no máximo este tempo.", SettingType.NUMBER, "120"),
            SettingSpec("daily_cap_sol", "Teto diário de compras (SOL)", "Soma máxima de SOL em compras por dia. 0 = sem teto.", SettingType.NUMBER, "0.2"),
            SettingSpec("slippage_bps", "Slippage (bps)", "Tolerância de deslize (300 = 3%).", SettingType.NUMBER, "300"),
            SettingSpec("auto_sell", "Saída automática (trailing/stop)", "Ligado: o app vende sozinho no trailing/stop, sem o agente no loop.", SettingType.SWITCH, "true"),
        ),
        tools = listOf(
            ToolSpec("scan", "Lista meme coins REAIS recém-lançadas na Solana que passam nos filtros: mint, símbolo, liquidez, idade, preço e variação. Não compra.", listOf(
                ParamSpec("limit", "Quantidade (1–30). Padrão 15.", "integer", required = false),
                ParamSpec("min_liquidity_usd", "Sobrepõe a liquidez mínima.", "number", required = false),
                ParamSpec("max_age_min", "Sobrepõe a idade máxima.", "integer", required = false),
            )),
            ToolSpec("snipe", "COMPRA uma meme coin pelo mint, gastando o SOL configurado (compra real, assinada localmente). Respeita teto por operação, teto diário, máx. de posições e liquidez. Registra a posição com saída automática.", listOf(
                ParamSpec("mint", "Endereço (mint) do token na Solana"),
            ), risky = true),
            ToolSpec("positions", "Posições abertas com o lucro/prejuízo REAL agora (quanto SOL a venda devolveria), topo alcançado e o stop atual; e as últimas fechadas."),
            ToolSpec("sell", "VENDE agora uma posição aberta (troca o token de volta para SOL).", listOf(
                ParamSpec("mint", "Mint do token da posição"),
            ), risky = true),
        ),
        accent = 0xFF18C29C,
    )

    private val wallet = SolanaWalletRepository(ctx.applicationContext)
    private val market = SolanaMarketDataClient()
    private val swap = SolanaSwapClient()
    private val store = SniperStore(ctx.applicationContext)
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tradeMutex = Mutex()
    @Volatile private var autoJob: Job? = null
    private val network = SolanaNetwork.MAINNET

    init { if (store.open().isNotEmpty() && store.settingsOrNull()?.bool("auto_sell") != false) startAutoLoop() }

    override val isLoggedIn: Boolean get() = wallet.hasWallet() && wallet.backupConfirmed()
    override fun secrets(): List<String> = emptyList()

    override suspend fun login(credentials: Map<String, String>): Result<Unit> =
        if (isLoggedIn) Result.success(Unit) else Result.failure(IllegalStateException("Crie/importe a carteira Solana no app (tela Carteira) e confirme o backup."))
    override suspend fun logout() {}

    private fun usd(v: Double?): String = v?.let { "US$ " + String.format(Locale.US, if (it < 1.0) "%.8f" else "%.4f", it) } ?: "—"
    private fun sol(v: Double): String = String.format(Locale.US, "%.4f SOL", v)
    private fun pct(v: Double?): String = v?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"
    private fun ageMin(createdAt: String?): Long? {
        val t = createdAt ?: return null
        val ms = runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull() ?: return null
        return (System.currentTimeMillis() - ms) / 60_000
    }
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    /** SOL que a venda de toda a posição devolveria AGORA (cotação real), já com slippage/impacto. null se sem rota. */
    private suspend fun quoteSellSol(pos: SniperPos, slippageBps: Int): Double? = runCatching {
        swap.quote(pos.mint, SolanaSwapClient.SOL_MINT, pos.amountTokenRaw, slippageBps.coerceIn(1, 5000)).outAmountRaw / 1e9
    }.getOrNull()

    /** Lucro/prejuízo REAL em % sobre o SOL investido (o único número que decide TP/SL). */
    private fun realizedPct(pos: SniperPos, sellSol: Double?): Double? =
        if (sellSol == null || pos.buySol <= 0) null else (sellSol - pos.buySol) / pos.buySol * 100

    // ------------------------------------------------------------------ descoberta
    private suspend fun candidates(limit: Int, minLiq: Double, maxAge: Long): List<TokenCand> =
        (runCatching { market.discoverTokens("new", 60) }.getOrElse {
            // 429/limite de requisições: espera curta e tenta de novo uma vez
            kotlinx.coroutines.delay(1500); market.discoverTokens("new", 60)
        }).mapNotNull { t ->
            val liq = t.liquidityUsd ?: 0.0
            val age = ageMin(t.poolCreatedAt)
            if (liq < minLiq) return@mapNotNull null
            if (age != null && age > maxAge) return@mapNotNull null
            TokenCand(t.mint, t.symbol, liq, age, t.priceUsd, t.priceChange24hPct)
        }.take(limit)

    // ------------------------------------------------------------------ ferramentas do agente
    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String = try {
        store.saveSettings(settings)
        when (name) {
            "scan" -> {
                val minLiq = args["min_liquidity_usd"]?.toDoubleOrNull() ?: settings.num("min_liquidity_usd", 8000.0)
                val maxAge = args["max_age_min"]?.toLongOrNull() ?: settings.num("max_age_min", 120.0).toLong()
                val lim = (args["limit"]?.toIntOrNull() ?: 15).coerceIn(1, 30)
                val cs = candidates(lim, minLiq, maxAge)
                if (cs.isEmpty()) "Nenhuma meme coin nova dentro dos filtros (liquidez ≥ ${usd(minLiq)}, idade ≤ ${maxAge}min) agora."
                else "Meme coins novas:\n" + cs.mapIndexed { i, t ->
                    "${i + 1}. ${t.symbol} ${t.mint} · liq ${usd(t.liquidity)} · ${t.ageMin?.let { "${it}min" } ?: "?"} · ${usd(t.priceUsd)}${t.change24h?.let { " · 24h ${String.format(Locale.US, "%+.1f", it)}%" } ?: ""}"
                }.joinToString("\n") + "\nALTO RISCO: confira antes; use snipe <mint> para comprar."
            }
            "snipe" -> snipe(args["mint"].orEmpty().trim(), settings)
            "sell" -> sellPosition(args["mint"].orEmpty().trim(), settings, "manual")
            "positions" -> positionsText(settings)
            else -> "Ferramenta desconhecida: $name"
        }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        "ERRO: ${t.message ?: t.javaClass.simpleName}"
    }

    private suspend fun snipe(mint: String, settings: ConnectorSettings): String {
        if (!dev.cueopen.app.wallet.SolanaAddressValidator.isValidAddress(mint)) return "Mint inválido."
        if (!settings.bool("enable_trading")) return "RECUSADO: o trade está desligado. Ligue 'Permitir o agente comprar/vender' nas configurações do Sniper Trader."
        if (!isLoggedIn) return "RECUSADO: carteira indisponível ou backup não confirmado (tela Carteira)."
        val buySol = settings.num("buy_sol", 0.02)
        if (buySol <= 0.0) return "Configure 'SOL por entrada' maior que zero."
        val maxPos = settings.num("max_positions", 3.0).toInt()
        if (store.open().size >= maxPos) return "RECUSADO: já há ${store.open().size} posições abertas (máx. $maxPos)."
        if (store.open().any { it.mint == mint }) return "Já existe uma posição aberta neste token."
        val dailyCap = settings.num("daily_cap_sol", 0.2)
        if (dailyCap > 0 && store.spentToday(today()) + buySol > dailyCap + 1e-9)
            return "RECUSADO: teto diário (${sol(dailyCap)}) seria excedido. Já gastou ${sol(store.spentToday(today()))} hoje."
        return tradeMutex.withLock {
            TradeCoordinator.venueLock("sniper").withLock {
                if (TradeCoordinator.claim("sniper", mint, "sniper") != null) return@withLock "RECUSADO: já há uma operação em andamento neste token."
                try {
                    val entryPrice = market.priceUsd(mint)
                    val lamports = Math.round(buySol * 1e9)
                    val slippage = settings.num("slippage_bps", 300.0).toInt().coerceIn(1, 5000)
                    val rpc = SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
                    val exec = SolanaSwapExecutor(ctx.applicationContext, wallet, rpc)
                    when (val r = TradeCoordinator.walletMutex.withLock { exec.executeSwap(SolanaSwapClient.SOL_MINT, mint, lamports, slippage, network) }) {
                        is SwapResult.Submitted -> {
                            val info = runCatching { market.tokenInfo(mint) }.getOrNull()
                            store.add(SniperPos(mint, info?.symbol ?: mint.take(6), entryPrice ?: info?.priceUsd, r.outAmountRaw, buySol,
                                System.currentTimeMillis(), settings.num("take_profit_pct", 60.0), settings.num("stop_loss_pct", 35.0),
                                settings.num("trailing_pct", 0.0)))
                            store.addSpent(today(), buySol)
                            if (settings.bool("auto_sell")) startAutoLoop()
                            "COMPRADO ${info?.symbol ?: mint.take(6)} com ${sol(buySol)} · recebeu ${r.outAmountRaw} (unidades) · take profit ${settings.num("take_profit_pct", 60.0).toInt()}% (depois trailing) / stop ${settings.num("stop_loss_pct", 35.0).toInt()}%. Assinatura: ${r.signature}. ${network.transactionExplorerUrl(r.signature)}"
                        }
                        is SwapResult.Rejected -> { TradeCoordinator.release("sniper", mint, "sniper"); "Compra recusada: ${r.reason}" }
                    }
                } catch (t: Throwable) { TradeCoordinator.release("sniper", mint, "sniper"); throw t }
            }
        }
    }

    private suspend fun sellPosition(mint: String, settings: ConnectorSettings, reason: String): String {
        val pos = store.open().firstOrNull { it.mint == mint } ?: return "Nenhuma posição aberta neste token."
        if (!isLoggedIn) return "RECUSADO: carteira indisponível."
        return tradeMutex.withLock {
            TradeCoordinator.venueLock("sniper").withLock {
                try {
                    val slippage = settings.num("slippage_bps", 300.0).toInt().coerceIn(1, 5000)
                    val rpc = SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
                    val exec = SolanaSwapExecutor(ctx.applicationContext, wallet, rpc)
                    when (val r = TradeCoordinator.walletMutex.withLock { exec.executeSwap(mint, SolanaSwapClient.SOL_MINT, pos.amountTokenRaw, slippage, network) }) {
                        is SwapResult.Submitted -> {
                            val solOut = r.outAmountRaw / 1e9
                            store.close(mint, market.priceUsd(mint), solOut, reason)
                            TradeCoordinator.release("sniper", mint, "sniper")
                            val pnlSol = solOut - pos.buySol
                            "VENDIDO ${pos.symbol} ($reason) · recebeu ${sol(solOut)} (entrada ${sol(pos.buySol)}, resultado ${String.format(Locale.US, "%+.4f", pnlSol)} SOL · ${pct(realizedPct(pos, solOut))}). Assinatura: ${r.signature}. ${network.transactionExplorerUrl(r.signature)}"
                        }
                        is SwapResult.Rejected -> "Venda recusada: ${r.reason} (a posição segue aberta)."
                    }
                } catch (t: Throwable) { "ERRO ao vender: ${t.message ?: t.javaClass.simpleName} (posição segue aberta)." }
            }
        }
    }

    /** Stop atual (em % de lucro real) de uma posição, já considerando o trailing/zero-a-zero. */
    private fun currentStopPct(pos: SniperPos, settings: ConnectorSettings): Double {
        if (!pos.tpArmed) return -pos.slPct
        val floor = settings.num("breakeven_floor_pct", 1.0)
        val trail = settings.num("trailing_pct", 0.0).let { if (it > 0) it else pos.tpPct / 2 }
        return maxOf(floor, pos.peakPct - trail)
    }

    private suspend fun positionsText(settings: ConnectorSettings): String {
        val open = store.open(); val closed = store.closed().takeLast(8).reversed()
        if (open.isEmpty() && closed.isEmpty()) return "Nenhuma posição. Use scan e snipe."
        val slip = settings.num("slippage_bps", 300.0).toInt()
        return buildString {
            appendLine("== Abertas (${open.size}) — lucro REAL se vender agora ==")
            if (open.isEmpty()) appendLine("nenhuma")
            open.forEach { p ->
                val sell = quoteSellSol(p, slip)
                val r = realizedPct(p, sell)
                appendLine("${p.symbol} ${p.mint} · venderia por ${sell?.let { sol(it) } ?: "—"} (entrou ${sol(p.buySol)}) · ${pct(r)} · ${if (p.tpArmed) "trailing (topo ${pct(p.peakPct)}, stop ${pct(currentStopPct(p, settings))})" else "TP ${p.tpPct.toInt()}% / stop -${p.slPct.toInt()}%"}")
            }
            appendLine("== Fechadas (recentes) ==")
            if (closed.isEmpty()) appendLine("nenhuma")
            closed.forEach { p -> appendLine("${p.symbol} · ${p.exitReason} · ${String.format(Locale.US, "%+.4f", (p.exitSol ?: 0.0) - p.buySol)} SOL") }
        }.trim()
    }

    // ------------------------------------------------------------------ saída automática (trailing/stop), independente da UI
    private fun startAutoLoop() {
        if (autoJob?.isActive == true) return
        autoJob = bg.launch {
            while (true) {
                val settings = store.settingsOrNull() ?: break
                if (!settings.bool("auto_sell")) break
                val open = store.open()
                if (open.isEmpty()) break
                val slip = settings.num("slippage_bps", 300.0).toInt()
                for (p in open) {
                    val sell = quoteSellSol(p, slip) ?: continue
                    val pnl = realizedPct(p, sell) ?: continue
                    // atualiza o topo e arma o trailing ao cruzar o take profit
                    if (pnl > p.peakPct) store.updatePeak(p.mint, pnl)
                    if (!p.tpArmed && pnl >= p.tpPct) store.armTp(p.mint)
                    val cur = store.open().firstOrNull { it.mint == p.mint } ?: continue
                    val reason = when {
                        !cur.tpArmed && pnl <= -cur.slPct -> "stop loss"
                        cur.tpArmed && pnl <= currentStopPct(cur, settings) -> if (currentStopPct(cur, settings) > 0) "trailing (lucro protegido)" else "stop"
                        else -> null
                    }
                    if (reason != null) runCatching { sellPosition(p.mint, settings, reason) }
                }
                delay(12_000)
            }
        }
    }

    fun armAuto(settings: ConnectorSettings) {
        store.saveSettings(settings)
        if (settings.bool("auto_sell") && store.open().isNotEmpty()) startAutoLoop()
    }

    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> {
        armAuto(settings)
        val blocks = ArrayList<UiBlock>()
        if (!isLoggedIn) {
            blocks += UiBlock.Header("Sniper Trader", "Carteira não configurada", "OFFLINE", Tone.BAD)
            blocks += UiBlock.Note("Crie ou importe a carteira Solana em Carteira e confirme o backup para usar o sniper.", Tone.WARN)
            return blocks
        }
        val addr = wallet.publicAddress().orEmpty()
        val bal = runCatching { SolanaRpcClient.balanceWithFallback(network, addr) }.getOrNull()
        blocks += UiBlock.Header("Sniper Trader", addr.take(4) + "…" + addr.takeLast(4), if (settings.bool("enable_trading")) "TRADE LIGADO" else "só leitura", if (settings.bool("enable_trading")) Tone.WARN else Tone.GOOD)
        val open = store.open()
        val slip = settings.num("slippage_bps", 300.0).toInt()
        blocks += UiBlock.Stats(listOf(
            Stat("Saldo", bal?.let { String.format(Locale.US, "%.4f SOL", it / 1e9) } ?: "—"),
            Stat("Posições", "${open.size}/${settings.num("max_positions", 3.0).toInt()}"),
            Stat("Gasto hoje", sol(store.spentToday(today()))),
        ))
        if (open.isNotEmpty()) blocks += UiBlock.Table("Posições abertas (lucro real)", listOf("Token", "Venderia por", "P/L", "Proteção"),
            open.map { p ->
                val sell = quoteSellSol(p, slip)
                val r = realizedPct(p, sell)
                listOf(p.symbol, "${sell?.let { sol(it) } ?: "—"} (de ${sol(p.buySol)})", pct(r),
                    if (p.tpArmed) "trailing · stop ${pct(currentStopPct(p, settings))}" else "TP ${p.tpPct.toInt()}/SL -${p.slPct.toInt()}")
            }, empty = "Nenhuma posição aberta.")
        val closed = store.closed().takeLast(6).reversed()
        if (closed.isNotEmpty()) blocks += UiBlock.Table("Últimas fechadas", listOf("Token", "Motivo", "Resultado"),
            closed.map { listOf(it.symbol, it.exitReason, String.format(Locale.US, "%+.4f SOL", (it.exitSol ?: 0.0) - it.buySol)) })
        blocks += UiBlock.Note("Lucro/stop medidos pelo SOL que a venda devolveria agora (já com taxas e slippage). Depois do take profit o stop sobe para o zero-a-zero e segue o topo. Tokens novos são altíssimo risco.", Tone.WARN)
        return blocks
    }

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> =
        Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))

    data class TokenCand(val mint: String, val symbol: String, val liquidity: Double, val ageMin: Long?, val priceUsd: Double?, val change24h: Double?)
}

@Serializable
data class SniperPos(
    val mint: String, val symbol: String, val entryPriceUsd: Double?, val amountTokenRaw: Long, val buySol: Double,
    val openedAt: Long, val tpPct: Double, val slPct: Double, val trailingPct: Double,
    val peakPct: Double = 0.0, val tpArmed: Boolean = false,
    val closed: Boolean = false, val exitPriceUsd: Double? = null, val exitSol: Double? = null, val exitReason: String = "",
)

/** Persistência das posições, do gasto diário e das últimas configurações (para a saída automática rodar sem a UI). */
class SniperStore(context: Context) {
    private val sp = context.getSharedPreferences("sniper_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = kotlinx.serialization.builtins.ListSerializer(SniperPos.serializer())

    @Synchronized private fun all(): MutableList<SniperPos> =
        runCatching { json.decodeFromString(ser, sp.getString("positions", "[]")!!) }.getOrDefault(emptyList()).toMutableList()
    @Synchronized private fun save(list: List<SniperPos>) { sp.edit().putString("positions", json.encodeToString(ser, list)).apply() }

    fun open(): List<SniperPos> = all().filter { !it.closed }
    fun closed(): List<SniperPos> = all().filter { it.closed }
    @Synchronized fun add(p: SniperPos) { val l = all(); l.add(p); save(l) }
    @Synchronized fun updatePeak(mint: String, peak: Double) { save(all().map { if (it.mint == mint && !it.closed) it.copy(peakPct = maxOf(it.peakPct, peak)) else it }) }
    @Synchronized fun armTp(mint: String) { save(all().map { if (it.mint == mint && !it.closed) it.copy(tpArmed = true) else it }) }
    @Synchronized fun close(mint: String, exitPrice: Double?, exitSol: Double?, reason: String) {
        save(all().map { if (it.mint == mint && !it.closed) it.copy(closed = true, exitPriceUsd = exitPrice, exitSol = exitSol, exitReason = reason) else it })
    }

    fun spentToday(day: String): Double = if (sp.getString("spent_day", "") == day) sp.getFloat("spent_sol", 0f).toDouble() else 0.0
    @Synchronized fun addSpent(day: String, sol: Double) { sp.edit().putString("spent_day", day).putFloat("spent_sol", (spentToday(day) + sol).toFloat()).apply() }

    private val keys = listOf("enable_trading", "buy_sol", "max_positions", "take_profit_pct", "stop_loss_pct", "trailing_pct",
        "breakeven_floor_pct", "min_liquidity_usd", "max_age_min", "daily_cap_sol", "slippage_bps", "auto_sell")
    fun saveSettings(s: ConnectorSettings) {
        val e = sp.edit(); keys.forEach { e.putString("set_$it", s.text(it)) }; e.apply()
    }
    /** Configurações persistidas (ou null se nunca salvas). Usadas pela saída automática sem a tela aberta. */
    fun settingsOrNull(): ConnectorSettings? {
        if (!sp.contains("set_enable_trading")) return null
        return ConnectorSettings { k -> sp.getString("set_$k", null) }
    }
}
