package dev.cueopen.app.skills

import dev.cueopen.app.data.GithubSkills
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Instalação de skills como o OpenBot: link do GitHub (repo, tree ou blob) com o commit fixado (pinned), descoberta das
 * pastas com SKILL.md, validação do frontmatter e download da PASTA INTEIRA (scripts, templates, referências) com os
 * filtros de segurança. Também aceita uma URL direta de SKILL.md (ex.: https://site/SKILL.md), baixando junto os
 * arquivos relativos que ele cita.
 */
object SkillInstaller {
    data class Fetched(val meta: SkillPackage.Meta, val markdown: String, val files: Map<String, ByteArray>, val source: String, val warnings: List<String>)

    private const val UA = "FluwyBot-SkillInstaller"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(): OkHttpClient = runCatching { dev.cueopen.app.CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.AGENT_API, http) }.getOrDefault(http)

    private fun getBytes(url: String, max: Long, github: Boolean = false): ByteArray {
        val rq = Request.Builder().url(url).header("User-Agent", UA).apply { if (github) header("Accept", "application/vnd.github+json") }.build()
        client().newCall(rq).execute().use { r ->
            if (r.code == 404) error("não encontrado (404): $url")
            if ((r.code == 403 || r.code == 429) && github) error("o GitHub limitou as consultas sem login; tente em alguns minutos")
            if (!r.isSuccessful) error("HTTP ${r.code} em $url")
            val src = r.body?.source() ?: error("resposta vazia")
            src.request(max + 1)
            if (src.buffer.size > max) error("arquivo grande demais: $url")
            return src.buffer.readByteArray()
        }
    }
    private fun getText(url: String, max: Long, github: Boolean = false) = String(getBytes(url, max, github), Charsets.UTF_8)

    /** Baixa e valida. Pode devolver mais de uma skill quando o link é um repositório com várias. */
    suspend fun fetch(source: String, wantedName: String? = null): List<Fetched> = withContext(Dispatchers.IO) {
        val ref = GithubSkills.parse(source)
        if (ref != null) fetchGithub(ref, wantedName) else fetchUrl(source.trim(), wantedName)
    }

    private fun fetchGithub(ref: GithubSkills.Ref, wanted: String?): List<Fetched> {
        val api = "https://api.github.com/repos/${ref.owner}/${ref.repo}"
        // Fixa o commit: o que foi instalado é exatamente o que foi validado (mesmo que o repositório mude depois).
        val refName = ref.branch ?: "HEAD"
        val commitObj = json.parseToJsonElement(getText("$api/commits/${java.net.URLEncoder.encode(refName, "UTF-8").replace("%2F", "/")}", 2_000_000, true)).jsonObject
        val sha = commitObj["sha"]?.jsonPrimitive?.contentOrNull ?: error("não consegui resolver o commit de ${ref.owner}/${ref.repo}")
        val treeObj = json.parseToJsonElement(getText("$api/git/trees/$sha?recursive=1", 8_000_000, true)).jsonObject
        if (treeObj["truncated"]?.jsonPrimitive?.contentOrNull == "true") error("repositório grande demais para listar; use o link da pasta da skill (…/tree/<branch>/<pasta>)")
        val entries = treeObj["tree"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "blob" }
        val sizes = entries.associate { (it["path"]?.jsonPrimitive?.contentOrNull ?: "") to (it["size"]?.jsonPrimitive?.longOrNull ?: 0L) }
        val links = entries.filter { it["mode"]?.jsonPrimitive?.contentOrNull == "120000" }.mapNotNull { it["path"]?.jsonPrimitive?.contentOrNull }.toSet()
        // Link de arquivo (blob …/SKILL.md): a skill é a pasta dele.
        val under = if (ref.path.endsWith("SKILL.md", true)) ref.path.substringBeforeLast('/', "") else ref.path
        val folders = SkillPackage.skillFolders(sizes.keys.toList(), under).take(20)
        if (folders.isEmpty()) error("nenhum SKILL.md em ${ref.owner}/${ref.repo}" + (if (under.isNotBlank()) "/$under" else ""))
        val raw = { p: String -> "https://raw.githubusercontent.com/${ref.owner}/${ref.repo}/$sha/" + p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } }
        return folders.map { folder ->
            val prefix = if (folder.isEmpty()) "" else "$folder/"
            val own = sizes.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
                .filterKeys { !SkillPackage.unsafePath(it) }
            if (own.keys.any { (prefix + it) in links }) error("a skill contém links simbólicos, que não são aceitos")
            if (own.size > SkillPackage.MAX_FILES) error("a skill tem ${own.size} arquivos (máx. ${SkillPackage.MAX_FILES})")
            if (own.values.sum() > SkillPackage.MAX_BYTES) error("a skill passa de 10 MB")
            val md = getText(raw(prefix + "SKILL.md"), SkillPackage.MAX_SKILL_MD.toLong())
            val meta = SkillPackage.inspect(md, folder.substringAfterLast('/').ifBlank { ref.repo }, if (folders.size == 1) wanted else null)
            val files = own.filterKeys { it != "SKILL.md" }.filterValues { it <= SkillPackage.MAX_FILE_BYTES }.keys
                .associateWith { p -> getBytes(raw(prefix + p), SkillPackage.MAX_FILE_BYTES) }
            Fetched(meta, md, files, "github.com/${ref.owner}/${ref.repo}/${folder}@${sha.take(12)}",
                SkillPackage.warnings(md + files.values.joinToString("\n") { String(it, Charsets.UTF_8).take(50_000) }))
        }
    }

    private fun fetchUrl(url: String, wanted: String?): List<Fetched> {
        if (!url.startsWith("https://") && !url.startsWith("http://")) error("informe um link do GitHub (owner/repo) ou uma URL https de um SKILL.md")
        val md = getText(url, SkillPackage.MAX_SKILL_MD.toLong())
        if (md.trimStart().startsWith("<!doctype", true) || md.trimStart().startsWith("<html", true)) error("o endereço devolveu uma página HTML, não um SKILL.md")
        val base = url.substringBeforeLast('/') + "/"
        val host = java.net.URI(url).host
        val fallback = url.substringBeforeLast('/').substringAfterLast('/').takeIf { it.isNotBlank() && it != host } ?: host.substringBefore('.')
        val meta = SkillPackage.inspect(md, fallback, wanted)
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for (rel in SkillPackage.relativeRefs(md)) {
            val bytes = runCatching { getBytes(base + rel, SkillPackage.MAX_FILE_BYTES) }.getOrNull() ?: continue
            if (java.net.URI(base + rel).host != host) continue
            total += bytes.size; if (total > SkillPackage.MAX_BYTES) break
            files[rel] = bytes
        }
        return listOf(Fetched(meta, md, files, url, SkillPackage.warnings(md)))
    }

    /** Grava os arquivos da skill numa pasta (substitui a versão anterior). Devolve a lista gravada. */
    fun writeFolder(dir: File, f: Fetched): List<String> {
        dir.deleteRecursively(); dir.mkdirs()
        File(dir, "SKILL.md").writeText(f.markdown)
        f.files.forEach { (p, b) ->
            val out = File(dir, p).canonicalFile
            if (!out.path.startsWith(dir.canonicalPath + File.separator)) return@forEach
            out.parentFile?.mkdirs(); out.writeBytes(b)
            if (p.endsWith(".sh") || p.endsWith(".py")) out.setExecutable(true)
        }
        return listOf("SKILL.md") + f.files.keys.sorted()
    }
}
