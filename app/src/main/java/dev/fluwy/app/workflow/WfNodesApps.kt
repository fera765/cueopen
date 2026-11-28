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

// Família Apps: Conector, MCP (antigo), Web API do hub e Terminal. Envios externos são simulados em teste.

internal suspend fun nodeConnector(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                // Preenche os {{…}} DENTRO de cada valor do JSON (texto com aspas/quebras de linha — ex.: um rascunho de e-mail —
                // não quebra mais o JSON). Modelo que não é JSON válido cai no preenchimento de texto antigo.
                val tpl = node.config["campos"].orEmpty().ifBlank { "{}" }
                val parsedTpl = runCatching { Json.parseToJsonElement(tpl) as JsonObject }.getOrNull()
                val campos = if (parsedTpl != null) {
                    fun walk(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
                        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString && e.content.contains("{{")) kotlinx.serialization.json.JsonPrimitive(rt(e.content, 0)) else e
                        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { walk(it) })
                        is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
                    }
                    walk(parsedTpl) as JsonObject
                } else {
                    val camposText = r("campos").ifBlank { "{}" }
                    runCatching { Json.parseToJsonElement(camposText) as JsonObject }.getOrElse { return NodeResult(error = "Campos não são um objeto JSON válido depois de preencher os valores: ${camposText.take(300)}") }
                }
                if (WfQuality.isEmailSend(node)) WfQuality.emailProblem(campos)?.let { return NodeResult(error = "$it Nada foi enviado.") }
                val kind = node.config["source"].orEmpty().ifBlank { WfConnectors.HUB }
                // E-mail sai sempre em HTML formatado (Markdown → HTML com estilos inline) + versão em texto.
                val sendCampos = if (WfQuality.isEmailSend(node)) {
                    val keys = runCatching { WfConnectors.fields(app.store, kind, r("conector").trim(), r("acao").trim(), r("conexao").trim()).map { it.key }.toSet() }.getOrDefault(emptySet())
                    // Itens com link (notícias/produtos) ganham a foto da página antes de virar HTML.
                    val bodyKey = listOf("body_text", "body", "corpo", "text").firstOrNull { (campos[it] as? kotlinx.serialization.json.JsonPrimitive)?.content?.isNotBlank() == true }
                    val withImgs = bodyKey?.let { k ->
                        val md = (campos[k] as kotlinx.serialization.json.JsonPrimitive).content
                        val links = if (WfEmail.looksHtml(md)) emptyList() else WfEmail.linkedItemsWithoutImage(md)
                        if (links.isEmpty()) campos else {
                            val imgs = withTimeoutOrNull(8_000) { links.map { u -> u to dev.fluwy.app.web.ImageEnricher.imageFor(u) } }?.mapNotNull { (u, i) -> i?.let { u to it } }?.toMap().orEmpty()
                            JsonObject(campos + (k to kotlinx.serialization.json.JsonPrimitive(WfEmail.withImages(md, imgs))))
                        }
                    } ?: campos
                    WfEmail.prepare(withImgs, keys)
                } else campos
                if (run.trigger == "test" && WfQuality.isExternalSend("connector", node.config))
                    return simulated(node, "${r("conector").trim()} · ${r("acao").trim()}", sendCampos.toString())
                val o = WfConnectors.run(app.store, kind, r("conector").trim(), r("acao").trim(), sendCampos, r("conexao").trim())
                NodeResult(o.output, error = o.error)
            }
}

internal suspend fun nodeMcp(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val argsText = r("args").ifBlank { "{}" }
                val args = runCatching { Json.parseToJsonElement(argsText) as JsonObject }.getOrElse { return NodeResult(error = "Argumentos não são um objeto JSON válido: ${argsText.take(300)}") }
                if (run.trigger == "test" && WfQuality.isExternalSend("mcp", node.config)) return simulated(node, r("tool").trim(), args.toString())
                val out = dev.fluwy.app.connectors.Connectors.callTool(app.store, r("tool").trim(), args)
                if (Regex("^\\s*(ERRO|ERROR|RECUSADO|Falha)", RegexOption.IGNORE_CASE).containsMatchIn(out)) NodeResult(error = out.take(1_500)) else NodeResult(out)
            }
}

internal suspend fun nodeWebApi(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val slug = r("api").trim()
                val api = dev.fluwy.app.browser.WebApiHub.find(app, slug)?.takeIf { it.enabled }
                    ?: return NodeResult(error = "Web API \"$slug\" não existe ou está desligada no API Hub.")
                val tpl = runCatching { Json.parseToJsonElement(node.config["args"].orEmpty().ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject }
                    .getOrElse { return NodeResult(error = "Argumentos não são um JSON válido: ${it.message?.take(120)}") }
                // Templates resolvidos valor a valor (texto com aspas não quebra o JSON).
                fun res(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
                    is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) kotlinx.serialization.json.JsonPrimitive(rt(e.content, 0)) else e
                    is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.mapValues { res(it.value) })
                    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { res(it) })
                    else -> e
                }
                var args = res(tpl) as kotlinx.serialization.json.JsonObject
                // Fluxo publicado = autorização do usuário para a ação configurada; no teste, só valida.
                if (api.sensitive) args = kotlinx.serialization.json.JsonObject(args + ("confirmed" to kotlinx.serialization.json.JsonPrimitive("true")) +
                    (if (run.trigger == "test") mapOf("__dry_run" to kotlinx.serialization.json.JsonPrimitive("true")) else emptyMap()))
                val out = dev.fluwy.app.browser.WebApiHub.call(app, api, args)
                if (out.contains("\"ok\":true")) NodeResult(out)
                else NodeResult(error = "${api.slug}: " + (Regex("\"error\":\"([^\"]*)").find(out)?.groupValues?.get(1) ?: out.take(300)))
            }
}

internal suspend fun nodeShell(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return kotlin.run {
                val cmd = r("command").trim()
                if (cmd.isBlank()) return NodeResult(error = "Comando vazio.")
                if (run.trigger == "test" && WfQuality.isExternalSend("shell", mapOf("command" to cmd))) return simulated(node, "comando que envia para fora", cmd)
                if (!app.sandbox.isInstalled()) return NodeResult(error = "O Linux do app não está instalado (Configurações › Sandbox).")
                val res = app.sandbox.exec(dev.fluwy.app.sandbox.InteractivePrompts.ENV + "\n{\n" + cmd + "\n} < /dev/null",
                    cwd = "/root/workspace", timeoutSec = (r("timeout").toLongOrNull() ?: 120L).coerceIn(5, 1_800))
                val out = buildJsonObject { put("exit", res.exitCode); put("output", res.output.takeLast(15_000)) }.toString()
                if (res.exitCode != 0) NodeResult(out, error = "Saiu com código ${res.exitCode}: ${res.output.takeLast(500)}") else NodeResult(out)
            }
}

/** Envio externo no teste: valida o conteúdo e devolve o que SERIA enviado, sem acionar nada. */
internal fun simulated(node: WfNode, target: String, payload: String): NodeResult {
    if (WfQuality.isEmailSend(node)) runCatching { Json.parseToJsonElement(payload) as JsonObject }.getOrNull()
        ?.let { WfQuality.emailProblem(it) }?.let { return NodeResult(error = "$it (teste: nada foi enviado)") }
    if (payload.isNotBlank()) Regex("\\{\\{[^}]*\\}\\}").find(payload)?.let { return NodeResult(error = "Campo não preenchido no envio: ${it.value} (teste: nada foi enviado)") }
    return NodeResult(buildJsonObject {
        put("simulated", true); put("target", target)
        put("would_send", runCatching { Json.parseToJsonElement(payload) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(payload.take(8_000)) })
        put("note", "Teste: envio externo simulado — nada saiu do celular.")
    }.toString())
}