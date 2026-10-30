package dev.cueopen.app.uiconnector.iqoption

/** Garante que as mesmas permissões de Forex/conta Real se apliquem a abertura e fechamento. */
object IqForexAccessPolicy {
    fun denialReason(isReal: Boolean, allowForex: Boolean, allowReal: Boolean): String? = when {
        !allowForex -> "RECUSADO: o usuário não liberou o agente para operar Forex nas configurações do conector."
        isReal && !allowReal -> "RECUSADO: a carteira selecionada é REAL e o agente não foi liberado para ela."
        else -> null
    }
}
