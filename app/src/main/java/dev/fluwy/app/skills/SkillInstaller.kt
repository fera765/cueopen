package dev.fluwy.app.skills

import dev.fluwy.app.data.GithubSkills
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

    private fun client(): OkHttpClient = runCatching { dev.fluwy.app.FluwyApp.instance.proxyHttpClient(dev.fluwy.app.proxy.ProxyScope.AGENT_API, http) }.getOrDefault(http)

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

    /**
     * Baixa e valida qualquer origem suportada (pode devolver várias skills):
     * - GitHub: owner/repo, …/tree/<ref>/<pasta>, …/blob/<ref>/…/SKILL.md (commit fixado, pasta inteira);
     * - arquivo .zip (URL ou arquivo do workspace) com uma ou mais pastas de skill;
     * - URL direta de um .md com a skill (baixa também os arquivos relativos citados);
     * - página web que tem um link para SKILL.md (ou /SKILL.md no mesmo endereço);
     * - pasta ou arquivo local do workspace ([localRoot]).
     */
    suspend fun fetch(source: String, wantedName: String? = null, localRoot: File? = null): List<Fetched> = withContext(Dispatchers.IO) {
        val src = source.trim().trim('<', '>', '"', '\'', '`')
        val ref = GithubSkills.parse(src)
        when {
            ref != null -> fetchGithub(ref, wantedName)
            localRoot != null && !src.startsWith("http") -> fetchLocal(localRoot, src, wantedName)
            src.substringBefore('?').endsWith(".zip", true) -> fromZip(getBytes(src, SkillPackage.MAX_BYTES), src, wantedName)
            else -> fetchUrl(src, wantedName)
        }
    }

    /** Pasta, SKILL.md ou .zip que já está no workspace (ex.: anexado pelo usuário). */
    private fun fetchLocal(root: File, path: String, wanted: String?): List<Fetched> {
        val f = File(root, path.removePrefix("/root/workspace/").removePrefix("./")).canonicalFile
        if (!f.path.startsWith(root.canonicalPath)) error("o caminho precisa estar no workspace")
        if (!f.exists()) error("não encontrei $path no workspace")
        if (f.isFile && f.name.endsWith(".zip", true)) return fromZip(f.readBytes(), path, wanted)
        val dir = if (f.isDirectory) f else f.parentFile
        val md = (if (f.isFile) f else File(dir, "SKILL.md")).takeIf { it.isFile }?.readText() ?: error("não há SKILL.md em $path")
        val files = dir.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }.map { it.relativeTo(dir).path to it }
            .filter { (p, file) -> !SkillPackage.unsafePath(p) && file.length() <= SkillPackage.MAX_FILE_BYTES }.take(SkillPackage.MAX_FILES)
            .associate { (p, file) -> p to file.readBytes() }
        return listOf(Fetched(SkillPackage.inspect(md, dir.name, wanted), md, files, "workspace:$path", SkillPackage.warnings(md)))
    }

    /** Pacote .zip: acha as pastas com SKILL.md e aplica os mesmos filtros e limites do GitHub. */
    fun fromZip(bytes: ByteArray, source: String, wanted: String?): List<Fetched> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/').trimStart('/')
                if (!e.isDirectory && !name.startsWith("__MACOSX/") && !name.endsWith(".DS_Store")) {
                    if (SkillPackage.unsafePath(name)) { e = zin.nextEntry; continue }
                    val data = zin.readBytes()
                    total += data.size
                    if (total > SkillPackage.MAX_BYTES || entries.size >= SkillPackage.MAX_FILES * 5) error("pacote .zip grande demais")
                    entries[name] = data
                }
                e = zin.nextEntry
            }
        }
        val folders = SkillPackage.skillFolders(entries.keys.toList(), "")
        if (folders.isEmpty()) error("o .zip não tem nenhum SKILL.md")
        return folders.take(20).map { folder ->
            val prefix = if (folder.isEmpty()) "" else "$folder/"
            val own = entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            if (own.size > SkillPackage.MAX_FILES) error("a skill tem mais de ${SkillPackage.MAX_FILES} arquivos")
            val md = String(own["SKILL.md"]!!, Charsets.UTF_8)
            val meta = SkillPackage.inspect(md, folder.substringAfterLast('/').ifBlank { source.substringAfterLast('/').removeSuffix(".zip") }, if (folders.size == 1) wanted else null)
            Fetched(meta, md, own.filterKeys { it != "SKILL.md" && own[it]!!.size <= SkillPackage.MAX_FILE_BYTES }, "$source${if (folder.isEmpty()) "" else "#$folder"}",
                SkillPackage.warnings(md))
        }
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

    private fun looksHtml(s: String) = s.trimStart().let { it.startsWith("<!doctype", true) || it.startsWith("<html", true) || it.startsWith("<head", true) }

    private fun fetchUrl(urlIn: String, wanted: String?): List<Fetched> {
        val url0 = urlIn
        if (!url0.startsWith("https://") && !url0.startsWith("http://")) error("informe um link do GitHub (owner/repo), uma URL de SKILL.md/.zip ou um caminho do workspace")
        var url = url0
        var md = getText(url, SkillPackage.MAX_SKILL_MD.toLong())
        if (looksHtml(md)) {
            // Página web: segue um link para SKILL.md (ou tenta <endereço>/SKILL.md).
            val link = Regex("href=[\"']([^\"']*SKILL\\.md[^\"']*)[\"']", RegexOption.IGNORE_CASE).find(md)?.groupValues?.get(1)
                ?.let { java.net.URI(url).resolve(it).toString() } ?: (url.trimEnd('/') + "/SKILL.md")
            if (GithubSkills.parse(link) != null) return fetchGithub(GithubSkills.parse(link)!!, wanted)
            md = runCatching { getText(link, SkillPackage.MAX_SKILL_MD.toLong()) }.getOrNull()?.takeIf { !looksHtml(it) }
                ?: error("o endereço é uma página web e não achei um SKILL.md nela (tente o link direto do SKILL.md, do repositório ou de um .zip)")
            url = link
        }
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
