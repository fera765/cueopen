package dev.cueopen.app.trading

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Lado de uma ordem financeira. */
enum class Side { BUY, SELL }

object Sides {
    /** buy/call/up/long → BUY; sell/put/down/short → SELL; qualquer outra coisa → null. */
    fun parse(raw: String?): Side? = when (raw?.trim()?.lowercase()) {
        "buy", "call", "up", "long", "compra", "comprar" -> Side.BUY
        "sell", "put", "down", "short", "venda", "vender" -> Side.SELL
        else -> null
    }
}

object AssetKeys {
    /** Normaliza ticker/mint para comparação (caixa, espaços, "/" e "_" ignorados). */
    fun norm(raw: String): String = raw.trim().uppercase().removePrefix("FRONT.").replace("/", "").replace(" ", "").replace("_", "")
}

/** Interpreta um swap Solana em pernas de compra/venda de ativos (não moedas-base). */
object SwapLegs {
    const val SOL = "So11111111111111111111111111111111111111112"
    val STABLES = setOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
    )

    data class Leg(val mint: String, val side: Side)

    private fun isBase(m: String) = m == SOL || m in STABLES

    /**
     * - token → SOL/stable: SELL do token
     * - SOL/stable → token: BUY do token
     * - token → token: SELL do 1º e BUY do 2º
     * - SOL → stable: SELL de SOL; stable → SOL: BUY de SOL
     * - stable ↔ stable: sem pernas
     */
    fun legs(input: String, output: String): List<Leg> {
        val out = ArrayList<Leg>()
        if (!isBase(input)) out.add(Leg(input, Side.SELL))
        if (!isBase(output)) out.add(Leg(output, Side.BUY))
        if (out.isEmpty()) {
            if (input == SOL && output in STABLES) out.add(Leg(SOL, Side.SELL))
            else if (input in STABLES && output == SOL) out.add(Leg(SOL, Side.BUY))
        }
        return out
    }
}

/** Coordenação entre agentes e venues: serialização e reserva temporária por ativo. */
object TradeCoordinator {
    /** Uma execução por vez na carteira Solana (agentes e regras automáticas). */
    val walletMutex = Mutex()

    private val venueLocks = ConcurrentHashMap<String, Mutex>()
    fun venueLock(venue: String): Mutex = venueLocks.getOrPut(venue.trim().lowercase()) { Mutex() }

    private class Claim(val agentKey: String, val until: Long)
    private val claims = ConcurrentHashMap<String, Claim>()

    /** Reserva (venue, ativo) para [agentKey]; retorna o titular se outro agente tem reserva válida. */
    fun claim(venue: String, asset: String, agentKey: String, ttlMs: Long = 120_000L, now: Long = System.currentTimeMillis()): String? {
        val key = venue.trim().lowercase() + "|" + AssetKeys.norm(asset)
        var holder: String? = null
        claims.compute(key) { _, cur ->
            if (cur != null && cur.until > now && cur.agentKey != agentKey) {
                holder = cur.agentKey
                cur
            } else Claim(agentKey, now + ttlMs)
        }
        return holder
    }

    fun release(venue: String, asset: String, agentKey: String) {
        val key = venue.trim().lowercase() + "|" + AssetKeys.norm(asset)
        claims.computeIfPresent(key) { _, cur -> if (cur.agentKey == agentKey) null else cur }
    }
}
