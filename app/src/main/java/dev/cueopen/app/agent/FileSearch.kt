package dev.cueopen.app.agent

import java.io.File

/** Busca de texto nos arquivos do workspace (o "grep" dos agentes): acha a linha certa sem ler arquivos inteiros. */
object FileSearch {
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    fun search(root: File, start: File, pattern: String, regex: Boolean, max: Int = 50): String {
        if (pattern.isBlank()) return "ERRO: informe pattern."
        val rx = runCatching { if (regex) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
            .getOrElse { return "ERRO: regex inválida (${it.message?.take(80)}). Tente regex=false." }
        val files = if (start.isFile) sequenceOf(start) else start.walkTopDown()
            .onEnter { d -> d == start || !(d.name.startsWith(".") && d.name != ".cue") && d.name != "node_modules" }
            .filter { it.isFile && it.length() in 1..MAX_FILE_BYTES && !(it.path.contains("/.cue/") && !it.path.contains("/.cue/out/")) }
        val hits = ArrayList<String>()
        var filesWithHits = 0
        var total = 0
        for (f in files) {
            if (isBinary(f)) continue
            var any = false
            f.bufferedReader().useLines { lines ->
                lines.forEachIndexed { i, line ->
                    if (rx.containsMatchIn(line)) {
                        total++; any = true
                        if (hits.size < max) hits.add("${f.relativeTo(root).path}:${i + 1}: ${line.trim().take(220)}")
                    }
                }
            }
            if (any) filesWithHits++
        }
        if (hits.isEmpty()) return "Nenhuma ocorrência de \"$pattern\"."
        return hits.joinToString("\n") + if (total > hits.size) "\n[… $total ocorrências em $filesWithHits arquivo(s); mostrando $max. Refine o padrão ou o caminho.]"
            else "\n[$total ocorrência(s) em $filesWithHits arquivo(s). Leia o trecho com file_read(path, offset=linha-20, limit=60).]"
    }

    private fun isBinary(f: File): Boolean = f.inputStream().use { s ->
        val buf = ByteArray(1024); val n = s.read(buf); (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
    }
}
