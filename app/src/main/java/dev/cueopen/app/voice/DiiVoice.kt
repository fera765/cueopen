package dev.cueopen.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Voz Dii (OpenVoiceOS, Piper/VITS) rodando no aparelho com sherpa-onnx — sem internet depois de baixar.
 *
 * Validada no laboratório (tools/voice_lab, CI): fp32 = 1º áudio ~0,05 s e 12× mais rápida que a fala em 2 núcleos x86;
 * int8 = 19 MB, ~4× mais rápida que a fala, qualidade igual dentro da variação natural do modelo.
 * Latência mínima: o texto é cortado em frases curtas (também nas vírgulas); a 1ª toca enquanto as outras são geradas,
 * direto num AudioTrack em streaming (sem arquivo no meio).
 */
object DiiVoice {
    enum class Variant(val pkg: String, val label: String, val sizeMb: Int) {
        FAST("vits-piper-pt_PT-dii-fp32", "Dii rápida (67 MB)", 67),
        LITE("vits-piper-pt_PT-dii-int8", "Dii leve (22 MB)", 22),
    }
    private const val BASE = "https://github.com/fera765/cueopen/releases/download/voice-dii-v1/"

    sealed class State {
        data object NotInstalled : State()
        data class Downloading(val progress: Float) : State()
        data object Loading : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }
    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedVariant: Variant? = null
    private val engineLock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dii_voice", Context.MODE_PRIVATE)
    /** Voz do app ligada (senão usa a voz do Android). */
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true) && installedVariant(ctx) != null
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply() }
    fun variant(ctx: Context): Variant = runCatching { Variant.valueOf(prefs(ctx).getString("variant", Variant.FAST.name)!!) }.getOrDefault(Variant.FAST)
    /** Velocidade da fala (1.0 normal) e naturalidade (variação de entonação). */
    fun speed(ctx: Context) = prefs(ctx).getFloat("speed", 1.0f)
    fun setSpeed(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("speed", v.coerceIn(0.7f, 1.5f)).apply() }

    private fun dir(ctx: Context, v: Variant) = File(ctx.filesDir, "voices/${v.pkg}")
    fun isInstalled(ctx: Context, v: Variant) = File(dir(ctx, v), "model.onnx").isFile && File(dir(ctx, v), "espeak-ng-data/phontab").isFile
    fun installedVariant(ctx: Context): Variant? = variant(ctx).takeIf { isInstalled(ctx, it) } ?: Variant.entries.firstOrNull { isInstalled(ctx, it) }

    /** Chamado na abertura do app: se a voz está instalada, já carrega o motor (o 1º áudio não paga o carregamento). */
    fun prewarm(ctx: Context) {
        if (installedVariant(ctx) == null) { _state.value = State.NotInstalled; return }
        scope.launch { runCatching { engine(ctx) } }
    }

    /** Baixa e instala a variante (zip da release voice-dii-v1). */
    fun install(ctx: Context, v: Variant) {
        if (_state.value is State.Downloading) return
        scope.launch(Dispatchers.IO) {
            val app = ctx.applicationContext
            _state.value = State.Downloading(0f)
            val tmp = File(app.cacheDir, "${v.pkg}.zip")
            try {
                val c = (java.net.URL(BASE + v.pkg + ".zip").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: (v.sizeMb * 1_000_000L)
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                    while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; _state.value = State.Downloading((got.toFloat() / total).coerceIn(0f, 1f)) }
                } }
                val root = File(app.filesDir, "voices").apply { mkdirs() }
                File(root, v.pkg).deleteRecursively()
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(root, e.name)
                        if (!f.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("zip inválido")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                        e = z.nextEntry
                    }
                }
                tmp.delete()
                prefs(app).edit().putString("variant", v.name).putBoolean("enabled", true).apply()
                release()
                engine(app)
            } catch (t: Throwable) {
                tmp.delete()
                _state.value = State.Failed("Não consegui baixar a voz: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun uninstall(ctx: Context, v: Variant) { release(); dir(ctx, v).deleteRecursively(); _state.value = if (installedVariant(ctx) != null) State.Ready else State.NotInstalled }

    fun release() = synchronized(engineLock) { runCatching { tts?.release() }; tts = null; loadedVariant = null }

    /** Motor carregado (uma vez; ~1 s). 2 threads: rápido sem roubar todos os núcleos do celular. */
    fun engine(ctx: Context): OfflineTts? = synchronized(engineLock) {
        val v = installedVariant(ctx) ?: run { _state.value = State.NotInstalled; return null }
        if (tts != null && loadedVariant == v) return tts
        runCatching { tts?.release() }
        _state.value = State.Loading
        return try {
            val d = dir(ctx, v)
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = File(d, "model.onnx").absolutePath, lexicon = "", tokens = File(d, "tokens.txt").absolutePath,
                        dataDir = File(d, "espeak-ng-data").absolutePath, dictDir = "", noiseScale = 0.6f, noiseScaleW = 0.75f, lengthScale = 1.0f),
                    numThreads = 2, debug = false, provider = "cpu"),
                maxNumSentences = 1)
            OfflineTts(assetManager = null, config = cfg).also { t ->
                tts = t; loadedVariant = v; _state.value = State.Ready
                runCatching { t.generate("Oi.", 0, 1.0f) }  // aquece o grafo: o 1º pedido real já sai rápido
            }
        } catch (t: Throwable) {
            _state.value = State.Failed("A voz não carregou neste aparelho (${t.javaClass.simpleName}); usando a voz do Android.")
            null
        }
    }

    /** Corta em pedaços curtos para o 1º áudio sair logo (frases; frases longas também nas vírgulas). */
    fun chunks(text: String): List<String> {
        val out = mutableListOf<String>()
        Regex("[^.!?…\\n]+[.!?…]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.forEach { s ->
            if (s.length <= 90) out += s
            else {
                var cur = StringBuilder()
                s.split(Regex("(?<=[,;:])\\s+")).forEach { p ->
                    if (cur.isNotEmpty() && cur.length + p.length > 90) { out += cur.toString().trim(); cur = StringBuilder() }
                    cur.append(p).append(' ')
                }
                if (cur.isNotBlank()) out += cur.toString().trim()
            }
        }
        // Primeiro pedaço bem curto (fala começa em ~décimos de segundo).
        if (out.isNotEmpty() && out[0].length > 60) {
            val first = out[0]; val cut = first.indexOf(' ', 30).takeIf { it in 30..60 } ?: -1
            if (cut > 0) { out[0] = first.substring(0, cut); out.add(1, first.substring(cut + 1)) }
        }
        return out
    }

    // ───────── Fala em streaming ─────────
    private var playJob: Job? = null
    private var track: AudioTrack? = null

    private fun newTrack(sr: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sr / 10 * 4))  // ~100 ms: baixa latência
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /** Fila de fala: textos entram (ex.: frases de uma resposta chegando) e saem tocando em sequência, sem pausa entre eles. */
    private var queue: Channel<String>? = null

    /** Fala agora (interrompe o que estiver falando). Retorna false se a voz não está disponível. */
    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null): Boolean {
        val q = startStream(ctx, onDone) ?: return false
        chunks(text).forEach { q.trySend(it) }
        q.close()
        return true
    }

    /**
     * Abre uma fala em fluxo contínuo: envie pedaços com [Channel.trySend] conforme o texto chega e feche no fim.
     * Gera e toca em paralelo: enquanto um pedaço toca, o próximo já está sendo gerado (zero pausa entre frases).
     */
    fun startStream(ctx: Context, onDone: (() -> Unit)? = null): Channel<String>? {
        stop()
        val t = tts ?: engine(ctx) ?: return null
        val sr = t.sampleRate()
        val q = Channel<String>(Channel.UNLIMITED); queue = q
        val audio = Channel<FloatArray>(4)
        val speed = speed(ctx)
        _speaking.value = true
        playJob = scope.launch {
            val tr = newTrack(sr).also { track = it; it.play() }
            val gen = launch(Dispatchers.Default) {
                for (piece in q) {
                    if (!isActive) break
                    val txt = piece.trim(); if (txt.isEmpty()) continue
                    // generate() por pedaço: o generateWithCallback derrubava o app (o JNI procura um invoke que lambdas do Kotlin 2 não têm).
                    val samples = synchronized(engineLock) { runCatching { t.generate(txt, 0, speed).samples }.getOrNull() } ?: continue
                    if (!isActive) break
                    audio.send(samples)
                }
                audio.close()
            }
            var written = 0L
            try {
                for (buf in audio) { if (!isActive) break; tr.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING); written += buf.size }
                // Espera o que já foi escrito terminar de tocar (sem cortar o fim da frase).
                tr.stop()
                val end = System.currentTimeMillis() + 3_000
                while (isActive && System.currentTimeMillis() < end && (tr.playbackHeadPosition.toLong() and 0xffffffffL) < written) delay(20)
            } finally {
                audio.cancel(); gen.cancel(); runCatching { tr.release() }; if (track === tr) track = null
                _speaking.value = false
                onDone?.invoke()
            }
        }
        return q
    }

    /** Para de falar na hora (interrupção). */
    fun stop() {
        queue?.close(); queue = null
        playJob?.cancel(); playJob = null
        runCatching { track?.pause(); track?.flush() }
        _speaking.value = false
    }

    /** Gera um WAV (respostas em áudio no chat). Rápido: ~12× mais rápido que a duração da fala. */
    fun synthesizeToWav(ctx: Context, text: String, out: File): Long? {
        val t = tts ?: engine(ctx) ?: return null
        val sr = t.sampleRate()
        val all = ArrayList<FloatArray>()
        synchronized(engineLock) { chunks(text).forEach { p -> all += t.generate(p, 0, speed(ctx)).samples } }
        val n = all.sumOf { it.size }
        if (n == 0) return null
        out.parentFile?.mkdirs()
        java.io.DataOutputStream(out.outputStream().buffered()).use { o ->
            fun le32(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            o.writeBytes("RIFF"); le32(36 + n * 2); o.writeBytes("WAVE"); o.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(sr); le32(sr * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(n * 2)
            all.forEach { a -> a.forEach { s -> le16((s.coerceIn(-1f, 1f) * 32767).toInt()) } }
        }
        return n * 1000L / sr
    }
}
