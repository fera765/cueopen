package dev.cueopen.app.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService

private const val ROLE_ASSISTANT = "android.app.role.ASSISTANT"

internal enum class AssistantSetupRoute {
    ROLE_CHOOSER,
    VOICE_INPUT_SETTINGS,
    DEFAULT_APPS_SETTINGS,
}

/** Ordem determinística de destinos; o consentimento final permanece sempre nas mãos do sistema/usuário. */
internal object AndroidAssistantSetupPolicy {
    fun routes(
        sdkInt: Int,
        assistantRoleAvailable: Boolean,
        assistantRoleHeld: Boolean = false,
    ): List<AssistantSetupRoute> = buildList {
        // O papel de assistente NÃO é pedível por apps (o seletor devolve "cancelado" na hora no Android puro):
        // o caminho que funciona é a tela do sistema "Assistente digital padrão". O seletor fica só como último recurso.
        add(AssistantSetupRoute.VOICE_INPUT_SETTINGS)
        add(AssistantSetupRoute.DEFAULT_APPS_SETTINGS)
        if (sdkInt >= Build.VERSION_CODES.Q && assistantRoleAvailable && !assistantRoleHeld) {
            add(AssistantSetupRoute.ROLE_CHOOSER)
        }
    }
}

/** Fluxo oficial de seleção do assistente; nunca tenta substituir o papel sem consentimento do sistema. */
object AndroidAssistantRole {
    fun isDefaultAssistant(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(
            context,
            ComponentName(context, FluwyVoiceInteractionService::class.java),
        )
    }.getOrDefault(false)

    /** Role holder and active voice service are distinct states; Android can grant one before activation. */
    fun isRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    fun isRoleRequestAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleAvailable(ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    /** Retorna destinos em ordem de preferência; Settings só entra se o dispositivo resolver o Intent. */
    fun requestIntents(context: Context): List<Intent> {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
        } else null
        val roleAvailable = runCatching {
            manager?.isRoleAvailable(ROLE_ASSISTANT) == true
        }.getOrDefault(false)
        val roleHeld = isRoleHeld(context)

        return AndroidAssistantSetupPolicy.routes(Build.VERSION.SDK_INT, roleAvailable, roleHeld).mapNotNull { route ->
            when (route) {
                AssistantSetupRoute.ROLE_CHOOSER -> runCatching {
                    manager?.createRequestRoleIntent(ROLE_ASSISTANT)
                }.getOrNull()
                AssistantSetupRoute.VOICE_INPUT_SETTINGS -> settingsIntentIfAvailable(
                    context,
                    Settings.ACTION_VOICE_INPUT_SETTINGS,
                )
                AssistantSetupRoute.DEFAULT_APPS_SETTINGS -> settingsIntentIfAvailable(
                    context,
                    Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                )
            }
        }
    }

    fun generalSettingsIntent(context: Context): Intent? = settingsIntentIfAvailable(context, Settings.ACTION_SETTINGS)

    private fun settingsIntentIfAvailable(context: Context, action: String): Intent? = runCatching {
        Intent(action).takeIf { it.resolveActivity(context.packageManager) != null }
    }.getOrNull()
}
