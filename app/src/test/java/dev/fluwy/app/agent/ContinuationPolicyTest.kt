package dev.fluwy.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationPolicyTest {
    @Test
    fun recognizesContinueRequests() {
        listOf("continuar", "Continue", "continua", "pode continuar", "Prossiga.", "siga", "avançar", "retome de onde parou",
            "continue de onde parou", "go on", "Pode seguir", "próxima etapa").forEach { assertTrue(it, ContinuationPolicy.isContinueIntent(it)) }
    }

    @Test
    fun ignoresRealRequests() {
        listOf("continue a pesquisa mas agora na França com 3 ofertas novas", "crie uma seção", "siga o perfil do concorrente no instagram",
            "por que parou?", "").forEach { assertFalse(it, ContinuationPolicy.isContinueIntent(it)) }
    }
}
