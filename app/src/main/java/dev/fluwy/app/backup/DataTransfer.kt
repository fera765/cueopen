@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.fluwy.app.backup

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.AppJson
import dev.fluwy.app.data.AppState
import dev.fluwy.app.data.Connector
import dev.fluwy.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exportar / importar todos os dados do app num .zip.
 *
 * Aberto (JSON legível): agentes, sessões (chats), skills, agenda, seções, conhecimento, memórias, páginas,
 * configurações SEM segredos, conectores (lista, sem tokens: pedem login de novo), gatilhos, UI Conectores e
 * permissões de sites.
 *
 * Criptografado com a senha do usuário (secure.bin, ver [SecureBox]): chaves de API/tokens das configurações,
 * Pix, cartões, carteira (seed), perfis de proxy e as sessões de login do browser (perfil do WebView).
 */
object DataTransfer {
    const val FORMAT = "fluwy-backup"
    const val VERSION = 1

    @Serializable
    data class Manifest(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val createdAt: Long = 0,
        val appVersion: String = "",
        val device: String = "",
        val encrypted: Boolean = true,
        val counts: Map<String, Int> = emptyMap(),
    )

    data class Report(val lines: List<String>)

    /** Preferências levadas abertas (sem segredos). */
    private val PLAIN_PREFS = listOf("fluwy_triggers", "browser_site_permissions", "wallet_agent_policies", "wallet_day_trade_rules", "solana_wallet_settings")
    /** Preferências que podem ter senha/credencial: só na parte criptografada. */
    private val SECURE_PREFS = listOf("proxy_profiles")
    /** Pastas do WebView que são só cache (recriadas sozinhas). */
    private val WEBVIEW_SKIP = setOf("Cache", "Code Cache", "GPUCache", "DawnCache", "DawnGraphiteCache", "DawnWebGPUCache", "GrShaderCache",
        "ShaderCache", "GraphiteDawnCache", "Crashpad", "CacheStorage", "ScriptCache", "component_crx_cache", "BrowserMetrics")

    private const val STAGE_DIR = ".import_stage"

    // ------------------------------------------------------------------ regras puras (testadas)

    /** Estado sem segredos: tokens de conectores e chaves/tokens das configurações saem. */
    fun sanitize(state: AppState): AppState = state.copy(
        connectors = state.connectors.map(::stripConnector),
        settings = stripSettings(state.settings),
    )

    fun stripConnector(c: Connector): Connector = c.copy(
        accessToken = null, refreshToken = null, expiresAt = null, clientSecret = null,
        // Conector que exige login volta desconectado (aparece na lista com "Conectar").
        connectedAt = if (c.needsAuth) null else c.connectedAt,
        lastError = null,
    )

    fun stripSettings(s: Settings): Settings = s.copy(
        apiKey = "", telegramBotToken = "", discordWebhookUrl = "", slackBotToken = "",
        oauthTokens = emptyMap(), savedProviders = emptyMap(),
    )

    /** Configurações finais: as do backup; segredos vêm da parte criptografada ou, sem ela, ficam os atuais. */
    fun mergeSettings(imported: Settings, secure: Settings?, current: Settings): Settings = secure ?: imported.copy(
        apiKey = current.apiKey, telegramBotToken = current.telegramBotToken, discordWebhookUrl = current.discordWebhookUrl,
        slackBotToken = current.slackBotToken, oauthTokens = current.oauthTokens, savedProviders = current.savedProviders,
    )

    fun prefsToJson(all: Map<String, *>): JsonObject = buildJsonObject {
        all.forEach { (k, v) ->
            when (v) {
                is String -> put(k, buildJsonObject { put("t", "s"); put("v", v) })
                is Boolean -> put(k, buildJsonObject { put("t", "b"); put("v", v) })
                is Int -> put(k, buildJsonObject { put("t", "i"); put("v", v) })
                is Long -> put(k, buildJsonObject { put("t", "l"); put("v", v) })
                is Float -> put(k, buildJsonObject { put("t", "f"); put("v", v) })
                is Set<*> -> put(k, buildJsonObject { put("t", "ss"); put("v", buildJsonArray { v.filterIsInstance<String>().forEach { add(JsonPrimitive(it)) } }) })
                else -> Unit
            }
        }
    }

    fun jsonToPrefs(o: JsonObject, e: SharedPreferences.Editor) {
        o.forEach { (k, el) ->
            val w = el as? JsonObject ?: return@forEach
            val t = (w["t"] as? JsonPrimitive)?.contentOrNull
            val v = w["v"]
            val p = v as? JsonPrimitive
            when (t) {
                "s" -> p?.contentOrNull?.let { e.putString(k, it) }
                "b" -> p?.booleanOrNull?.let { e.putBoolean(k, it) }
                "i" -> p?.contentOrNull?.toIntOrNull()?.let { e.putInt(k, it) }
                "l" -> p?.contentOrNull?.toLongOrNull()?.let { e.putLong(k, it) }
                "f" -> p?.contentOrNull?.toFloatOrNull()?.let { e.putFloat(k, it) }
                "ss" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()?.let { e.putStringSet(k, it) }
            }
        }
    }

    /** Nome de entrada de zip seguro (sem "..", sem caminho absoluto). */
    fun safeEntry(name: String): String? {
        val n = name.replace('\\', '/').trimStart('/')
        if (n.isBlank() || n.split('/').any { it == ".." }) return null
        return n
    }

    fun webviewSkipped(relPath: String): Boolean = relPath.split('/').any { it in WEBVIEW_SKIP }

    // ------------------------------------------------------------------ exportar

    private fun ZipOutputStream.text(name: String, text: String) { putNextEntry(ZipEntry(name)); write(text.toByteArray()); closeEntry() }

    private fun ZipOutputStream.dir(base: File, prefix: String, skip: (String) -> Boolean = { false }) {
        if (!base.exists()) return
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(base).invariantSeparatorsPath
            if (skip(rel)) return@forEach
            runCatching {
                f.inputStream().use { i -> putNextEntry(ZipEntry("$prefix/$rel")); i.copyTo(this); closeEntry() }
            }
        }
    }

    private fun webviewDir(ctx: Context) = File(ctx.applicationInfo.dataDir, "app_webview")
    private fun browserSessionsDir(ctx: Context) = File(ctx.filesDir, "browser-sessions")

    /**
     * Gera o .zip em Downloads/Fluwy. Retorna o caminho mostrado ao usuário.
     * [onProgress] recebe (0..1, etapa).
     */
    suspend fun export(password: CharArray, onProgress: (Float, String) -> Unit): String = withContext(Dispatchers.IO) {
        require(password.size >= 6) { "A senha precisa ter pelo menos 6 caracteres." }
        val app = FluwyApp.instance
        val tmp = File(app.cacheDir, "export-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onProgress(0.03f, "Salvando o estado atual")
            app.store.flush()
            withContext(Dispatchers.Main) { runCatching { android.webkit.CookieManager.getInstance().flush() } }
            val state = app.store.state.value
            val uiBackup = runCatching { app.uiConnectors.exportBackup() }.getOrNull()
            val hubConns = runCatching { dev.fluwy.app.connectors.HubApi.connections() }.getOrDefault(emptyList())

            // ---- parte criptografada
            onProgress(0.12f, "Juntando dados sensíveis")
            val secureZip = File(tmp, "secure.zip")
            ZipOutputStream(secureZip.outputStream().buffered()).use { z ->
                z.text("settings.json", AppJson.encodeToString(Settings.serializer(), state.settings))
                z.text("vault.json", vaultJson(app).toString())
                walletJson(app)?.let { z.text("wallet.json", it.toString()) }
                SECURE_PREFS.forEach { n -> z.text("prefs/$n.json", prefsToJson(app.getSharedPreferences(n, Context.MODE_PRIVATE).all).toString()) }
                onProgress(0.25f, "Copiando as sessões do browser")
                z.dir(webviewDir(app), "webview") { webviewSkipped(it) }
                z.dir(browserSessionsDir(app), "browser-sessions")
            }
            onProgress(0.45f, "Criptografando")
            val secureBin = File(tmp, "secure.bin")
            secureZip.inputStream().buffered().use { i -> secureBin.outputStream().buffered().use { o -> SecureBox.encrypt(password, i, o) } }
            secureZip.delete()

            // ---- zip final
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val name = "fluwy-dados-$stamp.zip"
            val manifest = Manifest(
                createdAt = System.currentTimeMillis(), appVersion = dev.fluwy.app.BuildConfig.VERSION_NAME, device = Build.MODEL,
                counts = mapOf(
                    "agents" to state.agents.size, "sessions" to state.sessions.size, "skills" to state.skills.size,
                    "scheduled" to state.scheduled.size, "connectors" to state.connectors.size + hubConns.size + (uiBackup?.let { 1 } ?: 0),
                    "browserLogins" to state.browserLogins.size,
                ),
            )
            onProgress(0.6f, "Gravando em Downloads")
            val (out, where) = openDownload(app, name)
            out.use { os ->
                ZipOutputStream(os.buffered()).use { z ->
                    z.text("manifest.json", AppJson.encodeToString(Manifest.serializer(), manifest))
                    putState(z, sanitize(state))
                    onProgress(0.75f, "Gravando conectores e preferências")
                    PLAIN_PREFS.forEach { n -> z.text("data/prefs/$n.json", prefsToJson(app.getSharedPreferences(n, Context.MODE_PRIVATE).all).toString()) }
                    uiBackup?.let { z.text("data/uiconnectors.json", it) }
                    z.text("data/hub-connections.json", buildJsonArray {
                        hubConns.forEach { c -> add(buildJsonObject { put("pieceName", c.pieceName); put("displayName", c.displayName) }) }
                    }.toString())
                    z.text("LEIA-ME.txt", README)
                    onProgress(0.88f, "Anexando a parte criptografada")
                    z.putNextEntry(ZipEntry("secure.bin")); secureBin.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
            finishDownload(app)
            onProgress(1f, "Pronto")
            where
        } catch (t: Throwable) {
            // Arquivo pela metade em Downloads: apaga.
            pendingUri?.let { u -> runCatching { app.contentResolver.delete(u, null, null) } }
            throw t
        } finally {
            tmp.deleteRecursively()
            pendingUri = null
            password.fill(' ')
        }
    }

    private fun putState(z: ZipOutputStream, s: AppState) {
        z.putNextEntry(ZipEntry("data/state.json"))
        // Escreve em fluxo (o estado pode passar de dezenas de MB) sem fechar o zip.
        val guard = object : OutputStream() {
            override fun write(b: Int) = z.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = z.write(b, off, len)
            override fun close() { /* o zip continua aberto */ }
        }
        guard.buffered(64 * 1024).use { AppJson.encodeToStream(AppState.serializer(), s, it) }
        z.closeEntry()
    }

    private fun vaultJson(ctx: Context): JsonObject = buildJsonObject {
        put("pix", buildJsonArray {
            runCatching { dev.fluwy.app.vault.PixVault(ctx).list() }.getOrDefault(emptyList()).forEach { k ->
                add(buildJsonObject { put("type", k.type); put("key", k.key); put("name", k.name); put("city", k.city); put("label", k.label) })
            }
        })
        put("cards", buildJsonArray {
            runCatching { dev.fluwy.app.vault.CardVault(ctx).list() }.getOrDefault(emptyList()).forEach { c ->
                add(buildJsonObject { put("number", c.number); put("holder", c.holder); put("expiry", c.expiry); put("cpf", c.cpf) })
            }
        })
    }

    private fun walletJson(ctx: Context): JsonObject? = runCatching {
        val vault = dev.fluwy.app.wallet.WalletSecretVault(ctx)
        if (!vault.hasSecret()) return null
        val seed = vault.read()
        val meta = ctx.getSharedPreferences("solana_wallet_metadata", Context.MODE_PRIVATE)
        buildJsonObject {
            put("seed", android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP))
            put("address", meta.getString("public_address", null).orEmpty())
            put("backupConfirmed", meta.getBoolean("backup_confirmed", false))
        }.also { seed.fill(0) }
    }.getOrNull()

    @Volatile private var pendingUri: Uri? = null

    private fun openDownload(ctx: Context, name: String): Pair<OutputStream, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fluwy")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Não consegui criar o arquivo em Downloads.")
            pendingUri = uri
            val os = ctx.contentResolver.openOutputStream(uri) ?: error("Não consegui abrir o arquivo em Downloads.")
            return os to "Downloads/Fluwy/$name"
        }
        // Android 8/9: pasta de downloads do próprio app (sem permissão de armazenamento).
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "downloads")
        dir.mkdirs()
        val f = File(dir, name)
        return f.outputStream() to f.absolutePath
    }

    private fun finishDownload(ctx: Context) {
        val uri = pendingUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    // ------------------------------------------------------------------ importar

    /** Lê o manifesto (para mostrar o que tem antes de importar). */
    suspend fun inspect(uri: Uri): Manifest = withContext(Dispatchers.IO) {
        val app = FluwyApp.instance
        app.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == "manifest.json") {
                        val m = AppJson.decodeFromString(Manifest.serializer(), z.readBytes().toString(Charsets.UTF_8))
                        require(m.format == FORMAT) { "Este arquivo não é um backup do Fluwy." }
                        require(m.version <= VERSION) { "Backup de uma versão mais nova do app. Atualize o app para importar." }
                        return@withContext m
                    }
                }
            }
        } ?: error("Não consegui abrir o arquivo.")
        throw IllegalArgumentException("Este arquivo não é um backup do Fluwy (sem manifest.json).")
    }

    private fun unzip(input: InputStream, dest: File, onEntry: (String) -> Unit = {}) {
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val rel = safeEntry(e.name) ?: continue
                val f = File(dest, rel)
                if (!f.canonicalPath.startsWith(dest.canonicalPath + File.separator)) continue
                f.parentFile?.mkdirs()
                f.outputStream().buffered().use { z.copyTo(it) }
                onEntry(rel)
            }
        }
    }

    /**
     * Importa o backup. A senha é conferida ANTES de qualquer mudança. Ao terminar, o app precisa reiniciar
     * (estado, agenda e sessões do browser recarregam do zero) — a tela de importação faz isso.
     */
    suspend fun import(uri: Uri, password: CharArray, onProgress: (Float, String) -> Unit): Report = withContext(Dispatchers.IO) {
        val app = FluwyApp.instance
        val tmp = File(app.cacheDir, "import-${System.currentTimeMillis()}").apply { mkdirs() }
        val lines = ArrayList<String>()
        try {
            onProgress(0.03f, "Lendo o arquivo")
            val outer = File(tmp, "outer").apply { mkdirs() }
            app.contentResolver.openInputStream(uri)?.use { unzip(it, outer) } ?: error("Não consegui abrir o arquivo.")
            val manifest = File(outer, "manifest.json").takeIf { it.isFile }?.let { AppJson.decodeFromString(Manifest.serializer(), it.readText()) }
                ?: error("Este arquivo não é um backup do Fluwy.")
            require(manifest.format == FORMAT) { "Este arquivo não é um backup do Fluwy." }
            require(manifest.version <= VERSION) { "Backup de uma versão mais nova do app. Atualize o app para importar." }

            onProgress(0.15f, "Conferindo a senha")
            val secureDir = File(tmp, "secure")
            val secureBin = File(outer, "secure.bin")
            if (secureBin.isFile) {
                val secureZip = File(tmp, "secure.zip")
                secureZip.outputStream().buffered().use { SecureBox.decrypt(password, secureBin, it) }
                secureZip.inputStream().use { unzip(it, secureDir.apply { mkdirs() }) }
                secureZip.delete()
            }

            onProgress(0.3f, "Restaurando agentes, sessões e configurações")
            val stateFile = File(outer, "data/state.json")
            if (stateFile.isFile) {
                val imported = stateFile.inputStream().buffered().use { AppJson.decodeFromStream(AppState.serializer(), it) }
                val secureSettings = File(secureDir, "settings.json").takeIf { it.isFile }?.let { runCatching { AppJson.decodeFromString(Settings.serializer(), it.readText()) }.getOrNull() }
                val current = app.store.state.value
                val merged = imported.copy(settings = mergeSettings(imported.settings, secureSettings, current.settings))
                app.store.update { merged }
                app.store.flush()
                lines += "${merged.agents.size} agente(s), ${merged.sessions.size} sessão(ões), ${merged.scheduled.size} agendamento(s), ${merged.skills.size} skill(s)"
            }

            onProgress(0.45f, "Restaurando gatilhos e preferências")
            (PLAIN_PREFS.map { File(outer, "data/prefs/$it.json") to it } + SECURE_PREFS.map { File(secureDir, "prefs/$it.json") to it }).forEach { (f, n) ->
                if (!f.isFile) return@forEach
                runCatching {
                    val o = AppJson.parseToJsonElement(f.readText()).jsonObject
                    val e = app.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear()
                    jsonToPrefs(o, e); e.commit()
                }
            }

            onProgress(0.55f, "Restaurando conectores")
            File(outer, "data/uiconnectors.json").takeIf { it.isFile }?.let { f ->
                app.uiConnectors.importBackup(f.readText()).onSuccess { r -> if (r.installed.isNotEmpty()) lines += "${r.installed.size} UI conector(es)" }
            }
            File(outer, "data/hub-connections.json").takeIf { it.isFile }?.let { f ->
                val list = runCatching { (AppJson.parseToJsonElement(f.readText()) as JsonArray).mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())
                    .mapNotNull { o -> (o["pieceName"] as? JsonPrimitive)?.contentOrNull?.let { it to ((o["displayName"] as? JsonPrimitive)?.contentOrNull ?: it) } }
                val n = dev.fluwy.app.connectors.HubApi.importPending(list)
                if (n > 0) lines += "$n app(s) conectado(s) listados — entre de novo em cada um (Apps › Conectores)"
            }

            onProgress(0.65f, "Restaurando o Cofre")
            File(secureDir, "vault.json").takeIf { it.isFile }?.let { f -> lines += restoreVault(app, AppJson.parseToJsonElement(f.readText()).jsonObject) }
            File(secureDir, "wallet.json").takeIf { it.isFile }?.let { f -> restoreWallet(app, AppJson.parseToJsonElement(f.readText()).jsonObject)?.let { lines += it } }

            onProgress(0.8f, "Preparando as sessões do browser")
            val web = File(secureDir, "webview"); val sessions = File(secureDir, "browser-sessions")
            if (web.isDirectory || sessions.isDirectory) {
                val stage = File(app.filesDir, STAGE_DIR).apply { deleteRecursively(); mkdirs() }
                if (web.isDirectory) web.copyRecursively(File(stage, "app_webview"), overwrite = true)
                if (sessions.isDirectory) sessions.copyRecursively(File(stage, "browser-sessions"), overwrite = true)
                File(stage, "ready").writeText(System.currentTimeMillis().toString())
                lines += "sessões de login do browser"
            }
            onProgress(1f, "Concluído")
            Report(lines)
        } finally {
            password.fill(' ')
            tmp.deleteRecursively()
        }
    }

    private fun restoreVault(ctx: Context, o: JsonObject): List<String> {
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val out = ArrayList<String>()
        runCatching {
            val pix = dev.fluwy.app.vault.PixVault(ctx)
            val have = pix.list().map { it.key }.toSet()
            var n = 0
            (o["pix"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { k ->
                if (k.s("key") in have) return@forEach
                if (pix.add(k.s("type"), k.s("key"), k.s("name"), k.s("city"), k.s("label")) == null) n++
            }
            if (n > 0) out += "$n chave(s) Pix"
        }
        runCatching {
            val cards = dev.fluwy.app.vault.CardVault(ctx)
            val have = cards.list().map { it.number.filter(Char::isDigit) }.toSet()
            var n = 0
            (o["cards"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { c ->
                if (c.s("number").filter(Char::isDigit) in have) return@forEach
                if (cards.add(c.s("number"), c.s("holder"), c.s("expiry"), c.s("cpf")) == null) n++
            }
            if (n > 0) out += "$n cartão(ões)"
        }
        return out
    }

    private fun restoreWallet(ctx: Context, o: JsonObject): String? = runCatching {
        val vault = dev.fluwy.app.wallet.WalletSecretVault(ctx)
        val address = (o["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val meta = ctx.getSharedPreferences("solana_wallet_metadata", Context.MODE_PRIVATE)
        if (vault.hasSecret()) {
            return if (meta.getString("public_address", null) == address) null else "carteira atual mantida (o aparelho já tem outra carteira)"
        }
        val seed = android.util.Base64.decode((o["seed"] as? JsonPrimitive)?.contentOrNull.orEmpty(), android.util.Base64.NO_WRAP)
        require(seed.size == 32) { "seed inválido" }
        vault.create(seed)
        seed.fill(0)
        meta.edit().putString("public_address", address).putBoolean("backup_confirmed", (o["backupConfirmed"] as? JsonPrimitive)?.booleanOrNull == true).commit()
        "carteira"
    }.getOrElse { "carteira não restaurada: ${it.message}" }

    /**
     * Chamado no início do app (antes de qualquer WebView): troca o perfil do browser pelo importado.
     */
    fun applyStagedBrowserData(ctx: Context) {
        runCatching {
            val stage = File(ctx.filesDir, STAGE_DIR)
            if (!File(stage, "ready").isFile) { if (stage.exists()) stage.deleteRecursively(); return }
            File(stage, "app_webview").takeIf { it.isDirectory }?.let { src ->
                val dst = webviewDir(ctx)
                dst.deleteRecursively()
                if (!src.renameTo(dst)) src.copyRecursively(dst, overwrite = true)
            }
            File(stage, "browser-sessions").takeIf { it.isDirectory }?.let { src ->
                val dst = browserSessionsDir(ctx)
                dst.deleteRecursively()
                if (!src.renameTo(dst)) src.copyRecursively(dst, overwrite = true)
            }
            stage.deleteRecursively()
        }
    }

    private val README = """
        Backup do Fluwy Bot.

        - manifest.json: data, versão do app e contagens.
        - data/state.json: agentes, sessões (chats), skills, agenda, seções, conhecimento, memórias e configurações (sem chaves de API).
        - data/prefs/*.json: gatilhos, permissões de sites e preferências da carteira.
        - data/uiconnectors.json e data/hub-connections.json: conectores (sem senhas nem tokens; entre de novo depois de importar).
        - secure.bin: criptografado com a sua senha (AES-256 + HMAC, PBKDF2): chaves de API, Pix, cartões, carteira e sessões de login do browser.

        Para restaurar: Configurações › Dados › Importar dados. Guarde a senha: sem ela a parte criptografada não pode ser lida.
    """.trimIndent()
}
