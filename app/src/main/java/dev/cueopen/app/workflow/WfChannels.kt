package dev.cueopen.app.workflow

import android.content.Context
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.Store
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Chat do fluxo: uma conversa por fluxo; cada envio vira uma mensagem (Markdown GitHub + UI kit) e entra no histórico da IA. */
object WfChat {
    fun post(store: Store, w: Workflow, markdown: String): String? = runCatching {
        val title = "[Fluxo] ${w.name}"
        val s = store.state.value.sessions.firstOrNull { it.title == title } ?: Session(title = title).also { store.upsertSession(it) }
        store.addItem(s.id, ChatItem.Assistant(text = markdown, final = true))
        store.editSession(s.id) { it.copy(llm = (it.llm + LlmMessage("assistant", markdown.take(12_000))).takeLast(60), updatedAt = System.currentTimeMillis()) }
        s.id
    }.getOrNull()
}

/**
 * Webhook pela ponte do GitHub: quem chama faz POST de um comentário na issue configurada
 * (1ª linha = segredo, resto = payload). O app confere a cada minuto com ETag (resposta 304 não gasta cota),
 * aceita só comentários novos com o segredo e lembra o último id — cada chamada roda uma vez.
 */
object WfWebhook {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private fun sp(ctx: Context) = ctx.getSharedPreferences("wf_webhook", Context.MODE_PRIVATE)

    fun poll(ctx: Context, wfId: String, n: WfNode): List<WfQuality.Hook> = runCatching {
        val repo = n.config["repo"].orEmpty().trim(); val issue = n.config["issue"]?.toIntOrNull() ?: return emptyList()
        val secret = n.config["secret"].orEmpty().trim()
        val key = "$wfId|${n.id}|$repo#$issue"
        val p = sp(ctx)
        val last = p.getLong("$key|id", -1L)
        val req = Request.Builder().url("https://api.github.com/repos/$repo/issues/$issue/comments?per_page=100&sort=created&direction=desc")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "FluwyBot")
            .apply { p.getString("$key|etag", null)?.let { header("If-None-Match", it) } }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 304 || !r.isSuccessful) return emptyList()
            val body = r.body?.string().orEmpty()
            r.header("ETag")?.let { p.edit().putString("$key|etag", it).apply() }
            val newest = WfQuality.newestId(body)
            // Primeira leitura só marca a linha de base: chamadas antigas não disparam.
            if (last < 0) { p.edit().putLong("$key|id", newest).apply(); return emptyList() }
            val hooks = WfQuality.parseHooks(body, secret, last).take(10)
            if (newest > last) p.edit().putLong("$key|id", newest).apply()
            hooks
        }
    }.getOrDefault(emptyList())
}
