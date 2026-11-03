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

    /** Produz somente texto imprimível e SGR, nunca entrada de shell nem controles arbitrários. */
    fun transcriptBytes(snapshot: String): ByteArray? {
        val safe = safeAnsiTranscript(boundedTranscript(snapshot))
        if (safe.isBlank()) return null
        val replay = safe.replace("\n", "\r\n") + "\u001b[0m\r\n"
        return replay.toByteArray(StandardCharsets.UTF_8)
    }

    /** Permite apenas sequências SGR (CSI numérico terminado em m); descarta OSC, cursor e demais CSI. */
    private fun safeAnsiTranscript(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\u001b') {
                if (i + 1 < input.length && input[i + 1] == '[') {
                    var end = i + 2
                    while (end < input.length && (input[end].isDigit() || input[end] == ';')) end++
                    if (end < input.length && input[end] == 'm' && end > i + 2) {
                        val params = input.substring(i + 2, end)
                        if (params.startsWith('0')) out.append(input, i, end + 1)
                        i = end + 1
                    } else {
                        while (end < input.length && input[end] !in '@'..'~') end++
                        i = if (end < input.length) end + 1 else input.length
                    }
                } else if (i + 1 < input.length && input[i + 1] == ']') {
                    var end = i + 2
                    while (end < input.length && input[end] != '\u0007' &&
                        !(input[end] == '\u001b' && end + 1 < input.length && input[end + 1] == '\\')) end++
                    i = when {
                        end >= input.length -> input.length
                        input[end] == '\u0007' -> end + 1
                        else -> end + 2
                    }
                } else {
                    i += if (i + 1 < input.length) 2 else 1
                }
                continue
            }
            if (ch == '\n') out.append(ch)
            else if (!Character.isISOControl(ch)) out.append(ch)
            i++
        }
        return out.toString()
    }
}
