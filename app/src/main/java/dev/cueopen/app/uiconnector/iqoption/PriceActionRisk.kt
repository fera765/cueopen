package dev.cueopen.app.uiconnector.iqoption

data class EntryAuthorization(val allowed: Boolean, val reason: String)

/** Política de direção: só libera a ordem se os dados forem válidos e o lado pedido coincidir com o sinal. */
object PriceActionExecutionGate {
    fun authorize(analysis: PriceActionAnalysis, requestedDirection: String, requireStrong: Boolean = false): EntryAuthorization {
        if (!analysis.dataValid) return EntryAuthorization(false, "Análise indisponível ou dados inválidos; ordem bloqueada.")
        val requested = when (requestedDirection.trim().lowercase()) {
            "call", "buy", "compra" -> TradeDirection.CALL
            "put", "sell", "venda" -> TradeDirection.PUT
            else -> TradeDirection.NO_TRADE
        }
        if (requested == TradeDirection.NO_TRADE) return EntryAuthorization(false, "Direção solicitada inválida; use call ou put.")
        if (analysis.decision == TradeDirection.NO_TRADE) return EntryAuthorization(false, "A análise concluiu NÃO OPERAR; ordem bloqueada.")
        if (analysis.decision != requested) return EntryAuthorization(false, "A análise favorece ${analysis.decision.name}, não ${requested.name}; ordem bloqueada.")
        if (requireStrong && !analysis.strongSetup) return EntryAuthorization(false, "Modo forte ligado: o setup não atende ao critério de rompimento ou continuação forte.")
        return EntryAuthorization(true, "Direção confirmada pela análise de preço; as demais salvaguardas continuam obrigatórias.")
    }
}
