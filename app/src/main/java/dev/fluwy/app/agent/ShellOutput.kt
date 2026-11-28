package dev.fluwy.app.agent

import java.io.File

/**
 * Limpa a saída do terminal ANTES de ela ir para o modelo/log (Kotlin puro — testável na JVM):
 *  - barras de progresso com `\r` (curl, pip, wget, yt-dlp) viram só o último estado da linha;
 *  - linhas quase iguais repetidas em sequência (ex.: centenas de "find: …: Permission denied")
 *    são resumidas, mantendo as primeiras e um contador.
 * Isso evitava que uma única chamada ocupasse todo o contexto com ruído e escondesse o erro real.
 */
object ShellOutput {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val DIGITS = Regex("[0-9]+")

    fun compact(raw: String, keepSimilar: Int = 3): String {
        if (raw.isEmpty()) return raw
        val lines = raw.replace("\r\n", "\n").split('\n').map { line ->
            // \r reescreve a linha: o terminal mostraria só o último pedaço
            val last = line.split('\r').lastOrNull { it.isNotBlank() } ?: ""
            ANSI.replace(last, "")
        }
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val key = shape(lines[i])
            var j = i + 1
            while (j < lines.size && key.isNotEmpty() && shape(lines[j]) == key) j++
            val run = j - i
            if (run > keepSimilar + 1) {
                for (k in i until i + keepSimilar) out.add(lines[k])
                out.add("… (+${run - keepSimilar} linhas parecidas omitidas)")
            } else for (k in i until j) out.add(lines[k])
            i = j
        }
        return out.joinToString("\n")
    }

    /** Limite de caracteres da saída que volta ao modelo; o excedente fica num arquivo consultável. */
    const val INLINE_LIMIT = 12_000
    private const val HEAD_CHARS = 2_000
    private const val KEEP_FULL_LOGS = 20

    /**
     * Lê o stdout/stderr capturado sem disfarçar captura ausente como comando silencioso.
     * Saída longa volta como início + fim (onde costumam estar o comando e o erro) e o arquivo
     * completo é preservado em [keepAs], citado pelo caminho [guestPath] para o agente usar grep/file_read.
     */
    fun readCapturedFile(file: File, keepAs: File? = null, guestPath: String? = null): String {
        if (!file.isFile) return "Erro: o arquivo de captura de stdout/stderr não foi criado ou ficou inacessível no sandbox."
        return runCatching {
            val text = file.readText()
            if (text.length <= INLINE_LIMIT) return@runCatching text
            val kept = keepAs?.let { dest ->
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                dest.parentFile?.listFiles { f -> f.name.startsWith("cmd_") }?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP_FULL_LOGS)?.forEach { it.delete() }
                true
            } == true
            headTail(text, INLINE_LIMIT, if (kept && guestPath != null) guestPath else null)
        }.getOrElse { "Erro ao ler a captura de stdout/stderr: ${it.message ?: "falha de leitura"}" }
    }

    /** Mantém o início e o fim de [text] dentro de [limit] caracteres, com um aviso do que foi omitido. */
    fun headTail(text: String, limit: Int, fullPath: String? = null): String {
        if (text.length <= limit) return text
        val tail = limit - HEAD_CHARS
        val omitted = text.length - HEAD_CHARS - tail
        val where = if (fullPath != null) " Saída completa em $fullPath (use grep -n ou file_read com offset para ler o trecho)." else ""
        return text.take(HEAD_CHARS) + "\n… [$omitted caracteres omitidos.$where]\n" + text.takeLast(tail)
    }

    /** Formats output together with an unambiguous process result for the agent. */
    fun formatExecutionResult(raw: String, exitCode: Int, timedOut: Boolean): String {
        val body = compact(raw).let { if (it.contains(" caracteres omitidos.")) it else headTail(it, INLINE_LIMIT) }
        return buildString {
            if (body.isBlank()) {
                append(when {
                    timedOut -> "(stdout/stderr vazios antes do timeout)"
                    exitCode == 1 -> "(stdout/stderr capturados vazios; exit 1 — em grep/find/test isso significa \"nada encontrado\", não erro)"
                    exitCode != 0 -> "(falha sem mensagem: stdout/stderr capturados vazios)"
                    else -> "(comando concluído sem saída stdout/stderr)"
                })
            } else append(body)
            if (timedOut) append("\n[timeout]")
            append("\n[exit $exitCode]")
        }
    }

    /** "Formato" da linha: sem números e sem o último segmento de caminho — agrupa linhas repetitivas. */
    private fun shape(line: String): String {
        val t = line.trim()
        if (t.length < 8) return ""
        val noNum = DIGITS.replace(t, "#")
        // "find: /vendor/lib/a@1.0.so: Permission denied" ≈ "find: /vendor/lib/b@2.0.so: Permission denied"
        return noNum.replace(Regex("/[^\\s:]*"), "/…").take(120)
    }
}
