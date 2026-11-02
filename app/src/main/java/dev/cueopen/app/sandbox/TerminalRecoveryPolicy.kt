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

    /** Limita o histórico sem começar no meio de um comando/linha. */
    fun boundedTranscript(snapshot: String): String {
        val normalized = snapshot.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.length <= MAX_TRANSCRIPT_CHARS) return normalized.trimEnd('\n')
        val boundary = normalized.indexOf('\n', normalized.length - MAX_TRANSCRIPT_CHARS)
        if (boundary < 0) return ""
        return normalized.substring(boundary + 1).trimEnd('\n')
    }

    /** Produz texto simples para o stdout da nova shell, nunca como entrada de comandos. */
    fun transcriptBytes(snapshot: String): ByteArray? {
        val bounded = boundedTranscript(snapshot)
        if (bounded.isBlank()) return null
        val replay = bounded.replace("\n", "\r\n") + "\r\n"
        return replay.toByteArray(StandardCharsets.UTF_8)
    }
}
