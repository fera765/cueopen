package dev.fluwy.app.voice

import android.content.Context
import android.os.Build
import dev.fluwy.app.voice.phonon.PhononTTS
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
    /** Um modelo por idioma (branch "voices"). O runtime normaliza números, datas e símbolos conforme o [lang]. */
    data class Model(val lang: String, val id: String, val title: String, val defaultVoice: String)
    val MODELS = listOf(
        Model("pt", "phonon-40m-pt", "Português do Brasil", "Isadora"),
        Model("en", "phonon-en", "Inglês", ""),
    )
    fun model(lang: String) = MODELS.firstOrNull { it.lang == lang } ?: MODELS.first()
    private fun base(m: Model) = "https://github.com/fera765/fluwy/raw/voices/${m.id}/"
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
    private val engines = java.util.concurrent.ConcurrentHashMap<String, PhononTTS>()
    private val loadedVoice = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_voice", Context.MODE_PRIVATE)
    fun dir(ctx: Context, lang: String) = File(ctx.filesDir, "voices/${model(lang).id}")
    fun isInstalled(ctx: Context, lang: String) = listOf("model.q8.gguf", "config.json", "tokenizer.json").all { File(dir(ctx, lang), it).isFile }
    fun hasAny(ctx: Context) = supported && MODELS.any { isInstalled(ctx, it.lang) }
    /** Voz do app ligada e ao menos um modelo instalado. */
    fun active(ctx: Context) = supported && hasAny(ctx)
    /** Idioma que de fato vai falar: o pedido, se instalado; senão o outro. */
    fun resolve(ctx: Context, lang: String): String? = lang.takeIf { isInstalled(ctx, it) } ?: MODELS.firstOrNull { isInstalled(ctx, it.lang) }?.lang
    fun voice(ctx: Context, lang: String) = prefs(ctx).getString("phonon_voice_$lang", null) ?: model(lang).defaultVoice
    fun setVoice(ctx: Context, lang: String, v: String) { prefs(ctx).edit().putString("phonon_voice_$lang", v).apply(); preload(ctx, lang) }
    fun voices(lang: String): List<String> = engines[lang]?.voices.orEmpty()

    fun install(ctx: Context, lang: String) {
        if (_state.value is AppVoice.State.Downloading) return
        val app = ctx.applicationContext; val m = model(lang); val base = base(m)
        scope.launch {
            _state.value = AppVoice.State.Downloading(m.id, 0f)
            val tmp = File(app.filesDir, "voices/${m.id}.part")
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                // manifest.txt: "caminho tamanho" por linha (gerado pelo workflow phonon-model).
                val files = java.net.URL("${base}manifest.txt").readText().lines().mapNotNull { l ->
                    val p = l.trim().split(' '); if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
                }
                if (files.isEmpty()) error("lista de arquivos vazia")
                val total = files.sumOf { it.second }.coerceAtLeast(1); var got = 0L
                for ((path, _) in files) {
                    val f = File(tmp, path)
                    if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw SecurityException("pacote inválido")
                    f.parentFile?.mkdirs()
                    val c = (java.net.URL(base + path).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                    }
                    if (c.responseCode != 200) error("o servidor respondeu ${c.responseCode}")
                    c.inputStream.use { inp -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int
                        while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; _state.value = AppVoice.State.Downloading(m.id, (got.toFloat() / total).coerceIn(0f, 1f)) }
                    } }
                }
                kotlinx.coroutines.withContext(thread) { release(lang) }
                dir(app, lang).deleteRecursively()
                if (!tmp.renameTo(dir(app, lang))) error("não consegui salvar")
                _state.value = AppVoice.State.Loading
                kotlinx.coroutines.withContext(thread) { engine(app, lang) } ?: error("o motor não carregou")
                _state.value = AppVoice.State.Ready
            } catch (t: Throwable) {
                tmp.deleteRecursively()
                _state.value = AppVoice.State.Failed("Não consegui baixar a voz (${m.title}): ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun uninstall(ctx: Context, lang: String) { scope.launch(thread) { release(lang); dir(ctx, lang).deleteRecursively(); _state.value = AppVoice.State.Idle } }

    /** Chamar só na [thread]. */
    fun engine(ctx: Context, lang: String): PhononTTS? {
        if (!supported || !isInstalled(ctx, lang)) return null
        val want = voice(ctx, lang)
        engines[lang]?.let { t -> if (want.isNotEmpty() && loadedVoice[lang] != want) { runCatching { t.setVoice(want) }; loadedVoice[lang] = want }; return t }
        return try {
            val cores = Runtime.getRuntime().availableProcessors()
            android.system.Os.setenv("RAYON_NUM_THREADS", (cores / 2).coerceIn(2, 4).toString(), true)
            val t = PhononTTS(dir(ctx, lang).absolutePath, lang)
            if (want.isNotEmpty()) runCatching { t.setVoice(want) }.onFailure { runCatching { t.voices.firstOrNull()?.let(t::setVoice) } }
            // Aquece (1ª chamada aloca buffers): a 1ª resposta de verdade já sai rápida.
            runCatching { t.speak(if (lang == "pt") "Oi." else "Hi.") { false } }
            engines[lang] = t; loadedVoice[lang] = want; t
        } catch (e: Throwable) {
            _state.value = AppVoice.State.Failed("A voz (${model(lang).title}) não carregou neste aparelho (${e.javaClass.simpleName}).")
            null
        }
    }

    fun preload(ctx: Context, lang: String? = null) {
        if (!supported) return
        val app = ctx.applicationContext
        scope.launch(thread) { MODELS.filter { (lang == null || it.lang == lang) && isInstalled(app, it.lang) }.forEach { engine(app, it.lang) } }
    }
    fun release(lang: String) { runCatching { engines.remove(lang)?.close() }; loadedVoice.remove(lang) }

}
