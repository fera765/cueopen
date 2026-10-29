package dev.cueopen.app.uiconnector.iqoption

/** Intenção aprovada após análise e risco; a plataforma só a transforma em mensagem/ack. */
data class IqOptionOrder(
    val balanceId: Long,
    val activeId: Int,
    val direction: String,
    val expirationSeconds: Long,
    val amount: Double,
    val turbo: Boolean,
)

/** Fronteira de envio: estratégia e política não conhecem WebSocket, request_id ou payload JSON. */
fun interface IqOptionExecutionPort {
    suspend fun open(order: IqOptionOrder): IqProtocol.OptionAck
}
