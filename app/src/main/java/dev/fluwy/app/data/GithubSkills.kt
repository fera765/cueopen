package dev.fluwy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Instala skills (arquivos SKILL.md) direto de repositórios públicos do GitHub. Sem login e sem token. */
object GithubSkills {
    data class Ref(val owner: String, val repo: String, val branch: String?, val path: String)

    const val MAX_SKILLS = 20
    private const val MAX_BYTES = 200_000
    private val name = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Aceita `owner/repo`, `https://github.com/owner/repo`, `…/tree/<branch>/<pasta>` e `…/blob/<branch>/<pasta>/SKILL.md`.
     * Devolve null se não for um endereço de repositório do GitHub.
     */
    fun parse(input: String): Ref? {
        var s = input.trim().removeSuffix("/").removeSuffix(".git")
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (s.startsWith("github.com/")) s = s.removePrefix("github.com/") else if (s.contains("://") || s.startsWith("github.com")) return null
        val parts = s.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2 || '.' in parts[0] || !name.matches(parts[0]) || !name.matches(parts[1]) || parts[0].length > 39 || parts[1].length > 100) return null
        if (parts.size == 2) return Ref(parts[0], parts[1], null, "")
        if (parts[2] != "tree" && parts[2] != "blob" || parts.size < 4) return null
        val rest = parts.drop(4)
        if (rest.any { it == ".." || it == "." }) return null
        return Ref(parts[0], parts[1], parts[3], rest.joinToString("/"))
    }

    /** Dos caminhos do repositório, os SKILL.md que ficam dentro de [prefix] (ou o próprio arquivo, se [prefix] já aponta para ele). */
    fun skillPaths(allPaths: List<String>, prefix: String): List<String> {
        val p = prefix.trim('/')
        return allPaths.filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .filter { p.isEmpty() || it == p || it.startsWith("$p/") }
            .sortedBy { it.count { c -> c == '/' } }.take(MAX_SKILLS)
    }

    /** Nome padrão de uma skill quando o SKILL.md não traz `name:` — a pasta em que ele está (ou o repositório, na raiz). */
    fun fallbackTitle(path: String, repo: String): String = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { repo }

    fun frontmatter(md: String, key: String): String {
        if (!md.startsWith("---")) return ""
        val fm = md.removePrefix("---").substringBefore("\n---")
        return fm.lines().firstOrNull { it.trim().startsWith("$key:") }?.substringAfter(":")?.trim()?.trim('"', '\'').orEmpty()
    }

    private fun get(url: String): String = http.newCall(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").header("User-Agent", "Fluwy").build()).execute().use { r ->
        if (r.code == 404) error("Repositório, branch ou pasta não encontrado (ele é público?).")
        if (r.code == 403 || r.code == 429) error("O GitHub limitou as consultas sem login. Tente de novo em alguns minutos.")
        if (!r.isSuccessful) error("GitHub respondeu HTTP ${r.code}")
        val body = r.body ?: error("Resposta vazia do GitHub")
        body.source().let { src -> src.request(MAX_BYTES.toLong() * 20); src.buffer.snapshot().utf8() }
    }

    /** Lista e baixa as skills do repositório, prontas para a pré-visualização e instalação. */
    suspend fun fetch(input: String): List<SkillHub.Remote> = withContext(Dispatchers.IO) {
        val ref = parse(input) ?: error("Endereço inválido. Use owner/repo ou o link do repositório no GitHub.")
        val api = "https://api.github.com/repos/${ref.owner}/${ref.repo}"
        val branch = ref.branch ?: runCatching { json.parseToJsonElement(get(api)).jsonObject["default_branch"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: error("Repositório não encontrado (ele é público?).")
        val tree = json.parseToJsonElement(get("$api/git/trees/$branch?recursive=1")).jsonObject["tree"]?.jsonArray.orEmpty()
        val all = tree.mapNotNull { e -> runCatching { e.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "blob" }?.get("path")?.jsonPrimitive?.contentOrNull }.getOrNull() }
        val paths = skillPaths(all, ref.path)
        if (paths.isEmpty()) error("Nenhum arquivo SKILL.md encontrado" + (if (ref.path.isNotBlank()) " em ${ref.path}" else " neste repositório") + ".")
        paths.mapNotNull { path ->
            val md = runCatching { get("https://raw.githubusercontent.com/${ref.owner}/${ref.repo}/$branch/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }) }
                .getOrNull()?.take(MAX_BYTES) ?: return@mapNotNull null
            val title = frontmatter(md, "name").ifBlank { fallbackTitle(path, ref.repo) }
            SkillHub.Remote(id = "github:${ref.owner}/${ref.repo}/$path", title = title, description = frontmatter(md, "description").take(400),
                source = "github.com/${ref.owner}/${ref.repo}", installs = 0, content = md)
        }
    }
}
