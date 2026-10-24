package dev.cueopen.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

/** Catálogo público de modelos da OpenRouter (não precisa de chave). Cache em memória. */
object OpenRouterModels {
    data class Model(val id: String, val name: String, val context: Int, val promptPrice: Double, val completionPrice: Double)

    @Volatile private var cache: List<Model>? = null

    suspend fun all(): List<Model> = cache ?: withContext(Dispatchers.IO) {
        val body = http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").build()).execute().use { it.body?.string().orEmpty() }
        val arr = JSONObject(body).getJSONArray("data")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.optJSONObject("pricing")
            Model(
                id = o.getString("id"), name = o.optString("name", o.getString("id")),
                context = o.optInt("context_length", 0),
                promptPrice = (p?.optString("prompt")?.toDoubleOrNull() ?: 0.0) * 1_000_000,
                completionPrice = (p?.optString("completion")?.toDoubleOrNull() ?: 0.0) * 1_000_000,
            )
        }
        cache = list
        list
    }

    fun search(list: List<Model>, q: String): List<Model> {
        val terms = q.lowercase().split(' ').filter { it.isNotBlank() }
        return list.filter { m -> terms.all { t -> m.id.lowercase().contains(t) || m.name.lowercase().contains(t) } }.take(40)
    }
}

/**
 * Marketplace de skills: busca no índice público Skyll (milhares de SKILL.md agregados de repositórios
 * do GitHub). Parser tolerante a variações de formato da resposta.
 */
object SkillHub {
    data class Remote(val id: String, val title: String, val description: String, val source: String, val installs: Int, val content: String)

    suspend fun search(query: String, limit: Int = 25): List<Remote> = withContext(Dispatchers.IO) {
        val url = "https://api.skyll.app/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", limit.toString()).build()
        val body = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) error("Catálogo respondeu HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val arr: JSONArray = runCatching { JSONObject(body).let { it.optJSONArray("skills") ?: it.optJSONArray("results") ?: it.optJSONArray("data") } }
            .getOrNull() ?: runCatching { JSONArray(body) }.getOrDefault(JSONArray())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content", o.optString("skill_md", ""))
            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { frontmatter(content, "name") }.ifBlank { o.optString("id") }
            val desc = o.optString("description").ifBlank { frontmatter(content, "description") }
                .ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.contains(":") }.orEmpty() }
            Remote(
                id = o.optString("id", title), title = title, description = desc.take(400),
                source = o.optString("source", o.optString("repo", o.optString("url", ""))),
                installs = o.optInt("install_count", o.optInt("installs", 0)), content = content,
            )
        }
    }

    private fun frontmatter(md: String, key: String): String {
        if (!md.startsWith("---")) return ""
        val fm = md.removePrefix("---").substringBefore("\n---")
        return fm.lines().firstOrNull { it.trim().startsWith("$key:") }?.substringAfter(":")?.trim()?.trim('"', '\'').orEmpty()
    }

    /** Converte a skill remota numa Skill local (corpo do SKILL.md sem o frontmatter). */
    fun toSkill(r: Remote): Skill {
        val body = if (r.content.startsWith("---")) r.content.removePrefix("---").substringAfter("\n---").trim() else r.content.trim()
        return Skill(name = r.title.take(60), description = r.description.ifBlank { "Skill importada de ${r.source}" },
            instructions = body.ifBlank { r.description }, icon = "sparkles")
    }
}
