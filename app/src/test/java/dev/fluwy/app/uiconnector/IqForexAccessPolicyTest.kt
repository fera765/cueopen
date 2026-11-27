package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqForexAccessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IqForexAccessPolicyTest {
    @Test fun forexMustBeEnabledForAnyAccountType() {
        assertEquals(
            "RECUSADO: o usuário não liberou o agente para operar Forex nas configurações do conector.",
            IqForexAccessPolicy.denialReason(isReal = false, allowForex = false, allowReal = true),
        )
    }

    @Test fun realAccountNeedsItsSeparateExplicitOptIn() {
        assertEquals(
            "RECUSADO: a carteira selecionada é REAL e o agente não foi liberado para ela.",
            IqForexAccessPolicy.denialReason(isReal = true, allowForex = true, allowReal = false),
        )
    }

    @Test fun demoCanProceedWithForexPermissionAndRealNeedsBothOptIns() {
        assertNull(IqForexAccessPolicy.denialReason(isReal = false, allowForex = true, allowReal = false))
        assertNull(IqForexAccessPolicy.denialReason(isReal = true, allowForex = true, allowReal = true))
    }
}
