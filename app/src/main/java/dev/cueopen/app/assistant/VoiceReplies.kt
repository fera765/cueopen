package dev.cueopen.app.assistant

import android.content.Context
import android.media.MediaMetadataRetriever
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.cueopen.app.data.ChatItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Respostas faladas: o texto do agente vira áudio no próprio aparelho (motor TTS do Android, offline quando a voz
 * pt-BR está instalada; sem API paga) e entra no chat como ChatItem.Audio com player.
 */
object VoiceReplies {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val mutex = Mutex()

    /** Motor de voz do Google (vem no Android, gratuito, sem baixar modelo pelo app); outro motor só se não houver. */
    const val GOOGLE_TTS = "com.google.android.tts"

    private suspend fun open(ctx: Context, enginePkg: String?): TextToSpeech? {
        val d = CompletableDeferred<Boolean>()
        val t = if (enginePkg != null) TextToSpeech(ctx.applicationContext, { status -> d.complete(status == TextToSpeech.SUCCESS) }, enginePkg)
            else TextToSpeech(ctx.applicationContext) { status -> d.complete(status == TextToSpeech.SUCCESS) }
        if (withTimeoutOrNull(8_000) { d.await() } != true) { runCatching { t.shutdown() }; return null }
        return t
    }

    private suspend fun engine(ctx: Context): TextToSpeech? {
        tts?.let { if (ready?.await() == true) return it }
        val d = CompletableDeferred<Boolean>(); ready = d
        val t = open(ctx, GOOGLE_TTS) ?: open(ctx, null)
        if (t == null) { d.complete(false); ready = null; return null }
        tts = t; d.complete(true)
        t.language = Locale("pt", "BR")
        bestVoice(t.voices?.toList().orEmpty().map { VoiceInfo(it.name, it.locale.toLanguageTag(), it.quality, it.isNetworkConnectionRequired,
            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) }, online(ctx))
            ?.let { name -> t.voices?.firstOrNull { it.name == name }?.let { v -> t.voice = v } }
        return t
    }

    data class VoiceInfo(val name: String, val tag: String, val quality: Int, val network: Boolean, val notInstalled: Boolean)

    /** Melhor voz pt-BR: instalada, maior qualidade; com internet aceita as vozes de rede do Google (mais naturais). */
    fun bestVoice(voices: List<VoiceInfo>, online: Boolean): String? = voices
        .filter { it.tag.lowercase().startsWith("pt-br") && !it.notInstalled && (online || !it.network) }
        .sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenBy { if (online) (if (it.network) 0 else 1) else 0 })
        .firstOrNull()?.name

    private fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    /** Texto limpo para fala: sem markdown, links e blocos de código; tamanho limitado. */
    fun speakable(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), " (trecho de código omitido) ")
        .replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "$1")
        .replace(Regex("https?://\\S+"), " link ")
        .replace(Regex("[*_`#>|]"), "")
        .replace(Regex("\\s+"), " ").trim().take(3_800)

    /** Gera o áudio da resposta; null se o motor TTS não estiver disponível. */
    suspend fun synthesize(ctx: Context, dir: File, text: String, sourceItemId: String = ""): ChatItem.Audio? = mutex.withLock {
        val say = speakable(text)
        if (say.isBlank()) return null
        // Voz do app no aparelho (rápida, sem internet) quando instalada e ligada; senão a voz do Android.
        if (dev.cueopen.app.voice.AppVoice.enabled(ctx)) {
            val f = File(dir, "voz_${System.currentTimeMillis()}.wav")
            val ms = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { dev.cueopen.app.voice.AppVoice.synthesizeToWav(ctx, say, f) } }.getOrNull()
            if (ms != null) return ChatItem.Audio(path = f.absolutePath, text = say, durationMs = ms, sourceItemId = sourceItemId)
        }
        val t = engine(ctx) ?: return null
        dir.mkdirs()
        val f = File(dir, "voz_${System.currentTimeMillis()}.wav")
        val id = "u" + System.nanoTime()
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
        })
        if (t.synthesizeToFile(say, null, f, id) != TextToSpeech.SUCCESS) return null
        if (withTimeoutOrNull(60_000) { done.await() } != true || !f.exists() || f.length() < 100) return null
        val dur = runCatching {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(f.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { r.release() }
        }.getOrDefault(0L)
        ChatItem.Audio(path = f.absolutePath, text = say, durationMs = dur, sourceItemId = sourceItemId)
    }
}
