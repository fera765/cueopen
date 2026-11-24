package dev.cueopen.app.voice

import android.content.Context
import android.os.Build
import dev.cueopen.app.voice.phonon.PhononTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Voz Phonon 40m pt (Gradium, CC BY-NC 4.0): fala em streaming de quadros de 80 ms, normaliza números/datas/símbolos sozinha (lang=pt).
 * Roda só em arm64. Modelo (~75 MB) baixado da branch "voices" no 1º uso.
 */
object PhononVoice {
    const val ID = "phonon-40m-pt"
    private const val BASE = "https://github.com/fera765/cueopen/raw/voices/$ID/"
    const val DEFAULT_VOICE = "Isadora"
    const val SR = 24_000

    /** Biblioteca nativa: a rápida exige dotprod+fp16 (ARMv8.2); sem isso usa a compatível. Lida pelo PhononTTS (Native.load). */
    @JvmStatic val libName: String by lazy {
        val feats = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty() }.getOrDefault("")
        val is64arm = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        if (is64arm && !(feats.contains("asimddp") && feats.contains("fphp"))) "ptts_ffi_compat" else "ptts_ffi"
    }

    val supported: Boolean get() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    private val _state = MutableStateFlow<AppVoice.State>(AppVoice.State.Idle)
    val state: StateFlow<AppVoice.State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** O modelo só aceita uma chamada por vez: tudo roda nesta thread. */
    val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "phonon").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    @Volatile private var tts: PhononTTS? = null
    @Volatile private var loadedVoice: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_voice", Context.MODE_PRIVATE)
    fun dir(ctx: Context) = File(ctx.filesDir, "voices/$ID")
    fun isInstalled(ctx: Context) = listOf("model.q8.gguf", "config.json", "tokenizer.json").all { File(dir(ctx), it).isFile }
    fun useIt(ctx: Context) = prefs(ctx).getBoolean("phonon", true)
    fun setUse(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("phonon", on).apply(); if (on) preload(ctx) }
    /** Phonon é a voz de português quando está instalada e ligada. */
    fun active(ctx: Context) = supported && useIt(ctx) && isInstalled(ctx)
    fun voice(ctx: Context) = prefs(ctx).getString("phonon_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
    fun setVoice(ctx: Context, v: String) { prefs(ctx).edit().putString("phonon_voice", v).apply(); preload(ctx) }
    fun voices(ctx: Context): List<String> {
        tts?.let { t -> return t.voices.filter { it.startsWith("pt_br") }.ifEmpty { t.voices } }
        return File(dir(ctx), "voices").list()?.map { it.substringBefore('.') }?.filter { it.startsWith("pt_br") }?.sorted().orEmpty()
    }

    fun install(ctx: Context) {
        if (_state.value is AppVoice.State.Downloading) return
        val app = ctx.applicationContext
        scope.launch {
            _state.value = AppVoice.State.Downloading(ID, 0f)
            val tmp = File(app.filesDir, "voices/$ID.part")
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                // manifest.txt: "caminho tamanho" por linha (gerado pelo workflow phonon-model).
                val files = java.net.URL("${BASE}manifest.txt").readText().lines().mapNotNull { l ->
                    val p = l.trim().split(' '); if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
                }
                if (files.isEmpty()) error("lista de arquivos vazia")
                val total = files.sumOf { it.second }.coerceAtLeast(1); var got = 0L
                for ((path, _) in files) {
                    val f = File(tmp, path)
                    if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw SecurityException("pacote inválido")
                    f.parentFile?.mkdirs()
                    val c = (java.net.URL(BASE + path).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                    }
                    if (c.responseCode != 200) error("o servidor respondeu ${c.responseCode}")
                    c.inputStream.use { inp -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int
                        while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; _state.value = AppVoice.State.Downloading(ID, (got.toFloat() / total).coerceIn(0f, 1f)) }
                    } }
                }
                release(); dir(app).deleteRecursively()
                if (!tmp.renameTo(dir(app))) error("não consegui salvar")
                prefs(app).edit().putBoolean("phonon", true).apply()
                _state.value = AppVoice.State.Loading
                kotlinx.coroutines.withContext(thread) { engine(app) } ?: error("o motor não carregou")
                _state.value = AppVoice.State.Ready
            } catch (t: Throwable) {
                tmp.deleteRecursively()
                _state.value = AppVoice.State.Failed("Não consegui baixar a voz Phonon: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun uninstall(ctx: Context) { scope.launch(thread) { release(); dir(ctx).deleteRecursively(); _state.value = AppVoice.State.Idle } }

    /** Chamar só na [thread]. */
    fun engine(ctx: Context): PhononTTS? {
        if (!supported || !isInstalled(ctx)) return null
        val want = voice(ctx)
        tts?.let { t -> if (loadedVoice != want) { runCatching { t.setVoice(want) }; loadedVoice = want }; return t }
        return try {
            val cores = Runtime.getRuntime().availableProcessors()
            android.system.Os.setenv("RAYON_NUM_THREADS", (cores / 2).coerceIn(2, 4).toString(), true)
            val t = PhononTTS(dir(ctx).absolutePath, "pt")
            runCatching { t.setVoice(want) }.onFailure { runCatching { t.setVoice(t.voices.firstOrNull { it.contains("Isadora") } ?: t.voices.firstOrNull { it.startsWith("pt_br") } ?: t.voices.first()) } }
            // Aquece (1ª chamada aloca buffers): a 1ª resposta de verdade já sai rápida.
            runCatching { t.speak("Oi.") { false } }
            tts = t; loadedVoice = want; t
        } catch (e: Throwable) {
            _state.value = AppVoice.State.Failed("A voz Phonon não carregou neste aparelho (${e.javaClass.simpleName}); usando a voz Piper.")
            null
        }
    }

    fun preload(ctx: Context) { if (active(ctx)) scope.launch(thread) { engine(ctx.applicationContext) } }
    fun release() { runCatching { tts?.close() }; tts = null; loadedVoice = null }

    /** Texto para o Phonon: tira markdown/links/emojis; números e datas ele mesmo normaliza. */
    fun prepare(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("[*_#`>|~]+"), " ")
        .replace(Regex("[\\p{So}\\p{Cn}\\x{FE0F}\\x{200D}]"), " ")
        .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\s+"), " ").trim()
}
