package dev.cueopen.app.agent

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

    /** Reads captured stdout/stderr without disguising a missing capture file as a silent command. */
    fun readCapturedFile(file: File): String {
        if (!file.isFile) return "Erro: o arquivo de captura de stdout/stderr não foi criado ou ficou inacessível no sandbox."
        return runCatching { file.readText().takeLast(12_000) }
            .getOrElse { "Erro ao ler a captura de stdout/stderr: ${it.message ?: "falha de leitura"}" }
    }

    /** Formats output together with an unambiguous process result for the agent. */
    fun formatExecutionResult(raw: String, exitCode: Int, timedOut: Boolean): String {
        val body = compact(raw).takeLast(10_000)
        return buildString {
            if (body.isBlank()) {
                append(when {
                    timedOut -> "(stdout/stderr vazios antes do timeout)"
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
