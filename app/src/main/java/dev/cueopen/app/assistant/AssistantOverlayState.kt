package dev.cueopen.app.assistant

import android.content.Context
import dev.cueopen.app.data.AppJson
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class AssistantOverlayState(
    val requestId: String = "",
    val sessionId: String = "",
    val targetLabel: String = "",
    val transcript: String = "",
    val phase: String = "idle",
    val detail: String = "",
    val result: String = "",
    val screenshotPath: String? = null,
    val startedAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Arquivo curto e substituído atomicamente; o processo :assistant só lê este arquivo. */
object AssistantOverlayStateStore {
    private const val FILE_NAME = "assistant_overlay_state.json"

    fun read(context: Context): AssistantOverlayState = runCatching {
        AppJson.decodeFromString(AssistantOverlayState.serializer(), file(context).readText())
    }.getOrDefault(AssistantOverlayState())

    fun write(context: Context, value: AssistantOverlayState) {
        val destination = file(context)
        val temporary = File(destination.parentFile, "$FILE_NAME.tmp")
        runCatching {
            temporary.writeText(AppJson.encodeToString(AssistantOverlayState.serializer(), value.copy(updatedAt = System.currentTimeMillis())))
            runCatching {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { temporary.delete() }
    }

    /** Atualizações antigas não podem substituir o estado de uma invocação mais recente. */
    fun updateIfCurrent(context: Context, requestId: String, transform: (AssistantOverlayState) -> AssistantOverlayState) {
        val current = read(context)
        if (current.requestId == requestId) write(context, transform(current))
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
