package dev.cueopen.app.agent

import java.io.File

/** Mantém arquivos ainda referenciados pelo histórico e limita apenas o acervo sem referências. */
internal object ContextOutputRetentionPolicy {
    private val archiveName = Regex("o[0-9a-f]+_\\d+\\.txt")

    fun filesToDelete(
        files: Iterable<File>,
        referencedNames: Set<String>,
        keepUnreferenced: Int = 40,
    ): List<File> {
        require(keepUnreferenced >= 0)
        return files.asSequence()
            .filter { it.isFile && archiveName.matches(it.name) && it.name !in referencedNames }
            .sortedByDescending { it.lastModified() }
            .drop(keepUnreferenced)
            .toList()
    }
}
