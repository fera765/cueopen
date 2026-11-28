package dev.fluwy.app.pages

/**
 * Troca de trecho tolerante (como o Edit do Claude Code, mas perdoando espaços e maiúsculas): exato → espaços
 * normalizados → sem diferenciar maiúsculas. Exige um único lugar; se falhar, mostra o trecho mais parecido para o
 * agente corrigir na próxima tentativa em vez de adivinhar.
 */
object TextPatch {
    fun replace(source: String, find: String, replacement: String): Result<String> {
        if (find.isBlank()) return Result.failure(IllegalArgumentException("informe find"))
        val exact = count(source, find)
        if (exact == 1) return Result.success(source.replace(find, replacement))
        if (exact > 1) return Result.failure(IllegalArgumentException("o trecho aparece $exact vezes; inclua mais texto ao redor para ficar único"))
        val tokens = find.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Result.failure(IllegalArgumentException("informe find"))
        for (opts in listOf(emptySet(), setOf(RegexOption.IGNORE_CASE))) {
            val rx = Regex(tokens.joinToString("\\s+") { Regex.escape(it) }, opts)
            val ms = rx.findAll(source).toList()
            if (ms.size == 1) return Result.success(source.replaceRange(ms[0].range, replacement))
            if (ms.size > 1) return Result.failure(IllegalArgumentException("o trecho aparece ${ms.size} vezes; inclua mais texto ao redor"))
        }
        return Result.failure(IllegalArgumentException("trecho não encontrado." + hint(source, find)))
    }

    private fun count(s: String, f: String): Int { var n = 0; var i = s.indexOf(f); while (i >= 0) { n++; i = s.indexOf(f, i + f.length) }; return n }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    /** Linha mais parecida com o trecho procurado (para o agente copiar o texto certo). */
    fun hint(source: String, find: String): String {
        val fw = words(find)
        if (fw.isEmpty()) return ""
        val best = source.lines().map { it to words(it).intersect(fw).size }.filter { it.second > 0 }.maxByOrNull { it.second } ?: return " Leia a página com page_read e copie o trecho exato."
        return " Trecho mais parecido na página: \"" + best.first.trim().take(220) + "\". Copie exatamente do page_read."
    }

    /** Substitui a seção de um título markdown (até o próximo título do mesmo nível ou acima). */
    fun replaceSection(md: String, heading: String, newBody: String): Result<String> {
        val lines = md.lines()
        val target = heading.trim().trimStart('#').trim().lowercase()
        val idx = lines.indexOfFirst { l -> l.trimStart().startsWith("#") && l.trim().trimStart('#').trim().lowercase() == target }
            .takeIf { it >= 0 } ?: lines.indexOfFirst { l -> l.trimStart().startsWith("#") && l.trim().trimStart('#').trim().lowercase().contains(target) }
        if (idx < 0) return Result.failure(IllegalArgumentException("título \"$heading\" não encontrado. Títulos da página: " +
            lines.filter { it.trimStart().startsWith("#") }.take(15).joinToString(" | ") { it.trim() }))
        val level = lines[idx].trimStart().takeWhile { it == '#' }.length
        var end = idx + 1
        while (end < lines.size && !(lines[end].trimStart().startsWith("#") && lines[end].trimStart().takeWhile { it == '#' }.length <= level)) end++
        val body = newBody.trim().let { b -> if (b.trimStart().startsWith("#")) b else lines[idx] + "\n" + b }
        return Result.success((lines.subList(0, idx) + body.lines() + listOf("") + lines.subList(end, lines.size)).joinToString("\n").trimEnd() + "\n")
    }
}
