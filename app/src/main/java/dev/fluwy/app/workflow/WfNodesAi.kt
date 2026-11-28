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

// Família IA: IA (texto), Decisão por IA e Agente IA. Rodam uma vez por item.

internal suspend fun nodeAgent(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val prompt = r("prompt").trim()
                if (prompt.isBlank()) return NodeResult(error = "Instrução vazia.")
                val agentName = r("agent").trim()
                val agent = app.store.state.value.agents.firstOrNull { agentName.isNotBlank() && it.name.equals(agentName, true) }
                if (agentName.isNotBlank() && agent == null) return NodeResult(error = "Agente \"$agentName\" não existe.")
                val session = Session(title = "[Workflow] ${w.name} · ${WfLogic.label(node)}", agentId = agent?.id)
                app.store.upsertSession(session)
                val timeoutMin = (r("timeout").toIntOrNull() ?: 15).coerceIn(1, 120)
                val msg = "[Fluxo automático \"${w.name}\" — ninguém está acompanhando agora; execute e responda com o resultado final, sem perguntas. " +
                    "Os dados que vieram dos nós anteriores já estão nesta mensagem: use-os direto, não busque de novo nos conectores. " +
                    "Seja econômico: prefira web_search e web_fetch (no máximo ~6 consultas); só use o navegador se for indispensável. " +
                    "Links: use o endereço direto da fonte (nunca links de redirecionamento como news.google.com/read); não tente abrir ou decodificar links de redirecionamento. " +
                    "Sua ÚLTIMA mensagem é a entrega que segue para os próximos nós: escreva nela o resultado completo (ex.: o briefing pronto), nunca um relato do que fez nem o próximo passo. Não use ask_question nem peça confirmação." +
                    (if (run.trigger == "test") " MODO TESTE: pesquise e leia de verdade, mas NÃO envie nada para fora (e-mail, mensagem, POST, publicação, compra); descreva o que enviaria." else "") + "]\n\n$prompt"
                val maxSteps = (r("maxSteps").toIntOrNull() ?: 30).coerceAtLeast(30).coerceAtMost(60)
                val budget = (r("budget").toLongOrNull() ?: 200_000L).coerceIn(30_000L, 1_500_000L)
                val finished = withTimeoutOrNull(timeoutMin * 60_000L) { app.agent.runBudgeted(session.id, msg, maxSteps, budget); true }
                if (finished == null) { app.agent.stop(session.id); return NodeResult(error = "O agente passou de $timeoutMin min.", sessionId = session.id) }
                val s = app.store.session(session.id)
                val assistants = s?.items?.filterIsInstance<ChatItem.Assistant>().orEmpty()
                val answer = (assistants.lastOrNull { it.final } ?: assistants.lastOrNull())?.text.orEmpty()
                when {
                    s?.status == SessionStatus.ERROR -> NodeResult(error = (s.items.lastOrNull { it is ChatItem.Error } as? ChatItem.Error)?.text ?: "O agente falhou.", sessionId = session.id)
                    s?.status == SessionStatus.WAITING_USER -> NodeResult(error = "O agente parou pedindo uma confirmação ou resposta. Abra o chat da execução.", sessionId = session.id)
                    answer.isBlank() -> NodeResult(error = "O agente terminou sem resposta.", sessionId = session.id)
                    WfQuality.agentUnfinished(answer) -> NodeResult(answer, error = "O agente parou no meio do trabalho (\"${answer.take(120)}\"). Deixe a instrução mais direta, aumente passos/tempo ou divida em nós.", sessionId = session.id)
                    else -> WfQuality.aiOutputProblem("A resposta do agente", answer)?.let { NodeResult(answer, error = it, sessionId = session.id) } ?: NodeResult(answer, sessionId = session.id)
                }
            }
}

internal suspend fun nodeAiDecide(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val criteria = r("criteria").trim()
                if (criteria.isBlank()) return NodeResult(error = "Defina o critério da decisão.")
                val item = rh("input", false).ifBlank { prev }.take(14_000)
                if (item.isBlank()) return NodeResult(error = dev.fluwy.app.prompts.Prompts.render("workflow.3", mapOf<String, Any?>("lb" to "{{")))
                val ask = dev.fluwy.app.prompts.Prompts.render("workflow.2", mapOf<String, Any?>("criteria" to (criteria), "item" to (item), "lb" to "{{")) +
                    dev.fluwy.app.prompts.Prompts.render("workflow.1", mapOf<String, Any?>("lb" to "{{"))
                val raw = app.llm.quick(ask, maxTokens = 300, conversationId = "workflow-${w.id}-${node.id}").trim()
                val obj = Regex("\\{[\\s\\S]*\\}").find(raw)?.value?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
                    ?: return NodeResult(error = dev.fluwy.app.prompts.Prompts.render("workflow.4", mapOf<String, Any?>("raw" to (raw.take(200)), "lb" to "{{")))
                val d = obj["decisao"] ?: obj["decision"]
                val yes = (d as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.lowercase() in setOf("true", "sim", "yes", "1")
                NodeResult(buildJsonObject {
                    put("decision", yes)
                    put("reason", (obj["motivo"] ?: obj["reason"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
                    put("category", (obj["categoria"] ?: obj["category"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
                    put("confidence", (obj["confianca"] ?: obj["confidence"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } ?: 0.0)
                }.toString(), port = if (yes) "true" else "false")
            }
}

internal suspend fun nodeAi(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val prompt = r("prompt").trim()
                if (prompt.isBlank()) return NodeResult(error = "Instrução vazia.")
                if (prompt.length > 120_000) return NodeResult(error = "Os dados ficaram grandes demais para a IA (${prompt.length / 1000} mil caracteres). Use um caminho mais específico, ex.: {{id.data.campo}}.")
                val max = (r("maxTokens").toIntOrNull() ?: 800).coerceIn(50, 8_000)
                // Provedores como o OpenCode exigem um id de conversa estável: um por fluxo+nó.
                // Entrada com XML de feed cru: troca pela lista enxuta (o modelo travava com 6–160 KB de XML).
                val input = if (Regex("(?i)<rss[\\s>]|<feed[\\s>]").containsMatchIn(prompt))
                    Regex("(?is)(<\\?xml[^>]*>\\s*)?<(rss|feed)[\\s>].*?(</(rss|feed)>|$)").replace(prompt) { m -> WfFeed.toText(WfFeed.parse(m.value, 25)).ifBlank { m.value.take(8_000) } }
                else prompt
                var out = ""
                for (attempt in 0..1) {
                    out = runCatching { app.llm.quick(input, maxTokens = max, conversationId = "workflow-${w.id}-${node.id}") }.getOrDefault("").trim()
                    if (out.isNotBlank()) break
                    kotlinx.coroutines.delay(2_000)
                }
                // Resposta em bloco de código (```json … ```) vira o conteúdo puro (ex.: uma view do UI kit).
                val clean = Regex("^```[a-zA-Z]*\\s*([\\s\\S]*?)\\s*```$").find(out)?.groupValues?.get(1)?.trim() ?: out
                if (clean.isBlank()) NodeResult(error = "O modelo não respondeu (2 tentativas, entrada de ${input.length / 1000} mil caracteres). Reduza os dados: use {{id.text}} ou {{id.items}} em vez do corpo inteiro.")
                else WfQuality.aiOutputProblem("A resposta da IA", clean)?.let { NodeResult(clean, error = it) } ?: NodeResult(clean)
            }
}
