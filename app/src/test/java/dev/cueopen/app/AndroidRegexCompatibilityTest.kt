package dev.cueopen.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O Android compila regex com ICU, mais rígido que o java.util.regex da JVM: um `}` sem escape fora de um
 * quantificador é aceito nos testes JVM mas lança no aparelho. Num `val` de object isso vira
 * ExceptionInInitializerError e derruba a classe inteira (foi o que quebrou a compactação).
 * Este teste varre TODO `Regex("…")` do código e reprova esses padrões antes de chegarem ao APK.
 */
class AndroidRegexCompatibilityTest {
    private val literal = Regex("Regex\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")")

    private fun decode(raw: String, triple: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '$' && raw.getOrNull(i + 1) == '{') {
                var depth = 0; var j = i + 1
                while (j < raw.length) {
                    if (raw[j] == '{') depth++ else if (raw[j] == '}') { depth--; if (depth == 0) break }
                    j++
                }
                out.append(if (raw.substring(i, minOf(j + 1, raw.length)) == "\${'$'}") "$" else "X")
                i = j + 1; continue
            }
            if (!triple && c == '\\' && i + 1 < raw.length) {
                out.append(when (val n = raw[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; else -> n })
                i += 2; continue
            }
            out.append(c); i++
        }
        return out.toString()
    }

    /** Posição de uma chave que o ICU rejeita, ou null. */
    private fun icuBraceProblem(rx: String): Int? {
        var i = 0
        while (i < rx.length) {
            val c = rx[i]
            when {
                c == '\\' -> {
                    if (rx.getOrNull(i + 1) in setOf('p', 'P') && rx.getOrNull(i + 2) == '{') i = rx.indexOf('}', i).let { if (it < 0) rx.length else it + 1 }
                    else i += 2
                }
                c == '[' -> {
                    i++
                    if (rx.getOrNull(i) == '^') i++
                    if (rx.getOrNull(i) == ']') i++
                    var depth = 1
                    while (i < rx.length && depth > 0) {
                        when (rx[i]) { '\\' -> { i += 2; continue }; '[' -> depth++; ']' -> depth-- }
                        i++
                    }
                }
                c == '{' -> {
                    val m = Regex("^\\{\\d+(,\\d*)?\\}").find(rx.substring(i)) ?: return i
                    i += m.value.length
                }
                c == '}' -> return i
                else -> i++
            }
        }
        return null
    }

    @Test fun `scanner catches the patterns that crashed on Android`() {
        assertTrue(icuBraceProblem("\\{\\{secret:[a-z][a-z0-9_]{0,31}}}") != null)
        assertTrue(icuBraceProblem("\\{\\{\\s*X(?:\\s*\\||\\s*}})") != null)
        assertTrue(icuBraceProblem("\\{\\{secret:[a-z][a-z0-9_]{0,31}\\}\\}") == null)
        assertTrue(icuBraceProblem("[^\\p{L}\\p{N}]+\\d{10,20}[{}]") == null)
    }

    @Test fun `every regex literal in the app compiles on Android ICU`() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java")).filter { it.isDirectory }
        assertTrue("fonte não encontrada", roots.isNotEmpty())
        val problems = mutableListOf<String>()
        var scanned = 0
        roots.first().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            literal.findAll(src).forEach { m ->
                val triple = m.groups[1] != null
                val rx = decode(m.groups[1]?.value ?: m.groups[2]?.value.orEmpty(), triple)
                scanned++
                icuBraceProblem(rx)?.let { pos ->
                    val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                    problems += "${f.path}:$line → \"$rx\" (chave sem escape na posição $pos; use \\\\} )"
                }
            }
        }
        assertTrue("nenhum Regex encontrado — o scanner quebrou", scanned > 50)
        assertTrue("Regex incompatíveis com o Android (ICU):\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun `compaction identifiers load and work`() {
        // Força o init da classe: se algum padrão não compilar, cai aqui e não no aparelho.
        val ids = dev.cueopen.app.agent.CompactionIdentifiers.extract("{{secret:senha}} https://x.com/a /root/workspace/a.md")
        assertTrue(ids.containsAll(listOf("{{secret:senha}}", "https://x.com/a", "/root/workspace/a.md")))
    }
}
