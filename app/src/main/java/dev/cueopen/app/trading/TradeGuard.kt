package dev.cueopen.app.trading

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/*
 * Portão de validação e coordenação de trading (puro Kotlin, sem Android).
 *
 * Regra do produto: NENHUMA ordem de compra ou venda sai sem uma validação prévia do avaliador de
 * acurácia (TypeSafe Jev). A regra é imposta aqui, em código — não depende do modelo "se lembrar":
 *  - a aprovação só é gravada pelo programa, a partir da resposta real do avaliador;
 *  - cada aprovação vale para UMA ordem (uso único) e expira em poucos minutos;
 *  - o piso de acurácia (70) não pode ser rebaixado pelo agente.
 * A coordenação impede que agentes/venues (carteira Solana e IQ Option) colidam.
 */

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

/** Aprovações de ordens, de uso único, com validade curta. Thread-safe. */
class SignalGate(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 5 * 60_000L,
    val floor: Int = 70,
) {
    data class Approval(val agentKey: String, val venue: String, val asset: String, val side: Side, val accuracy: Int, val at: Long)

    private val items = ArrayList<Approval>()

    val ttlSeconds: Long get() = ttlMs / 1000

    private fun purge() {
        val now = clock()
        items.removeAll { now - it.at > ttlMs }
    }

    /** Grava a aprovação se a acurácia atinge o maior entre [minAccuracy] e o piso. Devolve se gravou. */
    @Synchronized
    fun approve(agentKey: String, venue: String?, asset: String, side: Side, accuracy: Double, minAccuracy: Int): Boolean {
        purge()
        if (accuracy < maxOf(minAccuracy, floor)) return false
        val v = venue?.trim()?.lowercase().orEmpty().ifBlank { "*" }
        val a = AssetKeys.norm(asset)
        if (a.isEmpty()) return false
        items.removeAll { it.agentKey == agentKey && it.venue == v && it.asset == a && it.side == side }
        items.add(Approval(agentKey, v, a, side, accuracy.toInt(), clock()))
        return true
    }

    /** Consome (uso único) uma aprovação válida para este agente/venue/ativo/lado, ou devolve null. */
    @Synchronized
    fun consume(agentKey: String, venue: String, assetKeys: Set<String>, side: Side): Approval? {
        purge()
        val keys = assetKeys.map { AssetKeys.norm(it) }.filter { it.isNotEmpty() }.toSet()
        val v = venue.trim().lowercase()
        val i = items.indexOfFirst { it.agentKey == agentKey && it.side == side && it.asset in keys && (it.venue == "*" || it.venue == v) }
        return if (i >= 0) items.removeAt(i) else null
    }

    /** Devolve uma aprovação que foi consumida mas cuja ordem não chegou a sair (recusada antes do envio). */
    @Synchronized
    fun restore(a: Approval) {
        purge()
        if (items.none { it.agentKey == a.agentKey && it.venue == a.venue && it.asset == a.asset && it.side == a.side }) items.add(a)
    }

    @Synchronized
    fun pendingCount(): Int { purge(); return items.size }
}

/** Interpreta um swap Solana em "pernas" de compra/venda de ativos de risco (não moedas-base). */
object SwapLegs {
    const val SOL = "So11111111111111111111111111111111111111112"
    val STABLES = setOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
    )

    data class Leg(val mint: String, val side: Side)

    private fun isBase(m: String) = m == SOL || m in STABLES

    /**
     * - token → SOL/stable  : SELL do token
     * - SOL/stable → token  : BUY do token
     * - token → token       : SELL do 1º e BUY do 2º
     * - SOL → stable        : SELL de SOL;  stable → SOL: BUY de SOL
     * - stable ↔ stable     : sem pernas (não é posição de risco)
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

data class SignalInput(val id: String, val direction: String?, val venue: String?)
data class JevResult(val id: String, val accuracy: Double, val confidence: Double, val reason: String)

/** Leitura tolerante dos sinais enviados pelo agente e da resposta do avaliador. */
object SignalIO {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun extractArray(text: String): JsonArray? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        val body = if (start >= 0 && end > start) text.substring(start, end + 1) else text
        return runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun num(e: JsonElement?): Double =
        str(e)?.trim()?.removeSuffix("%")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    fun parseInputs(text: String): List<SignalInput> =
        extractArray(text)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = str(o["id"]) ?: return@mapNotNull null
            SignalInput(id, str(o["direction"]), str(o["venue"]))
        }.orEmpty()

    /** null = a resposta não contém um array JSON. */
    fun parseResults(text: String): List<JevResult>? =
        extractArray(text)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            JevResult(str(o["id"]) ?: "?", num(o["accuracy"]), num(o["confidence"]), str(o["reason"]).orEmpty())
        }
}

/** Coordenação entre agentes e venues: um lugar só para travas e "reservas" de ativo. */
object TradeCoordinator {
    /** Portão único de aprovações do app. */
    val gate = SignalGate()

    /** Uma execução por vez na carteira Solana (agentes + avaliador automático de SL/TP). */
    val walletMutex = Mutex()

    /** Uma consulta ao avaliador por vez (evita enxurrada quando vários agentes acordam juntos). */
    val validationMutex = Mutex()

    private val venueLocks = ConcurrentHashMap<String, Mutex>()
    fun venueLock(venue: String): Mutex = venueLocks.getOrPut(venue.trim().lowercase()) { Mutex() }

    private class Claim(val agentKey: String, val until: Long)
    private val claims = ConcurrentHashMap<String, Claim>()

    /**
     * Reserva (venue, ativo) para [agentKey] por [ttlMs]. Se OUTRO agente já tem reserva válida,
     * devolve a chave dele (conflito); caso contrário null (reservado/renovado).
     */
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
