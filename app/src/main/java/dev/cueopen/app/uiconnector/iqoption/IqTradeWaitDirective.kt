package dev.cueopen.app.uiconnector.iqoption

/** Internal bridge from the IQ Option connector to AgentRunner; removed before model/UI output. */
object IqTradeWaitDirective {
    data class Wait(val tradeId: Long, val seconds: Int)
    data class Consumed(val output: String, val waits: List<Wait>)

    private val pattern = Regex("\\[\\[CUE_WAIT_TRADE id=(\\d+) seconds=(\\d+)]]")

    fun append(output: String, tradeId: Long, seconds: Int): String =
        output + "\n[[CUE_WAIT_TRADE id=$tradeId seconds=${seconds.coerceIn(1, 86_400)}]]"

    /** Espera até o expiry do relógio da corretora mais uma curta margem para o evento de fechamento. */
    fun secondsUntilExpiry(expiryEpochSec: Long, serverNowEpochSec: Long, graceSec: Int = 2): Int {
        val remaining = (expiryEpochSec - serverNowEpochSec).coerceAtLeast(0L)
        return (remaining + graceSec.coerceAtLeast(0)).coerceIn(1L, 86_400L).toInt()
    }

    fun consume(output: String): Consumed {
        val waits = pattern.findAll(output).mapNotNull { match ->
            val id = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            val seconds = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..86_400 } ?: return@mapNotNull null
            Wait(id, seconds)
        }.distinctBy { it.tradeId }.toList()
        return Consumed(pattern.replace(output, "").trimEnd(), waits)
    }
}
