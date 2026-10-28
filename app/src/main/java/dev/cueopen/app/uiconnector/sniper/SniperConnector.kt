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
 * Compra logo após o lançamento (filtrando liquidez mínima e idade máxima) e sai sozinho no take-profit
 * ou stop-loss. A chave privada NUNCA sai do cofre nem chega ao LLM (as trocas são assinadas pelo mesmo
 * executor seguro da carteira). Só opera na Mainnet. Todo valor é limitado pelo teto por operação e pelo
 * teto diário definidos pelo usuário. ALTO RISCO: tokens recém-lançados têm forte chance de perda total
 * (rug pull / baixa liquidez); por isso o trade vem DESLIGADO por padrão.
 */
class SniperConnector(private val ctx: Context) : UiConnector {

    override val spec = UiConnectorSpec(
        id = "sniper",
        name = "Sniper Trader",
        description = "Caçador de meme coins novas na Solana: compra recém-lançadas (com filtros) e vende no alvo de lucro ou stop. Usa a carteira do app.",
        version = "1.0.0",
        loginFields = emptyList(),
        settings = listOf(
            SettingSpec("enable_trading", "Permitir o agente comprar/vender (REAL)",
                "DESLIGADO (padrão): o agente só pesquisa e simula. LIGADO: ele compra e vende meme coins com SOL REAL da sua carteira. ALTO RISCO: tokens novos podem ir a zero.",
                SettingType.SWITCH, "false", risky = true),
            SettingSpec("buy_sol", "SOL por entrada", "Quanto SOL gastar em cada compra de meme coin.", SettingType.NUMBER, "0.02", risky = true),
            SettingSpec("max_positions", "Máx. de posições abertas", "Quantas meme coins o agente pode segurar ao mesmo tempo.", SettingType.NUMBER, "3"),
            SettingSpec("take_profit_pct", "Take profit (%)", "Vende automaticamente quando o lucro atingir esta porcentagem.", SettingType.NUMBER, "60"),
            SettingSpec("stop_loss_pct", "Stop loss (%)", "Vende automaticamente quando a perda atingir esta porcentagem.", SettingType.NUMBER, "35"),
            SettingSpec("trailing_pct", "Trailing stop (%)", "0 = desligado. Protege o lucro: vende se o preço cair esta % a partir do topo alcançado.", SettingType.NUMBER, "0"),
            SettingSpec("min_liquidity_usd", "Liquidez mínima (US$)", "Ignora tokens com menos liquidez que isto (quanto menor, mais arriscado).", SettingType.NUMBER, "8000"),
            SettingSpec("max_age_min", "Idade máxima do token (min)", "Só considera tokens lançados há no máximo este tempo.", SettingType.NUMBER, "120"),
            SettingSpec("daily_cap_sol", "Teto diário de compras (SOL)", "Soma máxima de SOL que o agente pode gastar em compras por dia. 0 = sem teto.", SettingType.NUMBER, "0.2"),
            SettingSpec("slippage_bps", "Slippage (bps)", "Tolerância de deslize (300 = 3%). Tokens novos costumam exigir mais.", SettingType.NUMBER, "300"),
            SettingSpec("auto_sell", "Saída automática (TP/SL)", "Ligado: o app vende sozinho ao atingir take profit, stop loss ou trailing, sem o agente no loop.",
                SettingType.SWITCH, "true"),
        ),
        tools = listOf(
            ToolSpec("scan", "Lista meme coins REAIS recém-lançadas na Solana que passam nos filtros (liquidez mínima, idade máxima): mint, símbolo, liquidez, idade, preço e variação. Não compra nada.", listOf(
                ParamSpec("limit", "Quantidade (1–30). Padrão 15.", "integer", required = false),
                ParamSpec("min_liquidity_usd", "Sobrepõe a liquidez mínima das configurações.", "number", required = false),
                ParamSpec("max_age_min", "Sobrepõe a idade máxima das configurações.", "integer", required = false),
            )),
            ToolSpec("snipe", "COMPRA uma meme coin pelo mint, gastando o SOL configurado (compra real, assinada localmente). Respeita teto por operação, teto diário, máx. de posições e liquidez mínima. Registra a posição com alvo de lucro/stop.", listOf(
                ParamSpec("mint", "Endereço (mint) do token na Solana"),
            ), risky = true),
            ToolSpec("positions", "Posições abertas do sniper com preço de entrada, preço atual, lucro/prejuízo %, topo alcançado e alvos de saída; e as últimas fechadas."),
            ToolSpec("sell", "VENDE agora uma posição aberta (troca o token de volta para SOL).", listOf(
                ParamSpec("mint", "Mint do token da posição a vender"),
            ), risky = true),
        ),
        accent = 0xFF18C29C,
    )

    private val wallet = SolanaWalletRepository(ctx.applicationContext)
    private val market = SolanaMarketDataClient()
    private val store = SniperStore(ctx.applicationContext)
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tradeMutex = Mutex()
    @Volatile private var autoJob: Job? = null
    private val network = SolanaNetwork.MAINNET

    override val isLoggedIn: Boolean get() = wallet.hasWallet() && wallet.backupConfirmed()
    override fun secrets(): List<String> = emptyList() // a chave nunca passa por aqui

    override suspend fun login(credentials: Map<String, String>): Result<Unit> =
        if (isLoggedIn) Result.success(Unit) else Result.failure(IllegalStateException("Crie/importe a carteira Solana no app (tela Carteira) e confirme o backup."))
    override suspend fun logout() {}

    private fun usd(v: Double?): String = v?.let { "US$ " + String.format(Locale.US, if (it < 1.0) "%.8f" else "%.4f", it) } ?: "—"
    private fun sol(v: Double): String = String.format(Locale.US, "%.4f SOL", v)
    private fun ageMin(createdAt: String?): Long? {
        val t = createdAt ?: return null
        val ms = runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull() ?: return null
        return (System.currentTimeMillis() - ms) / 60_000
    }
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    // ------------------------------------------------------------------ descoberta
    private suspend fun candidates(settings: ConnectorSettings, limit: Int, minLiq: Double, maxAge: Long): List<TokenCand> {
        val list = market.discoverTokens("new", 60)
        return list.mapNotNull { t ->
            val liq = t.liquidityUsd ?: 0.0
            val age = ageMin(t.poolCreatedAt)
            if (liq < minLiq) return@mapNotNull null
            if (age != null && age > maxAge) return@mapNotNull null
            TokenCand(t.mint, t.symbol, liq, age, t.priceUsd, t.priceChange24hPct)
        }.take(limit)
    }

    // ------------------------------------------------------------------ ferramentas do agente
    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String = try {
        when (name) {
            "scan" -> {
                val minLiq = args["min_liquidity_usd"]?.toDoubleOrNull() ?: settings.num("min_liquidity_usd", 8000.0)
                val maxAge = (args["max_age_min"]?.toLongOrNull() ?: settings.num("max_age_min", 120.0).toLong())
                val lim = (args["limit"]?.toIntOrNull() ?: 15).coerceIn(1, 30)
                val cs = candidates(settings, lim, minLiq, maxAge)
                if (cs.isEmpty()) "Nenhuma meme coin nova dentro dos filtros (liquidez ≥ ${usd(minLiq)}, idade ≤ ${maxAge}min) agora."
                else "Meme coins novas (filtros: liq ≥ ${usd(minLiq)}, idade ≤ ${maxAge}min):\n" + cs.mapIndexed { i, t ->
                    "${i + 1}. ${t.symbol} ${t.mint} · liq ${usd(t.liquidity)} · ${t.ageMin?.let { "${it}min" } ?: "?"} · ${usd(t.priceUsd)}${t.change24h?.let { " · 24h ${String.format(Locale.US, "%+.1f", it)}%" } ?: ""}"
                }.joinToString("\n") + "\nALTO RISCO: confira você mesmo antes; use snipe <mint> para comprar."
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
        if (store.open().size >= maxPos) return "RECUSADO: já há ${store.open().size} posições abertas (máx. $maxPos). Venda alguma antes."
        if (store.open().any { it.mint == mint }) return "Já existe uma posição aberta neste token."
        val dailyCap = settings.num("daily_cap_sol", 0.2)
        if (dailyCap > 0 && store.spentToday(today()) + buySol > dailyCap + 1e-9)
            return "RECUSADO: teto diário de compras (${sol(dailyCap)}) seria excedido. Já gastou ${sol(store.spentToday(today()))} hoje."
        return tradeMutex.withLock {
            TradeCoordinator.venueLock("sniper").withLock {
                val holder = TradeCoordinator.claim("sniper", mint, "sniper")
                if (holder != null) return@withLock "RECUSADO: já há uma operação em andamento neste token."
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
                                settings.num("trailing_pct", 0.0), peakPct = 0.0))
                            store.addSpent(today(), buySol)
                            if (settings.bool("auto_sell")) startAutoLoop()
                            "COMPRADO ${info?.symbol ?: mint.take(6)} com ${sol(buySol)} · entrada ${usd(entryPrice ?: info?.priceUsd)} · recebeu ${r.outAmountRaw} (unidades) · TP ${settings.num("take_profit_pct", 60.0).toInt()}% / SL ${settings.num("stop_loss_pct", 35.0).toInt()}%. Assinatura: ${r.signature}. ${network.transactionExplorerUrl(r.signature)}"
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
                            val exitPrice = market.priceUsd(mint)
                            val solOut = r.outAmountRaw / 1e9
                            store.close(mint, exitPrice, solOut, reason)
                            TradeCoordinator.release("sniper", mint, "sniper")
                            val pnlSol = solOut - pos.buySol
                            "VENDIDO ${pos.symbol} ($reason) · recebeu ${sol(solOut)} (entrada ${sol(pos.buySol)}, resultado ${String.format(Locale.US, "%+.4f", pnlSol)} SOL) · saída ${usd(exitPrice)}. Assinatura: ${r.signature}. ${network.transactionExplorerUrl(r.signature)}"
                        }
                        is SwapResult.Rejected -> "Venda recusada: ${r.reason} (a posição segue aberta)."
                    }
                } catch (t: Throwable) { "ERRO ao vender: ${t.message ?: t.javaClass.simpleName} (posição segue aberta)." }
            }
        }
    }

    private suspend fun positionsText(settings: ConnectorSettings): String {
        val open = store.open(); val closed = store.closed().takeLast(8).reversed()
        if (open.isEmpty() && closed.isEmpty()) return "Nenhuma posição. Use scan e snipe."
        val prices = runCatching { market.pricesUsd(open.map { it.mint }) }.getOrDefault(emptyMap())
        return buildString {
            appendLine("== Abertas (${open.size}) ==")
            if (open.isEmpty()) appendLine("nenhuma")
            open.forEach { p ->
                val now = prices[p.mint]
                val pnl = if (p.entryPriceUsd != null && p.entryPriceUsd > 0 && now != null) (now - p.entryPriceUsd) / p.entryPriceUsd * 100 else null
                appendLine("${p.symbol} ${p.mint} · entrada ${usd(p.entryPriceUsd)} · agora ${usd(now)} · ${pnl?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"} · TP ${p.tpPct.toInt()}% / SL ${p.slPct.toInt()}%${if (p.trailingPct > 0) " / trail ${p.trailingPct.toInt()}% (topo ${String.format(Locale.US, "%+.1f%%", p.peakPct)})" else ""}")
            }
            appendLine("== Fechadas (recentes) ==")
            if (closed.isEmpty()) appendLine("nenhuma")
            closed.forEach { p -> appendLine("${p.symbol} · ${p.exitReason} · ${String.format(Locale.US, "%+.4f", (p.exitSol ?: 0.0) - p.buySol)} SOL") }
        }.trim()
    }

    // ------------------------------------------------------------------ saída automática (TP/SL/trailing)
    private fun startAutoLoop() {
        if (autoJob?.isActive == true) return
        autoJob = bg.launch {
            while (true) {
                val settings = store.lastSettings() ?: break
                val open = store.open()
                if (open.isEmpty()) break
                if (!settings.autoSell) break
                val prices = runCatching { market.pricesUsd(open.map { it.mint }) }.getOrDefault(emptyMap())
                for (p in open) {
                    val now = prices[p.mint] ?: continue
                    val entry = p.entryPriceUsd ?: continue
                    if (entry <= 0) continue
                    val pnl = (now - entry) / entry * 100
                    if (pnl > p.peakPct) store.updatePeak(p.mint, pnl)
                    val peak = maxOf(p.peakPct, pnl)
                    val hitTp = pnl >= p.tpPct
                    val hitSl = pnl <= -p.slPct
                    val hitTrail = p.trailingPct > 0 && peak > 0 && pnl <= peak - p.trailingPct && peak >= p.tpPct * 0.5
                    if (hitTp || hitSl || hitTrail) {
                        val reason = if (hitTp) "take profit" else if (hitSl) "stop loss" else "trailing stop"
                        runCatching { sellPosition(p.mint, settings.cs, reason) }
                    }
                }
                delay(15_000)
            }
        }
    }

    /** Religa a saída automática ao abrir a tela (processo reiniciado) e guarda as configurações para o loop. */
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
        val prices = runCatching { market.pricesUsd(open.map { it.mint }) }.getOrDefault(emptyMap())
        blocks += UiBlock.Stats(listOf(
            Stat("Saldo", bal?.let { String.format(Locale.US, "%.4f SOL", it / 1e9) } ?: "—"),
            Stat("Posições", "${open.size}/${settings.num("max_positions", 3.0).toInt()}"),
            Stat("Gasto hoje", sol(store.spentToday(today()))),
        ))
        if (open.isNotEmpty()) blocks += UiBlock.Table("Posições abertas", listOf("Token", "Entrada→Agora", "P/L", "Alvos"),
            open.map { p ->
                val now = prices[p.mint]
                val pnl = if (p.entryPriceUsd != null && p.entryPriceUsd > 0 && now != null) (now - p.entryPriceUsd) / p.entryPriceUsd * 100 else null
                listOf(p.symbol, "${usd(p.entryPriceUsd)} → ${usd(now)}", pnl?.let { String.format(Locale.US, "%+.1f%% ", it) } ?: "—", "TP ${p.tpPct.toInt()}/SL ${p.slPct.toInt()}")
            }, empty = "Nenhuma posição aberta.")
        val closed = store.closed().takeLast(6).reversed()
        if (closed.isNotEmpty()) blocks += UiBlock.Table("Últimas fechadas", listOf("Token", "Motivo", "Resultado"),
            closed.map { listOf(it.symbol, it.exitReason, String.format(Locale.US, "%+.4f SOL", (it.exitSol ?: 0.0) - it.buySol)) })
        blocks += UiBlock.Note("Tokens recém-lançados são de altíssimo risco (rug pull, baixa liquidez). O trade só roda se você ligar e dentro dos tetos por operação e diário.", Tone.WARN)
        return blocks
    }

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> =
        Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))

    data class TokenCand(val mint: String, val symbol: String, val liquidity: Double, val ageMin: Long?, val priceUsd: Double?, val change24h: Double?)
}

@Serializable
data class SniperPos(
    val mint: String, val symbol: String, val entryPriceUsd: Double?, val amountTokenRaw: Long, val buySol: Double,
    val openedAt: Long, val tpPct: Double, val slPct: Double, val trailingPct: Double, val peakPct: Double,
    val closed: Boolean = false, val exitPriceUsd: Double? = null, val exitSol: Double? = null, val exitReason: String = "",
)

/** Snapshot das configurações para o loop automático (fora do ciclo do agente). */
class SettingsSnapshot(private val map: Map<String, String>) {
    val cs = ConnectorSettings { map[it] }
    val autoSell get() = cs.bool("auto_sell")
    fun bool(k: String) = cs.bool(k)
    fun num(k: String, d: Double) = cs.num(k, d)
}

/** Persistência das posições e do gasto diário do sniper. */
class SniperStore(context: Context) {
    private val sp = context.getSharedPreferences("sniper_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var settingsKeys: Map<String, String>? = null

    @Synchronized private fun all(): MutableList<SniperPos> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SniperPos.serializer()), sp.getString("positions", "[]")!!) }.getOrDefault(emptyList()).toMutableList()
    @Synchronized private fun save(list: List<SniperPos>) { sp.edit().putString("positions", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SniperPos.serializer()), list)).apply() }

    fun open(): List<SniperPos> = all().filter { !it.closed }
    fun closed(): List<SniperPos> = all().filter { it.closed }
    @Synchronized fun add(p: SniperPos) { val l = all(); l.add(p); save(l) }
    @Synchronized fun updatePeak(mint: String, peak: Double) { save(all().map { if (it.mint == mint && !it.closed) it.copy(peakPct = maxOf(it.peakPct, peak)) else it }) }
    @Synchronized fun close(mint: String, exitPrice: Double?, exitSol: Double?, reason: String) {
        save(all().map { if (it.mint == mint && !it.closed) it.copy(closed = true, exitPriceUsd = exitPrice, exitSol = exitSol, exitReason = reason) else it })
    }

    fun spentToday(day: String): Double = if (sp.getString("spent_day", "") == day) sp.getFloat("spent_sol", 0f).toDouble() else 0.0
    @Synchronized fun addSpent(day: String, sol: Double) {
        val cur = spentToday(day)
        sp.edit().putString("spent_day", day).putFloat("spent_sol", (cur + sol).toFloat()).apply()
    }

    fun saveSettings(s: ConnectorSettings) {
        val keys = listOf("enable_trading", "buy_sol", "max_positions", "take_profit_pct", "stop_loss_pct", "trailing_pct",
            "min_liquidity_usd", "max_age_min", "daily_cap_sol", "slippage_bps", "auto_sell")
        settingsKeys = keys.associateWith { s.text(it) }
    }
    fun lastSettings(): SettingsSnapshot? = settingsKeys?.let { SettingsSnapshot(it) }
}
