package dev.cueopen.app.skills

import java.text.Normalizer

/**
 * Regras do pacote de skill, como no OpenBot (skill-frontmatter.ts / skill-package.ts / github-skill-source.ts):
 * frontmatter com name e description (1–1024), pasta da skill = pastas que têm SKILL.md (só as de cima), arquivos
 * inseguros ficam de fora (.git, node_modules, .env*, chaves privadas, arquivos compactados, caminhos que escapam),
 * limites de 200 arquivos e 10 MB.
 */
object SkillPackage {
    const val MAX_FILES = 200
    const val MAX_BYTES = 10L * 1024 * 1024
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_SKILL_MD = 200_000
    const val DESCRIPTION_MAX = 1024

    data class Meta(val name: String, val description: String, val body: String, val problems: List<String>)

    private val fmRx = Regex("^\\uFEFF?---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---(?:\\r?\\n|$)")

    /** Lê o frontmatter YAML simples (chave: valor, inclusive valores em bloco com | ou >). */
    fun frontmatter(md: String): Map<String, String>? {
        val m = fmRx.find(md) ?: return null
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val block = StringBuilder()
        fun flush() { key?.let { out[it] = block.toString().trim().trim('"', '\'') }; key = null; block.clear() }
        for (line in m.groupValues[1].lines()) {
            val kv = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(line)
            if (kv != null && !line.startsWith(" ") && !line.startsWith("\t")) {
                flush(); key = kv.groupValues[1]
                val v = kv.groupValues[2].trim()
                if (v != "|" && v != ">" && v != "|-" && v != ">-") block.append(v)
            } else if (key != null) block.append(if (block.isEmpty()) "" else " ").append(line.trim())
        }
        flush()
        return out
    }

    fun slugify(name: String): String {
        val v = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)
        return v.ifBlank { "skill-" + Integer.toHexString(name.hashCode()) }
    }

    /** Valida o SKILL.md. [wanted] = nome pedido pelo usuário ("salve como skill chamada X"), que tem prioridade. */
    fun inspect(md: String, fallbackName: String, wanted: String? = null): Meta {
        val problems = ArrayList<String>()
        if (md.length > MAX_SKILL_MD) problems += "SKILL.md acima de ${MAX_SKILL_MD / 1000} KB"
        val fm = frontmatter(md)
        if (fm == null) problems += "SKILL.md sem frontmatter (--- name/description ---)"
        val body = fmRx.find(md)?.let { md.substring(it.range.last + 1) } ?: md
        val name = slugify(wanted?.takeIf { it.isNotBlank() } ?: fm?.get("name")?.takeIf { it.isNotBlank() } ?: fallbackName)
        var desc = fm?.get("description").orEmpty().trim()
        if (desc.isBlank()) {
            // Sem description: usa o primeiro parágrafo (avisa, mas não bloqueia — a skill ainda é útil).
            desc = body.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }.orEmpty().take(300)
            problems += "frontmatter sem description (usei o primeiro parágrafo)"
        }
        if (desc.length > DESCRIPTION_MAX) { desc = desc.take(DESCRIPTION_MAX); problems += "description acima de $DESCRIPTION_MAX caracteres (cortada)" }
        return Meta(name, desc, body.trim(), problems)
    }

    /** Arquivo que não pode vir no pacote (OpenBot isUnsafeArchivePath). */
    fun unsafePath(path: String): Boolean {
        val parts = path.split('/')
        val file = parts.last().lowercase()
        return path.isBlank() || path.startsWith("/") || parts.any { it.isEmpty() || it == "." || it == ".." } ||
            ".git" in parts || "node_modules" in parts || file.startsWith(".env") || Regex("private.*key").containsMatchIn(file) ||
            Regex("\\.(zip|tar|tgz|gz|7z|rar|apk|exe|dll|so)$").containsMatchIn(file)
    }

    /** Pastas de skill num conjunto de caminhos: as que têm SKILL.md e não estão dentro de outra pasta de skill. */
    fun skillFolders(paths: List<String>, under: String): List<String> {
        val base = under.trim('/')
        val inside = paths.filter { base.isEmpty() || it == base || it.startsWith("$base/") }.map { if (base.isEmpty()) it else it.removePrefix(base).trimStart('/') }
        val folders = inside.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }.map { it.removeSuffix("SKILL.md").trimEnd('/') }
            .sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))
        val tops = ArrayList<String>()
        for (f in folders) if (tops.none { t -> t.isEmpty() || f.startsWith("$t/") }) tops += f
        return tops.map { listOf(base, it).filter { s -> s.isNotEmpty() }.joinToString("/") }
    }

    /** Avisos sobre o conteúdo (o usuário pediu a instalação, mas comandos perigosos merecem atenção antes de rodar). */
    fun warnings(text: String): List<String> = buildList {
        if (Regex("curl[^\\n|]*\\|\\s*(ba)?sh|wget[^\\n|]*\\|\\s*(ba)?sh").containsMatchIn(text)) add("baixa e executa script da internet (curl | sh)")
        if (Regex("rm\\s+-rf\\s+[/~]").containsMatchIn(text)) add("apaga diretórios (rm -rf)")
        if (Regex("(?i)(api[_-]?key|token|password|senha)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-]{16,}").containsMatchIn(text)) add("contém o que parece ser uma credencial")
    }

    /** Links relativos citados no SKILL.md (scripts/, references/, templates…) para baixar junto quando a skill vem de uma URL solta. */
    fun relativeRefs(md: String): List<String> {
        val rx = Regex("(?:\\]\\(|`|\\s)((?:\\./)?(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\\.(?:md|py|js|ts|sh|json|yaml|yml|html|css|txt|csv|sql|toml))(?=[)`\\s.,;:]|$)")
        return rx.findAll(md).map { it.groupValues[1].removePrefix("./") }
            .filter { ref -> ref != "SKILL.md" && !ref.contains("://") && !unsafePath(ref) && ('/' in ref || ref.substringAfterLast('.') in setOf("py", "sh", "js", "ts")) }
            .distinct().take(30).toList()
    }
}
