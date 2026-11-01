package dev.cueopen.app.sandbox

import java.io.File
import java.nio.charset.StandardCharsets

/** Reconstitui somente o que pode ser reconstruído com segurança depois que o processo Android morreu. */
internal object TerminalRecoveryPolicy {
    const val MAX_TRANSCRIPT_CHARS = 60_000

    fun safeWorkingDirectory(
        savedPath: String?,
        fallback: String,
        sessionRoot: File,
        resolveGuestPath: (String) -> File,
    ): String {
        val candidate = savedPath?.trim().orEmpty()
        if (candidate != "/root" && !candidate.startsWith("/root/")) return fallback
        val root = runCatching { sessionRoot.canonicalFile }.getOrNull() ?: return fallback
        val target = runCatching { resolveGuestPath(candidate).canonicalFile }.getOrNull() ?: return fallback
        val insideSession = target == root || target.path.startsWith(root.path + File.separator)
        return if (insideSession && target.isDirectory) candidate else fallback
    }

    /** Escreve o histórico como texto diretamente no emulador; não passa pelo stdin do shell. */
    fun transcriptBytes(snapshot: String): ByteArray? {
        val bounded = snapshot.takeLast(MAX_TRANSCRIPT_CHARS).trimEnd('\r', '\n')
        if (bounded.isBlank()) return null
        val replay = bounded.replace("\r\n", "\n").replace('\r', '\n')
            .replace("\n", "\r\n") + "\r\n"
        return replay.toByteArray(StandardCharsets.UTF_8)
    }
}
