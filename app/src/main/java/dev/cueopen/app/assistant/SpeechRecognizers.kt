package dev.cueopen.app.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Cria o SpeechRecognizer do app. Quando o Fluwy é o assistente padrão, o Android aponta o reconhecedor padrão
 * para o próprio FluwyRecognitionService (proxy), que não pode encaminhar para si mesmo: aí o app usa
 * diretamente o reconhecedor real instalado (Google de preferência), em vez de cair em ERROR_CLIENT em loop.
 */
object SpeechRecognizers {
    private val PREFERRED = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox", "com.google.android.as")

    fun create(ctx: Context): SpeechRecognizer {
        val real = realRecognizer(ctx)
        return if (real != null) SpeechRecognizer.createSpeechRecognizer(ctx, real) else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    /** Reconhecedor de outro app a usar no lugar do padrão; null = o padrão do sistema serve. */
    fun realRecognizer(ctx: Context): ComponentName? {
        val selected = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")?.let(ComponentName::unflattenFromString)
        }.getOrNull()
        val installed = installed(ctx)
        if (selected != null && selected.packageName != ctx.packageName && selected in installed) return selected
        if (selected == null || selected.packageName != ctx.packageName) return null
        return pick(installed.filter { it.packageName != ctx.packageName })
    }

    fun pick(candidates: List<ComponentName>): ComponentName? =
        candidates.minByOrNull { c -> PREFERRED.indexOf(c.packageName).let { if (it < 0) PREFERRED.size else it } }

    private fun installed(ctx: Context): List<ComponentName> = runCatching {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
            .mapNotNull { r -> r.serviceInfo?.let { ComponentName(it.packageName, it.name) } }
    }.getOrDefault(emptyList())
}
