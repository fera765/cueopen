package dev.cueopen.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Vozes do app (Piper/VITS no aparelho via sherpa-onnx, sem internet depois de baixar).
 * Ajustadas no laboratório (tools/voice_lab3): parâmetros escolhidos por nota de naturalidade (UTMOS) e erros (Whisper);
 * em português o texto passa pelo [NormalizePt] (valores, horas, datas, %, abreviações) — erros de pronúncia 8,6% → 0%.
 * O idioma da fala escolhe a voz: texto em português usa a voz pt escolhida, em inglês a voz en.
 */
object AppVoice {
    data class Spec(val id: String, val name: String, val lang: String, val gender: String, val desc: String,
                    val ns: Float, val nw: Float, val ls: Float, val fullMb: Int, val liteMb: Int)
    val CATALOG = listOf(
        Spec("cadu", "Cadu", "pt", "masculina", "Português do Brasil · natural e clara", 0.5f, 0.5f, 1.08f, 59, 14),
        Spec("faber", "Faber", "pt", "masculina", "Português do Brasil · grave e calma", 0.333f, 0.5f, 1.08f, 59, 14),
        Spec("lessac", "Lessac", "en", "feminina", "Inglês americano · narração", 0.333f, 0.8f, 1.0f, 59, 14),
        Spec("ryan", "Ryan", "en", "masculina", "Inglês americano · firme", 0.333f, 0.8f, 1.15f, 59, 14),
    )
    enum class Variant(val tag: String, val label: String) { FULL("fp32", "Padrão"), LITE("int8", "Leve") }
    /**
     * Pacotes leves v3 (branch "voices"): o modelo traz embutidos o PAD após o início e os ditongos como no Piper, e os dados de
     * pronúncia são os do espeak-ng do Piper. Validado por Whisper no motor do app: erros 0,7–2% (antes 8–9%).
     */
    private const val BASE = "https://github.com/fera765/cueopen/raw/voices/"

    sealed class State {
        data object Idle : State()
        data class Downloading(val key: String, val progress: Float) : State()
        data object Loading : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedKey: String? = null
    private val engineLock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_voice", Context.MODE_PRIVATE)
    fun spec(id: String?) = CATALOG.firstOrNull { it.id == id }
    private fun key(id: String, v: Variant) = "piper2-$id-${v.tag}"
    private fun dir(ctx: Context, id: String, v: Variant) = File(ctx.filesDir, "voices/${key(id, v)}")
    /**
     * Dados de pronúncia (espeak) numa pasta ÚNICA que nunca é apagada: o motor carrega o espeak uma vez por processo,
     * a partir do primeiro caminho usado. Se essa pasta sumisse (trocar/remover voz), ele passava a gerar ~1 s de chiado.
     */
    private fun espeakDir(ctx: Context) = File(ctx.filesDir, "voices/espeak3")  // v3: dados de pronúncia do espeak-ng do Piper
    fun isInstalled(ctx: Context, id: String, v: Variant) = File(dir(ctx, id, v), "model.onnx").isFile && File(espeakDir(ctx), "phontab").isFile
    /** Só a versão leve é oferecida (validada por Whisper no laboratório). */
    fun installedVariant(ctx: Context, id: String): Variant? = Variant.LITE.takeIf { isInstalled(ctx, id, it) }

    /** Voz escolhida para um idioma (pt/en); cai para qualquer voz instalada daquele idioma. */
    fun chosen(ctx: Context, lang: String): Spec? {
        val pick = spec(prefs(ctx).getString("voice_$lang", null))?.takeIf { installedVariant(ctx, it.id) != null }
        return pick ?: CATALOG.firstOrNull { it.lang == lang && installedVariant(ctx, it.id) != null }
    }
    fun choose(ctx: Context, id: String) { val s = spec(id) ?: return; prefs(ctx).edit().putString("voice_${s.lang}", id).apply(); preload(ctx, s.lang) }
    fun hasAny(ctx: Context) = CATALOG.any { installedVariant(ctx, it.id) != null }
    /** Voz do app ligada (senão usa a voz do Android). */
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true) && hasAny(ctx)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply() }
    fun speed(ctx: Context) = prefs(ctx).getFloat("speed", 1.0f)
    fun setSpeed(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("speed", v.coerceIn(0.7f, 1.5f)).apply() }

    /** Idioma provável do texto (para escolher a voz). */
    fun detectLang(text: String): String {
        val w = Regex("[\\p{L}']+").findAll(text.lowercase()).map { it.value }.take(80).toList()
        val pt = setOf("de", "que", "não", "você", "para", "com", "uma", "os", "as", "é", "está", "eu", "isso", "mais", "por", "seu", "sua", "como", "já", "também", "olá", "oi", "obrigado")
        val en = setOf("the", "and", "you", "is", "to", "of", "it", "that", "for", "with", "are", "this", "your", "i'm", "can", "hello", "hi", "thanks", "what", "have")
        return if (w.count { it in en } > w.count { it in pt } + 1) "en" else "pt"
    }

    /** Ao abrir o app: remove a voz antiga (Dii) e já carrega a voz em português, se houver. */
    fun prewarm(ctx: Context) {
        scope.launch(Dispatchers.IO) {
            val root = File(ctx.filesDir, "voices")
            root.listFiles()?.filter { it.name.contains("dii") || it.name.endsWith("-fp32") || it.name == "espeak-ng-data" }?.forEach { it.deleteRecursively() }
            // Migração: espeak que veio dentro de cada voz → pasta única.
            // Pacotes antigos (sem a correção do 1º som) saem; o espeak deles vira a pasta única se ainda não houver.
            root.listFiles()?.filter { it.isDirectory && (it.name.startsWith("piper-") || it.name.startsWith("piper2-")) }?.forEach { v ->
                val own = File(v, "espeak-ng-data")
                if (own.isDirectory) { if (!File(espeakDir(ctx), "phontab").isFile) own.renameTo(espeakDir(ctx)) else own.deleteRecursively() }
                if (v.name.startsWith("piper-")) v.deleteRecursively()
            }
            ctx.getSharedPreferences("dii_voice", Context.MODE_PRIVATE).edit().clear().apply()
            preload(ctx, "pt")
        }
    }
    private fun preload(ctx: Context, lang: String) { scope.launch { runCatching { engine(ctx, lang) } } }

    fun install(ctx: Context, id: String, v: Variant) {
        if (_state.value is State.Downloading) return
        val app = ctx.applicationContext; val k = key(id, v)
        scope.launch(Dispatchers.IO) {
            _state.value = State.Downloading(k, 0f)
            val tmp = File(app.cacheDir, "$k.zip")
            try {
                val c = (java.net.URL("$BASE$k.zip").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                }
                if (c.responseCode != 200) error("o servidor respondeu ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: ((spec(id)?.let { if (v == Variant.FULL) it.fullMb else it.liteMb } ?: 60) * 1_000_000L)
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                    while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; _state.value = State.Downloading(k, (got.toFloat() / total).coerceIn(0f, 1f)) }
                } }
                val root = File(app.filesDir, "voices").apply { mkdirs() }
                File(root, k).deleteRecursively()
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(root, e.name)
                        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw SecurityException("pacote inválido")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                        e = z.nextEntry
                    }
                }
                tmp.delete()
                val own = File(dir(app, id, v), "espeak-ng-data")
                if (!File(espeakDir(app), "phontab").isFile) { espeakDir(app).deleteRecursively(); own.renameTo(espeakDir(app)) } else own.deleteRecursively()
                spec(id)?.let { s -> if (prefs(app).getString("voice_${s.lang}", null) == null) prefs(app).edit().putString("voice_${s.lang}", id).apply() }
                prefs(app).edit().putBoolean("enabled", true).apply()
                release()
                engine(app, spec(id)?.lang ?: "pt") ?: error("o motor de voz não carregou")
            } catch (t: Throwable) {
                tmp.delete(); File(app.filesDir, "voices/$k").deleteRecursively()
                _state.value = State.Failed("Não consegui baixar a voz: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun uninstall(ctx: Context, id: String) { release(); Variant.entries.forEach { dir(ctx, id, it).deleteRecursively() }; _state.value = State.Idle }
    fun release() = synchronized(engineLock) { runCatching { tts?.release() }; tts = null; loadedKey = null }

    /** Motor da voz escolhida para o idioma (uma carregada por vez; trocar leva ~0,5 s). */
    fun engine(ctx: Context, lang: String): Pair<OfflineTts, Spec>? = synchronized(engineLock) {
        val s = chosen(ctx, lang) ?: chosen(ctx, if (lang == "pt") "en" else "pt") ?: return null
        val v = installedVariant(ctx, s.id) ?: return null
        val k = key(s.id, v)
        tts?.let { if (loadedKey == k) return it to s }
        runCatching { tts?.release() }; tts = null
        _state.value = State.Loading
        return try {
            val d = dir(ctx, s.id, v)
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = File(d, "model.onnx").absolutePath, lexicon = "", tokens = File(d, "tokens.txt").absolutePath,
                        dataDir = espeakDir(ctx).absolutePath, dictDir = "", noiseScale = s.ns, noiseScaleW = s.nw, lengthScale = s.ls),
                    numThreads = 2, debug = false, provider = "cpu"),
                maxNumSentences = 1)
            val t = OfflineTts(assetManager = null, config = cfg)
            // Aquece e confere: "Olá, tudo bem?" precisa render ~1 s de fala; se vier curto, a pronúncia não carregou.
            val probe = runCatching { t.generate(if (s.lang == "pt") "Olá, tudo bem com você?" else "Hello, how are you today?", 0, 1.0f).samples.size }.getOrDefault(0)
            if (probe < t.sampleRate() * 0.6) {
                runCatching { t.release() }
                _state.value = State.Failed("A voz não carregou direito (dados de pronúncia). Feche e abra o app; se continuar, remova e baixe a voz de novo.")
                return null
            }
            tts = t; loadedKey = k; _state.value = State.Ready
            t to s
        } catch (e: Throwable) {
            _state.value = State.Failed("A voz não carregou neste aparelho (${e.javaClass.simpleName}); usando a voz do Android.")
            null
        }
    }

    /** Prepara o texto para falar: tira markdown e, em português, escreve números/valores por extenso. */
    fun prepare(text: String, lang: String): String {
        val plain = text.replace(Regex("```[\\s\\S]*?```"), " ").replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        return if (lang == "pt") NormalizePt.normalize(plain) else plain.replace(Regex("[*_#`>|]+"), " ").trim()
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
        if (out.isNotEmpty() && out[0].length > 60) {
            val first = out[0]; val cut = first.indexOf(' ', 30).takeIf { it in 30..60 } ?: -1
            if (cut > 0) { out[0] = first.substring(0, cut); out.add(1, first.substring(cut + 1)) }
        }
        return out
    }

    // ───────── Fala em streaming ─────────
    private var playJob: Job? = null
    private var track: AudioTrack? = null
    private var queue: Channel<String>? = null

    private fun newTrack(sr: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sr / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /** Fala agora (interrompe o que estiver falando). Retorna false se não há voz instalada. */
    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null): Boolean {
        val q = startStream(ctx, detectLang(text), onDone) ?: return false
        q.trySend(text); q.close()
        return true
    }

    /**
     * Fala em fluxo contínuo: envie textos (frases chegando) e feche no fim. Cada texto é preparado e cortado em pedaços;
     * enquanto um pedaço toca, o próximo já está sendo gerado (sem pausa entre frases).
     */
    fun startStream(ctx: Context, lang: String = "pt", onDone: (() -> Unit)? = null): Channel<String>? {
        stop()
        val (t, s) = engine(ctx, lang) ?: return null
        val sr = t.sampleRate()
        val q = Channel<String>(Channel.UNLIMITED); queue = q
        val audio = Channel<FloatArray>(4)
        val speed = speed(ctx)
        _speaking.value = true
        playJob = scope.launch {
            val tr = newTrack(sr).also { track = it; it.play() }
            // ~200 ms de silêncio antes da 1ª sílaba: o alto-falante/Bluetooth "acorda" sem engolir o começo da palavra.
            tr.write(FloatArray(sr / 5), 0, sr / 5, AudioTrack.WRITE_BLOCKING)
            val gen = launch(Dispatchers.Default) {
                for (text in q) {
                    for (piece in chunks(prepare(text, s.lang))) {
                        if (!isActive) break
                        // generate() por pedaço: o generateWithCallback derrubava o app (o JNI procura um invoke que lambdas do Kotlin 2 não têm).
                        val samples = synchronized(engineLock) { runCatching { t.generate(piece, 0, speed).samples }.getOrNull() } ?: continue
                        if (!isActive) break
                        audio.send(samples)
                        gap(piece, sr)?.let { audio.send(it) }
                    }
                }
                audio.close()
            }
            var written = 0L
            try {
                for (buf in audio) { if (!isActive) break; tr.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING); written += buf.size }
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

    /** Pausa natural entre frases (fim de frase ~250 ms, vírgula ~120 ms). */
    private fun gap(piece: String, sr: Int): FloatArray? = when (piece.trimEnd().lastOrNull()) {
        '.', '!', '?', '…' -> FloatArray(sr / 4)
        ',', ';', ':' -> FloatArray(sr * 12 / 100)
        else -> null
    }

    /** Para de falar na hora (interrupção). */
    fun stop() {
        queue?.close(); queue = null
        playJob?.cancel(); playJob = null
        runCatching { track?.pause(); track?.flush() }
        _speaking.value = false
        stopPreview()
    }

    // ───────── Amostra (vem no app: dá para ouvir antes de baixar) ─────────
    private var preview: MediaPlayer? = null
    fun playPreview(ctx: Context, id: String, onDone: () -> Unit = {}) {
        stopPreview()
        runCatching {
            val afd = ctx.assets.openFd("voice/preview-$id.mp3")
            preview = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                setOnCompletionListener { onDone(); stopPreview() }
                prepare(); start()
            }
        }.onFailure { onDone() }
    }
    fun stopPreview() { runCatching { preview?.stop(); preview?.release() }; preview = null }

    /** Gera um WAV (respostas em áudio no chat). */
    fun synthesizeToWav(ctx: Context, text: String, out: File): Long? {
        val lang = detectLang(text)
        val (t, s) = engine(ctx, lang) ?: return null
        val sr = t.sampleRate()
        val all = ArrayList<FloatArray>()
        all += FloatArray(sr / 5)  // silêncio inicial: o player não corta a 1ª sílaba
        synchronized(engineLock) { chunks(prepare(text, s.lang)).forEach { p -> all += t.generate(p, 0, speed(ctx)).samples; gap(p, sr)?.let { all += it } } }
        val n = all.sumOf { it.size }
        if (n == 0) return null
        out.parentFile?.mkdirs()
        java.io.DataOutputStream(out.outputStream().buffered()).use { o ->
            fun le32(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            o.writeBytes("RIFF"); le32(36 + n * 2); o.writeBytes("WAVE"); o.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(sr); le32(sr * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(n * 2)
            all.forEach { a -> a.forEach { v -> le16((v.coerceIn(-1f, 1f) * 32767).toInt()) } }
        }
        return n * 1000L / sr
    }
}
