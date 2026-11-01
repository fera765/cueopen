package dev.cueopen.app.browser

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Perfil e arquivos de restauração determinísticos, sempre derivados da identidade do chat. */
internal object BrowserProfileIdentity {
    fun profileName(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "fluwy_chat_${hex.take(32)}"
    }
}

internal data class BrowserSavedState(
    val url: String = "",
    val desktop: Boolean = false,
    val darkPages: Boolean = false,
)

/** Dados locais, privados e isolados por chat. Bundle é best-effort; URL serve como fallback. */
internal class BrowserSessionPersistence(context: Context, sessionId: String) {
    val profileName: String = BrowserProfileIdentity.profileName(sessionId)
    private val directory = File(context.filesDir, "browser-sessions").apply { mkdirs() }
    private val metadataFile = File(directory, "$profileName.json")
    private val webViewStateFile = File(directory, "$profileName.webview")

    fun load(): BrowserSavedState = runCatching {
        val json = JSONObject(metadataFile.readText())
        BrowserSavedState(
            url = json.optString("url"),
            desktop = json.optBoolean("desktop", false),
            darkPages = json.optBoolean("darkPages", false),
        )
    }.getOrDefault(BrowserSavedState())

    fun saveMetadata(state: BrowserSavedState) {
        val json = JSONObject()
            .put("version", 1)
            .put("url", state.url)
            .put("desktop", state.desktop)
            .put("darkPages", state.darkPages)
            .toString()
        writeAtomically(metadataFile, json.toByteArray(Charsets.UTF_8))
    }

    fun saveWebViewState(bundle: Bundle) {
        val parcel = Parcel.obtain()
        val bytes = try {
            bundle.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        writeAtomically(webViewStateFile, bytes)
    }

    fun loadWebViewState(): Bundle? = runCatching {
        if (!webViewStateFile.isFile || webViewStateFile.length() !in 1..8_388_608) return null
        val bytes = webViewStateFile.readBytes()
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

    fun clearFiles() {
        metadataFile.delete()
        webViewStateFile.delete()
        File(metadataFile.path + ".bak").delete()
        File(webViewStateFile.path + ".bak").delete()
    }

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
}
