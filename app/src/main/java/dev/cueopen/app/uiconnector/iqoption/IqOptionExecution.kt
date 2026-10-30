package dev.cueopen.app.uiconnector.iqoption

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Ordem de abertura Turbo/binária; o transporte permanece isolado para testes em memória. */
data class IqOptionOrder(
    val balanceId: Long,
    val activeId: Int,
    val direction: String,
    val expirationSeconds: Long,
    val amount: Double,
    val turbo: Boolean,
    val timeoutMs: Int = 15_000,
)

data class IqForexOrder(
    val balanceId: Long,
    val instrumentId: String,
    val side: String,
    val amount: Double,
    val leverage: Int,
    val stopLossPct: Double,
    val takeProfitPct: Double,
)

data class IqCloseAck(val accepted: Boolean, val status: Int?)

fun interface IqManualRequestPort {
    suspend fun request(requestId: String, payload: String, expectedNames: List<String>, timeoutMs: Long): JsonObject
}

/** Converte ordens manuais nos comandos IQ Option usados pelo app; pode receber uma porta falsa nos testes. */
class IqManualExecution(
    private val nextRequestId: () -> String,
    private val request: IqManualRequestPort,
) {
    suspend fun openOption(order: IqOptionOrder): IqProtocol.OptionAck {
        val id = nextRequestId()
        val response = request.request(
            id,
            IqProtocol.openOptionMessage(id, order.balanceId, order.activeId, order.direction,
                order.expirationSeconds, order.amount, order.turbo),
            listOf("option", "option-rejected"),
            order.timeoutMs.coerceIn(500, 15_000).toLong(),
        )
        return IqProtocol.parseOptionAck(response)
    }

    suspend fun openForex(order: IqForexOrder): IqProtocol.OptionAck {
        val id = nextRequestId()
        val response = request.request(
            id,
            IqProtocol.placeForexOrderMessage(id, order.balanceId, order.instrumentId, order.side,
                order.amount, order.leverage, order.stopLossPct, order.takeProfitPct),
            listOf("order-placed-temp", "result"),
            15_000L,
        )
        return IqProtocol.parseOrderAck(response)
    }

    suspend fun closeForex(positionId: Long): IqCloseAck {
        require(positionId > 0L) { "positionId deve ser positivo." }
        val id = nextRequestId()
        val response = request.request(id, IqProtocol.closePositionMessage(id, positionId),
            listOf("result", "position-closed"), 12_000L)
        val status = (response["status"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return IqCloseAck(status != null && status in 2000..2999, status)
    }
}
