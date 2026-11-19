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

    private suspend fun engine(ctx: Context): TextToSpeech? {
        tts?.let { if (ready?.await() == true) return it }
        val d = CompletableDeferred<Boolean>(); ready = d
        val t = TextToSpeech(ctx.applicationContext) { status -> d.complete(status == TextToSpeech.SUCCESS) }
        tts = t
        if (withTimeoutOrNull(8_000) { d.await() } != true) return null
        t.language = Locale("pt", "BR")
        return t
    }

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
