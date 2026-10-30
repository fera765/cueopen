package dev.cueopen.app.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/** Fluxo oficial de seleção do assistente; nunca tenta substituir o papel sem consentimento do sistema. */
object AndroidAssistantRole {
    fun isDefaultAssistant(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(
            context,
            ComponentName(context, FluwyVoiceInteractionService::class.java),
        )
    }.getOrDefault(false)

    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(RoleManager::class.java)
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        return sequenceOf(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        ).map(::Intent).firstOrNull { it.resolveActivity(context.packageManager) != null }
    }
}
