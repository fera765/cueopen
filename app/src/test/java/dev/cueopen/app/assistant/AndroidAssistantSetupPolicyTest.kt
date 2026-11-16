package dev.cueopen.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAssistantSetupPolicyTest {
    @Test fun `Android 10 ou mais novo abre a tela do sistema antes do seletor de papel`() {
        assertEquals(
            listOf(
                AssistantSetupRoute.VOICE_INPUT_SETTINGS,
                AssistantSetupRoute.DEFAULT_APPS_SETTINGS,
                AssistantSetupRoute.ROLE_CHOOSER,
            ),
            AndroidAssistantSetupPolicy.routes(sdkInt = 29, assistantRoleAvailable = true),
        )
    }

    @Test fun `papel indisponivel tenta configuracoes de voz e apps padrao`() {
        assertEquals(
            listOf(AssistantSetupRoute.VOICE_INPUT_SETTINGS, AssistantSetupRoute.DEFAULT_APPS_SETTINGS),
            AndroidAssistantSetupPolicy.routes(sdkInt = 35, assistantRoleAvailable = false),
        )
    }

    @Test fun `papel ja concedido abre configuracoes em vez de pedir concessao novamente`() {
        assertEquals(
            listOf(AssistantSetupRoute.VOICE_INPUT_SETTINGS, AssistantSetupRoute.DEFAULT_APPS_SETTINGS),
            AndroidAssistantSetupPolicy.routes(
                sdkInt = 35,
                assistantRoleAvailable = true,
                assistantRoleHeld = true,
            ),
        )
    }

    @Test fun `Android anterior ao RoleManager nunca tenta pedir o papel`() {
        assertEquals(
            listOf(AssistantSetupRoute.VOICE_INPUT_SETTINGS, AssistantSetupRoute.DEFAULT_APPS_SETTINGS),
            AndroidAssistantSetupPolicy.routes(sdkInt = 28, assistantRoleAvailable = true),
        )
    }
}
