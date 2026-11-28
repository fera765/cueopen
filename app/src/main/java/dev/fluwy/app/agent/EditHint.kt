package dev.fluwy.app.agent

/**
 * file_edit falhou porque o trecho não existe: em vez de só "não encontrado" (o agente repetia a mesma
 * edição), mostra as linhas REAIS do arquivo mais parecidas com o começo do trecho procurado. Puro.
 */
object EditHint {
    fun nearest(fileText: String, oldText: String, context: Int = 3): String {
        val lines = fileText.lines()
        val probes = oldText.lines().map { it.trim() }.filter { it.length >= 4 }.take(3)
        if (lines.isEmpty() || probes.isEmpty()) return ""
        var best = -1; var bestScore = 0.0
        for (probe in probes) {
            val words = probe.split(Regex("\\W+")).filter { it.length >= 3 }.toSet()
            lines.forEachIndexed { i, l ->
                val t = l.trim()
                val score = when {
                    t.isEmpty() -> 0.0
                    t == probe -> 3.0
                    t.contains(probe) || probe.contains(t) && t.length >= 8 -> 2.0
                    words.isEmpty() -> 0.0
                    else -> t.split(Regex("\\W+")).filter { it.length >= 3 }.toSet().let { w -> w.intersect(words).size.toDouble() / (w.union(words).size.coerceAtLeast(1)) }
                }
                if (score > bestScore) { bestScore = score; best = i }
            }
            if (bestScore >= 2.0) break
        }
        if (best < 0 || bestScore < 0.4) return ""
        val from = (best - context).coerceAtLeast(0); val to = (best + context).coerceAtMost(lines.size - 1)
        return "Trecho mais parecido no arquivo (linhas ${from + 1}-${to + 1}) — copie o texto EXATO daqui:\n" +
            (from..to).joinToString("\n") { "${it + 1}| ${lines[it].take(200)}" }
    }
}
