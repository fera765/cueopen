package dev.fluwy.app.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Família humana/saída: Aprovação, Revisar e aprovar, Notificação.

internal suspend fun nodeApproval(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val q = r("question").ifBlank { "Posso continuar?" }
                WfQuality.contentProblem("A pergunta de aprovação", q)?.let { return NodeResult(error = it) }
                if (run.trigger == "test") return NodeResult("aprovado (teste: aprovação simulada; o envio seguinte também é simulado)", port = "true")
                val d = CompletableDeferred<Boolean>()
                WorkflowEngine.approvals["${run.id}|${node.id}"] = d
                setStatus("waiting")
                runCatching {
                    dev.fluwy.app.notify.NotificationCenter.post("Aprovar fluxo \"${w.name}\"?", q.take(200),
                        dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.APPROVAL, needsAction = true,
                        agent = "Fluxo · ${w.name}", detail = q.take(4_000), route = "workflow/${w.id}", routeLabel = "Responder no fluxo")
                }
                val hours = (r("hours").toLongOrNull() ?: 24L).coerceIn(1, 168)
                val ok = withTimeoutOrNull(TimeUnit.HOURS.toMillis(hours)) { d.await() } ?: false
                WorkflowEngine.approvals.remove("${run.id}|${node.id}")
                setStatus("running")
                NodeResult(if (ok) "aprovado" else "recusado", port = if (ok) "true" else "false")
            }
}

internal suspend fun nodeReview(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val title = WfFormat.oneLine(rh("title", true)).ifBlank { "Revisar" }
                val itemRaw = r("item").ifBlank { prev }
                val draft = rh("draft", false).trim()
                val auto = (node.config["mode"] ?: "ask") == "auto" || w.autonomous
                fun out(ok: Boolean, text: String, how: String) = NodeResult(buildJsonObject {
                    put("approved", ok); put("draft", text); put("decided_by", how); put("edited", text != draft)
                }.toString(), port = if (ok) "true" else "false")
                when {
                    // Teste nunca dispara o envio de verdade (ex.: responder um cliente): segue por "não".
                    run.trigger == "test" -> NodeResult(buildJsonObject { put("approved", true); put("draft", draft); put("decided_by", "teste")
                        put("note", "teste: aprovação simulada para testar o caminho todo; o envio seguinte é simulado. Em execução real " + (if (auto) "segue sozinho (automático)" else "espera a sua aprovação na página de revisão")) }.toString(), port = "true")
                    auto -> out(true, draft, "automático")
                    else -> {
                        val key = "${run.id}|${node.id}"
                        val view = WfView.view(WfView.itemBlocks(itemRaw))
                        val wait = CompletableDeferred<WorkflowEngine.ReviewDecision>()
                        WorkflowEngine.reviewWaits[key] = wait
                        WorkflowEngine.reviewsState.value = WorkflowEngine.reviewsState.value + (key to WorkflowEngine.Review(run.id, node.id, w.id, w.name, title, view, draft))
                        setStatus("waiting")
                        runCatching {
                            dev.fluwy.app.notify.NotificationCenter.post("✍ $title", WfView.plainText(view).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                                dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.APPROVAL, needsAction = true,
                                agent = "Fluxo · ${w.name}", dedupeKey = "review|$key", route = "review/${run.id}/${node.id}", routeLabel = "Revisar")
                        }
                        val hours = (r("hours").toLongOrNull() ?: 24L).coerceIn(1, 168)
                        val dec = try { withTimeoutOrNull(TimeUnit.HOURS.toMillis(hours)) { wait.await() } } finally {
                            WorkflowEngine.reviewWaits.remove(key); WorkflowEngine.reviewsState.value = WorkflowEngine.reviewsState.value - key
                        }
                        setStatus("running")
                        if (dec == null) out(false, draft, "tempo esgotado") else out(dec.approved, dec.draft.ifBlank { draft }, "você")
                    }
                }
            }
}

internal suspend fun nodeNotify(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                // Nada de JSON para o usuário: {{…}} com dados viram texto claro; o quadro/página usa o UI kit (cards, listas, imagens).
                val title = WfFormat.oneLine(rh("title", true)).ifBlank { w.name }.take(80)
                val text = rh("text", true).ifBlank { "Fluxo concluído." }
                val open = node.config["open"] ?: "modal"
                val layout = node.config["layout"] ?: "auto"
                val detailTpl = node.config["detail"].orEmpty()
                val rawDetail = if (detailTpl.isBlank()) "" else r("detail")
                val prevIsData = prev.isNotBlank() && !(WfFormat.isJson(prev) && Regex("\"trigger\"\\s*:").containsMatchIn(prev.take(200)))
                val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
                var view = ""
                var detail = ""
                if (open == "modal" || open == "page") {
                    when {
                        // Modelo próprio (UI kit) escrito no nó.
                        WfView.isView(detailTpl) -> view = WfView.fromTemplate(detailTpl, { t -> rt(t, 1) }, { t -> rt(t, 0) })
                        // Um nó (ex.: IA) devolveu uma view pronta.
                        WfView.isView(rawDetail) -> view = rawDetail.trim()
                        layout == "markdown" -> detail = if (detailTpl.isNotBlank()) rh("detail", false) else if (prevIsData) text + "\n\n" + WfFormat.readable(prev) else text
                        else -> {
                            val data = when {
                                detailTpl.isBlank() -> prev.takeIf { prevIsData }
                                WfFormat.isJson(rawDetail) -> rawDetail
                                else -> null
                            }
                            val md = if (detailTpl.isNotBlank() && data == null) rh("detail", false) else null
                            view = WfView.build(title, text, md, data, time)
                        }
                    }
                    if (detail.isBlank()) detail = if (view.isNotBlank()) WfView.plainText(view).take(6_000) else text
                }
                // Cards do UI kit com link ganham a foto da página; card que segue sem foto sai.
                if (view.isNotBlank()) view = runCatching { dev.fluwy.app.web.ImageEnricher.enrichCardsJson(view) }.getOrDefault(view)
                // Portão de conteúdo: nada vazio, sem {{…}} sobrando, sem JSON cru para o usuário.
                WfQuality.contentProblem("O título da notificação", title)?.let { return NodeResult(error = it) }
                WfQuality.contentProblem("O texto da notificação", text)?.let { return NodeResult(error = it) }
                if (view.isBlank() && detail.isNotBlank()) WfQuality.contentProblem("O conteúdo do quadro/página", detail)?.let { return NodeResult(error = it) }
                if (view.isNotBlank()) WfQuality.contentProblem("O conteúdo do quadro/página", WfView.plainText(view))?.let { return NodeResult(error = it) }
                var route = if (open == "none") null else "workflow/${w.id}"
                if (open == "chat") {
                    // Chat do fluxo: uma conversa com o conteúdo completo (Markdown estilo GitHub + UI kit), onde dá para continuar perguntando.
                    val md = buildString {
                        append("**").append(title).append("**\n\n").append(text)
                        val extra = if (detailTpl.isNotBlank()) rh("detail", false) else if (prevIsData) WfFormat.readable(prev) else ""
                        if (extra.isNotBlank() && extra.trim() != text.trim()) append("\n\n").append(extra)
                        if (WfView.isView(rawDetail)) append("\n\n```fluwyui\n").append(rawDetail.trim()).append("\n```")
                    }
                    WfQuality.contentProblem("A mensagem do chat do fluxo", md)?.let { return NodeResult(error = it) }
                    if (run.trigger != "test") route = WfChat.post(app.store, w, md)?.let { "chat/$it" } ?: route
                    detail = ""; view = ""
                }
                dev.fluwy.app.notify.NotificationCenter.post(title, text.replace("**", "").take(300),
                    dev.fluwy.app.notify.NotificationCenter.Priority.NORMAL, dev.fluwy.app.notify.NotificationCenter.Category.DONE,
                    agent = "Fluxo · ${w.name}", dedupeKey = "wf|${run.id}|${node.id}",
                    detail = detail, route = route, routeLabel = if (route?.startsWith("chat/") == true) "Abrir chat" else "Abrir fluxo", page = open == "page", view = view)
                NodeResult(buildJsonObject { put("title", title); put("text", text.take(4_000)); if (detail.isNotEmpty()) put("detail", detail.take(4_000)); put("layout", if (view.isNotEmpty()) "ui-kit" else "markdown") }.toString())
            }
}
