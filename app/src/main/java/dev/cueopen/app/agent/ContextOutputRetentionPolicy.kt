package dev.cueopen.app.agent

import java.io.File

/** Mantém arquivos ainda referenciados pelo histórico e limita apenas o acervo sem referências. */
internal object ContextOutputRetentionPolicy {
    private val archiveName = Regex("o[0-9a-f]+_\\d+\\.txt")

    fun filesToDelete(
        files: Iterable<File>,
        referencedNames: Set<String>,
        keepUnreferenced: Int = 120,
        now: Long = System.currentTimeMillis(),
        minAgeMs: Long = 12 * 3_600_000L,
    ): List<File> {
        require(keepUnreferenced >= 0)
        // Ponteiros do microCompact só existem no prompt (não no histórico salvo): um arquivo recente pode estar
        // citado ali. Nada com menos de [minAgeMs] sai — antes o agente recebia o ponteiro e o arquivo já tinha sumido.
        return files.asSequence()
            .filter { it.isFile && archiveName.matches(it.name) && it.name !in referencedNames && now - it.lastModified() > minAgeMs }
            .sortedByDescending { it.lastModified() }
            .drop(keepUnreferenced)
            .toList()
    }
}
