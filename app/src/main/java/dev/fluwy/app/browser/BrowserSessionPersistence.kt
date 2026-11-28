package dev.fluwy.app.browser

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Perfil e arquivos de restauração determinísticos, sempre derivados da identidade do chat. */
internal object BrowserProfileIdentity {
    /**
     * Perfil ÚNICO e persistente de contas: o usuário faz login uma vez (Instagram, Mercado Livre…)
     * e todos os chats e Agents — inclusive ciclos autônomos e tarefas agendadas — usam a mesma sessão.
     * Apagar um chat não desloga; para sair use "Sair de todas as contas" no menu do browser.
     */
    const val SHARED_PROFILE = "fluwy_accounts"

    fun profileName(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "fluwy_chat_${hex.take(32)}"
    }
}

internal object BrowserTabPersistenceIdentity {
    fun stateKey(tabId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tabId.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

internal data class BrowserSavedTab(
    val id: String,
    val url: String = "",
    val title: String = "",
)

internal data class BrowserSavedState(
    val url: String = "",
    val desktop: Boolean = false,
    val darkPages: Boolean = false,
    val tabs: List<BrowserSavedTab> = emptyList(),
    val selectedTabId: String = "",
)

/** Dados locais, privados e isolados por chat; cada aba guarda histórico e Bundle próprios. */
internal class BrowserSessionPersistence(context: Context, sessionId: String) {
    val profileName: String = BrowserProfileIdentity.profileName(sessionId)
    private val directory = File(context.filesDir, "browser-sessions").apply { mkdirs() }
    private val metadataFile = File(directory, "$profileName.json")
    private val legacyWebViewStateFile = File(directory, "$profileName.webview")

    fun load(): BrowserSavedState = runCatching {
        val json = JSONObject(metadataFile.readText())
        val savedTabs = json.optJSONArray("tabs")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
                    ?: return@mapNotNull null
                BrowserSavedTab(id, item.optString("url"), item.optString("title"))
            }.distinctBy { it.id }.take(MAX_TABS)
        }.orEmpty()
        val tabs = savedTabs.ifEmpty { listOf(BrowserSavedTab(LEGACY_TAB_ID, json.optString("url"))) }
        val activeId = json.optString("selectedTabId").takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        BrowserSavedState(
            url = json.optString("url"),
            desktop = json.optBoolean("desktop", false),
            darkPages = json.optBoolean("darkPages", false),
            tabs = tabs,
            selectedTabId = activeId,
        )
    }.getOrDefault(BrowserSavedState(tabs = listOf(BrowserSavedTab(LEGACY_TAB_ID)), selectedTabId = LEGACY_TAB_ID))

    fun saveMetadata(state: BrowserSavedState) {
        val tabs = state.tabs.take(MAX_TABS)
        val activeId = state.selectedTabId.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id.orEmpty()
        val json = JSONObject()
            .put("version", 2)
            .put("url", state.url)
            .put("desktop", state.desktop)
            .put("darkPages", state.darkPages)
            .put("selectedTabId", activeId)
            .put("tabs", JSONArray().also { array ->
                tabs.forEach { tab -> array.put(JSONObject().put("id", tab.id).put("url", tab.url).put("title", tab.title)) }
            })
            .toString()
        writeAtomically(metadataFile, json.toByteArray(Charsets.UTF_8))
    }

    fun saveWebViewState(bundle: Bundle) = saveWebViewState(LEGACY_TAB_ID, bundle)

    fun saveWebViewState(tabId: String, bundle: Bundle) {
        val parcel = Parcel.obtain()
        val bytes = try {
            bundle.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        writeAtomically(webViewStateFile(tabId), bytes)
    }

    fun loadWebViewState(): Bundle? = loadWebViewState(LEGACY_TAB_ID)

    fun loadWebViewState(tabId: String): Bundle? = runCatching {
        val stateFile = webViewStateFile(tabId).takeIf { it.isFile } ?:
            if (tabId == LEGACY_TAB_ID) legacyWebViewStateFile else return null
        if (stateFile.length() !in 1..MAX_BUNDLE_BYTES) return null
        val bytes = stateFile.readBytes()
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel).apply {
                classLoader = BrowserSessionPersistence::class.java.classLoader
            }
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    fun clearTabData(tabId: String) {
        val stateFile = webViewStateFile(tabId)
        stateFile.delete()
        File(stateFile.path + ".bak").delete()
        if (tabId == LEGACY_TAB_ID) {
            legacyWebViewStateFile.delete()
            File(legacyWebViewStateFile.path + ".bak").delete()
        }
    }

    fun clearFiles() {
        metadataFile.delete()
        legacyWebViewStateFile.delete()
        File(metadataFile.path + ".bak").delete()
        File(legacyWebViewStateFile.path + ".bak").delete()
        directory.listFiles()?.filter { it.name.startsWith("$profileName.tab.") }?.forEach { file ->
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    private fun webViewStateFile(tabId: String): File =
        File(directory, "$profileName.tab.${BrowserTabPersistenceIdentity.stateKey(tabId)}.webview")

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    companion object {
        const val LEGACY_TAB_ID = "tab-1"
        const val MAX_TABS = 12
        private const val MAX_BUNDLE_BYTES = 8_388_608L
    }
}
