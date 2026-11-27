package dev.fluwy.app.files

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Correspondência literal primeiro; fallback restrito a diferenças de whitespace, sem fuzzy edits. */
object TextReplace {
    const val MAX_MATCH_CHARS = 128 * 1024
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    data class Result(
        val updatedText: String?,
        val replacements: Int,
        val strategy: String,
        val error: String? = null,
    ) {
        val success: Boolean get() = updatedText != null && error == null
    }

    private data class Span(val start: Int, val end: Int)

    fun apply(source: String, oldText: String, newText: String, allowMultiple: Boolean = false): Result {
        if (oldText.isEmpty() || oldText.isBlank()) return Result(null, 0, "", "old_text não pode ser vazio ou só espaços.")
        if (oldText.length > MAX_MATCH_CHARS) return Result(null, 0, "", "old_text excede o limite de $MAX_MATCH_CHARS caracteres.")
        if (newText.length > MAX_FILE_BYTES) return Result(null, 0, "", "new_text excede o limite de 8 MB.")
        if (source.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return Result(null, 0, "", "arquivo excede o limite de 8 MB.")
        if ('\u0000' in source) return Result(null, 0, "", "arquivo parece binário; a edição textual foi recusada.")

        val exact = literalMatches(source, oldText)
        if (exact.isNotEmpty()) {
            val exactReplacement = normalizeLines(newText).replace("\n", preferredLineEnding(source))
            return applyMatches(source, exact, exactReplacement, allowMultiple, "exact")
        }

        val sourceNormalized = normalizeLines(source)
        val searchNormalized = normalizeLines(oldText).trim()
        val tokens = searchNormalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result(null, 0, "", "old_text não contém texto pesquisável.")
        val pattern = runCatching { Regex(tokens.joinToString("\\s*") { Regex.escape(it) }) }.getOrNull()
            ?: return Result(null, 0, "", "não foi possível preparar a busca literal.")
        val flexible = pattern.findAll(sourceNormalized).map { Span(it.range.first, it.range.last + 1) }.toList()
        if (flexible.isEmpty()) return Result(null, 0, "", "trecho não encontrado. O arquivo não foi alterado; releia o trecho e tente novamente.")

        val replacementNormalized = normalizeLines(newText)
        val planned = applyMatches(sourceNormalized, flexible, replacementNormalized, allowMultiple, "whitespace")
        if (!planned.success) return planned
        val lineEnding = preferredLineEnding(source)
        val restored = if (lineEnding == "\n") planned.updatedText!! else planned.updatedText!!.replace("\n", lineEnding)
        return planned.copy(updatedText = restored)
    }

    private fun preferredLineEnding(source: String): String = when {
        "\r\n" in source -> "\r\n"
        '\r' in source && '\n' !in source -> "\r"
        else -> "\n"
    }

    private fun literalMatches(source: String, search: String): List<Span> {
        val matches = ArrayList<Span>()
        var from = 0
        while (from <= source.length - search.length) {
            val at = source.indexOf(search, from)
            if (at < 0) break
            matches += Span(at, at + search.length)
            from = at + search.length
        }
        return matches
    }

    private fun applyMatches(
        source: String,
        matches: List<Span>,
        replacement: String,
        allowMultiple: Boolean,
        strategy: String,
    ): Result {
        if (!allowMultiple && matches.size != 1) {
            return Result(null, matches.size, strategy, "foram encontradas ${matches.size} correspondências; nenhuma alteração foi feita. Refine old_text ou habilite allow_multiple intencionalmente.")
        }
        val result = StringBuilder(source)
        for (span in matches.asReversed()) {
            val inserted = if (strategy == "whitespace") {
                val lineStart = if (span.start == 0) 0 else source.lastIndexOf('\n', span.start - 1) + 1
                val prefix = source.substring(lineStart, span.start)
                val indent = prefix.takeIf { it.all { ch -> ch == ' ' || ch == '\t' } }.orEmpty()
                replacement.split('\n').mapIndexed { index, line ->
                    if (index > 0 && line.isNotEmpty()) indent + line else line
                }.joinToString("\n")
            } else replacement
            result.replace(span.start, span.end, inserted)
        }
        val updated = result.toString()
        if (updated == source) return Result(null, matches.size, strategy, "old_text e new_text produzem o mesmo conteúdo; nada foi alterado.")
        return Result(updated, matches.size, strategy)
    }

    private fun normalizeLines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}

/** Atualização read-modify-write serializada e publicada por rename atômico no mesmo diretório. */
object AtomicTextFile {
    data class Result(val success: Boolean, val replacements: Int = 0, val strategy: String = "", val error: String? = null)

    private val pathLocks = ConcurrentHashMap<String, Any>()

    fun replace(file: File, oldText: String, newText: String, allowMultiple: Boolean = false): Result {
        val canonical = runCatching { file.canonicalFile }.getOrElse {
            return Result(false, error = "caminho de arquivo inválido: ${it.message ?: "erro"}")
        }
        val lock = pathLocks.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            try {
                if (!canonical.isFile) return@synchronized Result(false, error = "arquivo não existe ou não é um arquivo regular.")
                if (canonical.length() > TextReplace.MAX_FILE_BYTES) return@synchronized Result(false, error = "arquivo excede o limite de 8 MB.")
                val original = canonical.readText(Charsets.UTF_8)
                val plan = TextReplace.apply(original, oldText, newText, allowMultiple)
                if (!plan.success) return@synchronized Result(false, plan.replacements, plan.strategy, plan.error)

                val parent = canonical.parentFile ?: return@synchronized Result(false, error = "arquivo sem diretório pai.")
                val originalPermissions = runCatching { Files.getPosixFilePermissions(canonical.toPath()) }.getOrNull()
                val temporary = Files.createTempFile(parent.toPath(), ".${canonical.name.take(32)}-", ".tmp").toFile()
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(plan.updatedText!!.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    if (originalPermissions != null) Files.setPosixFilePermissions(temporary.toPath(), originalPermissions)
                    Files.move(
                        temporary.toPath(), canonical.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    temporary.delete()
                }
                Result(true, plan.replacements, plan.strategy)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Result(false, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
