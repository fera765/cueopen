package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

/** APIs do hub que combinam com o pedido (semântica simples + sinônimos): documentação completa, para o modelo usar direto. */
internal fun AgentRunner.webApiHint(lastUser: String): String = runCatching {
    val rel = dev.fluwy.app.browser.WebApiHub.relevant(ctx, lastUser)
    if (rel.isEmpty()) "" else buildString {
        appendLine(); appendLine("## Web APIs do hub que servem para este pedido (use webapi_call(api, args) — mais rápido e confiável que o browser)")
        rel.forEach { appendLine(dev.fluwy.app.browser.WebApiHub.doc(it)) }
        if (rel.any { it.slug.startsWith("whatsapp_") }) {
            appendLine("WhatsApp: 'to' aceita número (com ou sem DDI 55), id (…@c.us/@g.us/@lid) ou nome da conversa. Para arquivos use file_path (arquivo da área de trabalho) ou file (data URL/https).")
            appendLine("Envios/alterações só quando o usuário pediu explicitamente (destinatário e conteúdo) → confirmed=true. Se faltar a conversa, allow_new=true apenas se o usuário confirmou que é um contato novo. Nunca envie para vários contatos em sequência.")
            appendLine("Contatos bloqueados nas configurações do app recusam a ação: não tente contornar. Mídia recebida: whatsapp_download_media salva o arquivo em webapi_files/ (área de trabalho do chat). Qualquer binário que uma API devolver (imagem, áudio, PDF em base64) é salvo em webapi_files/ e o JSON traz só o caminho.")
        }
    }
}.getOrDefault("")

/**
 * Pílula no chat para as ferramentas do API Hub ("API Hub: duck_ai_image"): antes elas retornavam antes do passo
 * genérico e rodavam invisíveis. Tocar na pílula mostra os argumentos enviados e o resultado.
 */
internal suspend fun AgentRunner.webApiToolWithPill(sessionId: String, name: String, a: JsonObject): ToolResult {
    val slug = a["api"]?.jsonPrimitive?.contentOrNull ?: a["slug"]?.jsonPrimitive?.contentOrNull
    val title = when (name) {
        "webapi_call" -> "API Hub: ${slug ?: "?"}"
        "webapi_test" -> "API Hub · testando" + (slug?.let { ": $it" } ?: "")
        "webapi_save" -> "API Hub · salvando: ${slug ?: "?"}"
        "webapi_probe" -> "API Hub · analisando a rede do site"
        else -> "API Hub · listando APIs"
    }
    val args = when (name) {
        "webapi_call" -> (a["args"]?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: "{}")
        "webapi_probe" -> a["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        "webapi_test" -> (a["url_template"]?.jsonPrimitive?.contentOrNull.orEmpty() + " · args " + (a["args"]?.toString() ?: "{}"))
        else -> slug.orEmpty()
    }.let { SecretVault.redact(sessionId, it) }.take(2000)
    val step = ChatItem.Step(tool = name, title = title, args = args)
    store.addItem(sessionId, step)
    setActivity(sessionId, Activity(Activity.Kind.THINKING, title))
    val r = try { webApiTool(sessionId, name, a) } catch (c: kotlinx.coroutines.CancellationException) {
        store.replaceItem(sessionId, step.copy(status = StepStatus.ERROR, result = "interrompido")); throw c
    }
    val out = r.output
    val failed = out.startsWith("ERRO") || out.startsWith("RECUSADO") || out.startsWith("PAUSADO") || out.startsWith("FALHOU") || out.contains("\"ok\":false")
    store.replaceItem(sessionId, step.copy(status = if (failed) StepStatus.ERROR else StepStatus.OK, result = SecretVault.redact(sessionId, out).take(4000)))
    return r
}

/**
 * Mesma leitura com os MESMOS argumentos repetida (o export mostrou 30× o mesmo gmail_search_email e o mesmo
 * web_fetch 3×): a partir da 3ª, não executa e manda usar o resultado que já tem.
 */
internal fun AgentRunner.repeatGuard(sessionId: String, call: dev.fluwy.app.llm.ToolCall): ToolResult? {
    if (call.name !in setOf("conector_executar", "web_fetch", "web_search", "browser_navigate", "file_list", "webapi_call", "browser_read")) return null
    if (call.name == "webapi_call" && call.argsJson.contains("\"confirmed\"")) return null
    val key = sessionId + "\u0000" + call.name + "\u0000" + normArgs(call.argsJson)
    val n = repeatCounts.merge(key, 1, Int::plus) ?: 1
    if (repeatCounts.size > 5000) repeatCounts.clear()
    return if (n >= 3) ToolResult("REPETIDO (não executado): ${call.name} já rodou ${n - 1}× nesta tarefa com exatamente estes argumentos. Use o resultado que você já recebeu, mude os argumentos (ex.: outra página, max_results maior) ou avance para a entrega.") else null
}

/** Ferramentas do Web API Hub (fora do runTool: o método já está no limite de tamanho da JVM). */

/** webapi_call com arquivos: file_path (área de trabalho) vira data URL em args.file; data_url de retorno é salvo em arquivo. */
internal suspend fun AgentRunner.webApiCallWithFiles(sessionId: String, api: dev.fluwy.app.data.WebApi, args0: JsonObject): String {
    var args = args0
    // O modelo às vezes manda "path"/caminho absoluto do terminal: normaliza para a área de trabalho.
    if ("file_path" !in args && args["path"] != null) args = JsonObject(args - "path" + ("file_path" to args["path"]!!))
    val fp = args["file_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?.removePrefix("/root/workspace/")?.removePrefix("/root/workspace")?.removePrefix("workspace/")?.trimStart('/')?.takeIf { it.isNotBlank() }
    if (fp != null) {
        val ws = workspace(sessionId).canonicalFile
        val f = File(ws, fp).canonicalFile
        if (!f.path.startsWith(ws.path)) return "ERRO: file_path fora da área de trabalho"
        if (!f.isFile) return "ERRO: arquivo não encontrado: $fp"
        if (f.length() > 16L * 1024 * 1024) return "ERRO: arquivo maior que 16 MB"
        val mime = java.net.URLConnection.guessContentTypeFromName(f.name) ?: when (f.extension.lowercase()) {
            "webp" -> "image/webp"; "opus", "ogg" -> "audio/ogg"; "m4a" -> "audio/mp4"; "pdf" -> "application/pdf"; else -> "application/octet-stream" }
        val data = "data:$mime;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        args = JsonObject(args - "file_path" + ("file" to JsonPrimitive(data)) + (if ("filename" in args) emptyMap() else mapOf("filename" to JsonPrimitive(f.name))))
    }
    // Stream (api.emit): os pedaços chegam enquanto a API roda; vão junto no resultado.
    val chunks = java.util.Collections.synchronizedList(mutableListOf<String>())
    val r0 = dev.fluwy.app.browser.WebApiHub.call(ctx, api, args, workspace = workspace(sessionId)) { if (chunks.size < 400) chunks += it }
    val r = if (chunks.isEmpty()) r0 else runCatching {
        JsonObject(Json.parseToJsonElement(r0).jsonObject + ("stream" to kotlinx.serialization.json.JsonArray(chunks.map { c -> runCatching { Json.parseToJsonElement(c) }.getOrElse { JsonPrimitive(c) } }))).toString()
    }.getOrDefault(r0)
    return materializeBinary(sessionId, api.slug, r, args["save_as"]?.jsonPrimitive?.contentOrNull)
}

/**
 * Binário no JSON de uma Web API (data URL ou base64 longo — ex.: image_b64 do duck_ai_image) vira ARQUIVO na área
 * de trabalho do chat (terminal e gerenciador de arquivos enxergam) e no JSON fica só o caminho. Antes o base64
 * passava de 40 KB, era cortado e o agente gastava passos tentando remontar uma imagem corrompida.
 */
internal fun AgentRunner.materializeBinary(sessionId: String, slug: String, r: String, saveAs: String? = null): String {
    if (r.length < 3000) return r
    return runCatching {
        val root = Json.parseToJsonElement(r)
        val dir = File(workspace(sessionId), "webapi_files").apply { mkdirs() }
        var n = 0
        val savedByHash = HashMap<String, String>()
        val b64re = Regex("^[A-Za-z0-9+/=\\r\\n_-]{2000,}$")
        fun ext(mime: String, bytes: ByteArray): String = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { mime.isNotBlank() } ?: when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> "pdf"
            bytes.size > 3 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
            bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
            bytes.size > 3 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "ID3" -> "mp3"
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "OggS" -> "ogg"
            else -> "bin"
        }
        fun save(key: String, value: String, mimeHint: String, nameHint: String): JsonElement? {
            val isData = value.startsWith("data:") && value.contains(";base64,")
            if (!isData && !b64re.matches(value)) return null
            val mime = if (isData) value.substringAfter("data:").substringBefore(";") else mimeHint
            val bytes = runCatching { android.util.Base64.decode(if (isData) value.substringAfter(",") else value, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
            if (bytes.size < 512) return null
            // Mesmo binário em dois campos (ex.: image_b64 e data_url): um arquivo só.
            val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            savedByHash[digest]?.let { return JsonPrimitive(it) }
            n++
            val base = (if (n == 1) saveAs else null)?.takeIf { it.isNotBlank() } ?: nameHint.takeIf { it.isNotBlank() && it.contains('.') }
                ?: (slug + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + (if (n > 1) "_$n" else "") + "." + ext(mime, bytes))
            val name = base.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_")
            val out = File(dir, name); out.writeBytes(bytes)
            savedByHash[digest] = "webapi_files/$name"
            return JsonPrimitive("webapi_files/$name")
        }
        fun fix(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> {
                val mime = listOf("mime", "mime_type", "mimetype", "content_type").firstNotNullOfOrNull { e[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val fname = listOf("filename", "file_name", "name").firstNotNullOfOrNull { e[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val out = LinkedHashMap<String, JsonElement>()
                for ((k, v) in e) {
                    val sv = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val m2 = e[k.removeSuffix("_b64").removeSuffix("_base64") + "_mime"]?.jsonPrimitive?.contentOrNull ?: mime
                    val saved = if (sv != null && sv.length >= 2000) save(k, sv, m2, fname) else null
                    if (saved != null) {
                        val nk = if (k == "data_url" || k.endsWith("_b64") || k.endsWith("_base64") || k == "base64" || k == "data") k.removeSuffix("_b64").removeSuffix("_base64").let { if (it == "data_url" || it == "base64" || it == "data") "saved_to" else it + "_file" } else k + "_file"
                        out[nk] = saved; out[k + "_bytes"] = JsonPrimitive(File(workspace(sessionId), (saved as JsonPrimitive).content).length())
                    } else out[k] = fix(v)
                }
                JsonObject(out)
            }
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { fix(it) })
            else -> e
        }
        val fixed = fix(root).toString()
        if (n > 0) fixed.removeSuffix("}") + ",\"files_note\":\"$n arquivo(s) salvo(s) na área de trabalho do chat (webapi_files/). Use o caminho: file_read/show_file/shell; o base64 não vem no JSON.\"}" else fixed
    }.getOrElse { r.replace(Regex("data:[^\"]{200,}"), "[mídia omitida]") }
}


internal suspend fun AgentRunner.webApiTool(sessionId: String, name: String, a0: JsonObject): ToolResult {
    // code_file: o código vem de um arquivo da área de trabalho (código grande inline cortava a resposta do modelo).
    val a = a0["code_file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && a0["code"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }?.let { cf ->
        val ws = workspace(sessionId).canonicalFile
        val f = File(ws, cf.removePrefix("/root/workspace/").trimStart('/')).canonicalFile
        if (!f.path.startsWith(ws.path) || !f.isFile) return ToolResult("ERRO: code_file não encontrado na área de trabalho: $cf")
        JsonObject(a0 + ("code" to JsonPrimitive(f.readText())))
    } ?: a0
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    when (name) {
        "webapi_list" -> return ToolResult("Web APIs do hub (chame com webapi_call(api, args); * = obrigatório):\n" + dev.fluwy.app.browser.WebApiHub.catalog(ctx))
        "webapi_call" -> {
            val api = dev.fluwy.app.browser.WebApiHub.find(ctx, s("api"))?.takeIf { it.enabled }
                ?: return ToolResult("ERRO: API \"${s("api")}\" não existe ou está desligada. Disponíveis:\n" + dev.fluwy.app.browser.WebApiHub.catalog(ctx))
            val args = webApiArgs(a["args"]) ?: return ToolResult("ERRO: args precisa ser um objeto JSON, ex.: {\"query\":\"…\",\"page\":1}")
            // Ações com efeito fora do app (ex.: enviar WhatsApp): só quando o usuário pediu, nunca por iniciativa do agente,
            // e nunca para vários destinatários de uma vez.
            if (api.sensitive && args["confirmed"]?.jsonPrimitive?.contentOrNull != "true")
                return ToolResult("RECUSADO: ${api.slug} age em seu nome fora do app. Só chame com confirmed=true quando o usuário pediu explicitamente esta ação (destinatário e conteúdo). Se não pediu, pergunte antes.")
            // Parâmetros conferidos antes de rodar: nome errado vira erro claro com a lista certa (antes era ignorado em silêncio).
            val known = api.params.map { it.name }.toSet() + setOf("confirmed", "file_path", "path", "save_as", "timeout", "page")
            val unknown = args.keys.filter { it !in known && !it.startsWith("__") }
            if (unknown.isNotEmpty() && api.params.isNotEmpty()) return ToolResult("ERRO: argumento(s) desconhecido(s) para ${api.slug}: ${unknown.joinToString()}. Use exatamente estes:\n" + dev.fluwy.app.browser.WebApiHub.doc(api))
            return ToolResult(webApiCallWithFiles(sessionId, api, args).take(40_000))
        }
        "webapi_probe" -> {
            val url = s("url").trim()
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return ToolResult("ERRO: url precisa começar com https://")
            val r = dev.fluwy.app.browser.WebApiRunner.probe(url, s("pattern"), a["desktop"]?.jsonPrimitive?.booleanOrNull ?: false)
            return ToolResult("Descoberta em $url (use as URLs de dados com api.replay a partir da própria página; estados embutidos se leem direto no código):\n" + r.take(14_000))
        }
        "webapi_test" -> {
            val args = webApiArgs(a["args"]) ?: return ToolResult("ERRO: args precisa ser um objeto JSON.")
            // Teste às cegas em série (o log mostrou 15 seguidos, até em example.com): limite e orientação.
            // Limite contra teste às cegas: 12 testes SEGUIDOS sem passar. Zera quando um teste passa, ao salvar e a cada
            // nova mensagem do usuário (antes era um total por conversa: conversas longas que criam várias APIs ficavam
            // travadas para sempre e o agente tentava contornar com tarefa agendada).
            val nTests = webApiTests.merge(sessionId, 1, Int::plus) ?: 1
            if (nTests > 12) return ToolResult("PAUSADO: 12 testes seguidos sem passar. Pare aqui e explique ao usuário, em poucas linhas, o que está bloqueando (seletor, bloqueio do site, dado que não existe) e o que você tentaria a seguir. " +
                "NÃO contorne este limite (tarefa agendada, outra conversa, outro agente): quando o usuário responder (ex.: \"continuar\"), os testes voltam a ser liberados nesta mesma conversa.", SessionStatus.WAITING_USER)
            if (Regex("^https?://(www\\.)?example\\.(com|org)").containsMatchIn(s("url_template"))) return ToolResult("RECUSADO: teste na página real do site-alvo, não em example.com. Para chamar uma API JSON do site, use o template da página do próprio site e api.fetchJson no código.")
            if (s("code").isBlank()) return ToolResult("ERRO: informe code (curto) ou code_file (arquivo .js na área de trabalho, para código grande).")
            val draft = dev.fluwy.app.data.WebApi(slug = "teste", name = "teste", urlTemplate = s("url_template"), code = s("code"), waitFor = s("wait_for"),
                desktop = a["desktop"]?.jsonPrimitive?.booleanOrNull ?: false, inject = s("inject").split(',').map { it.trim() }.filter { it.startsWith("https://") })
            // Funções que agem fora do app (envio, edição, exclusão) testam em modo seguro: validam tudo e não executam.
            val acts = Regex("waGuard|sendTextMessage|sendFileMessage|sendLocationMessage|deleteMessage|editMessage|sendReaction|forwardMessage|sendCreatePoll|sendVCard").containsMatchIn(s("code"))
            val targs = if (acts) JsonObject(args + mapOf("__dry_run" to JsonPrimitive("true"), "confirmed" to JsonPrimitive("true"))) else args
            val out = dev.fluwy.app.browser.WebApiHub.dryOk("teste", dev.fluwy.app.browser.WebApiRunner.run(ctx, draft, targs, workspace = workspace(sessionId)))
                .let { materializeBinary(sessionId, s("slug").ifBlank { "teste" }, it) }
            val passed = out.contains("\"ok\":true") && Regex("\"results\":\\[\\s*\\{").containsMatchIn(out)
            if (passed) { webApiPassed[sessionId] = (s("url_template") + "\u0000" + s("code")).hashCode(); webApiTests.remove(sessionId) }
            val net = runCatching { dev.fluwy.app.browser.WebApiRunner.network() }.getOrDefault("").takeIf { it.isNotBlank() && !it.startsWith("(sem valor") }
            return ToolResult((if (passed) "PASSOU" else "FALHOU (precisa ok:true e results com itens)") + (if (passed) "" else " (falha seguida $nTests/12)") +
                (if (net != null) "\nRequisições fetch/XHR que a página fez (dados podem vir prontos daqui — use api.replay/api.waitResponse):\n${net.take(3000)}" else "") +
                (if (!passed && nTests % 3 == 0) ". Três falhas seguidas: inspecione a página com browser_navigate + browser_js no ponto exato antes de testar de novo." else "") + ":\n" + out.take(12_000))
        }
        "webapi_save" -> {
            val slug = s("slug").trim()
            if (!dev.fluwy.app.browser.WebApiHub.validSlug(slug)) return ToolResult("ERRO: slug inválido (minúsculas, números e _, 3–40, começando por letra).")
            if (webApiPassed[sessionId] != (s("url_template") + "\u0000" + s("code")).hashCode()) return ToolResult("RECUSADO: rode webapi_test com este mesmo url_template e code até PASSAR antes de salvar.")
            val params = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<List<dev.fluwy.app.data.WebApiParam>>(s("params").ifBlank { "[]" }) }
                .getOrElse { return ToolResult("ERRO: params não é uma lista JSON válida: ${it.message?.take(160)}") }
            store.upsertWebApi(dev.fluwy.app.data.WebApi(slug = slug, name = s("name").ifBlank { slug }, description = s("description"), urlTemplate = s("url_template"),
                code = s("code"), waitFor = s("wait_for"), params = params, source = "ia", desktop = a["desktop"]?.jsonPrimitive?.booleanOrNull ?: false,
                inject = s("inject").split(',').map { it.trim() }.filter { it.startsWith("https://") },
                // Ação externa continua exigindo confirmação mesmo depois de ajustada pela IA.
                sensitive = (dev.fluwy.app.browser.WebApiHub.find(ctx, slug)?.sensitive == true) ||
                    Regex("waGuard|sendTextMessage|sendFileMessage|sendLocationMessage|deleteMessage|editMessage|sendReaction|forwardMessage|sendCreatePoll|sendVCard").containsMatchIn(s("code"))))
            webApiTests.remove(sessionId)
            return ToolResult("OK: Web API \"$slug\" salva no hub e ativa. Qualquer agente chama com webapi_call(api=\"$slug\", args={…}).")
        }
    }
    return ToolResult("ERRO: ferramenta desconhecida $name")
}

internal fun AgentRunner.logActivity(sessionId: String, tool: String, step: ChatItem.Step, error: Boolean, out: String) =
    dev.fluwy.app.diag.ActivityLog.tool(sessionId, tool, step.args, error, System.currentTimeMillis() - step.time, out)

/** Modo do browser da conversa: desktop (UA de computador) ou mobile. Vazio = mantém. */
internal suspend fun AgentRunner.browserMode(sessionId: String, mode: String) {
    val want = when (mode.trim().lowercase()) { "desktop", "computador", "pc" -> true; "mobile", "celular" -> false; else -> return }
    val b = browserFor(sessionId)
    if (b.ui.value.desktop != want) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { b.setDesktop(want) }
}

/** Pílula do API Hub mostra qual API foi usada: "API Hub · google_search". */
internal fun AgentRunner.stepTitle(tool: String, argsJson: String, default: String): String =
    if (tool != "webapi_call") default
    else "API Hub · " + (runCatching { kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonObject["api"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: "?")

/** args do webapi_*: aceita objeto JSON ou string com JSON. */
internal fun AgentRunner.webApiArgs(e: kotlinx.serialization.json.JsonElement?): JsonObject? = when {
    e == null || e is kotlinx.serialization.json.JsonNull -> JsonObject(emptyMap())
    e is JsonObject -> e
    e is kotlinx.serialization.json.JsonPrimitive && e.isString -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(e.content.ifBlank { "{}" }) as? JsonObject }.getOrNull()
    else -> null
}

internal fun AgentRunner.primaryModel(agent: AgentProfile?): String = agent?.model?.takeIf { it.isNotBlank() } ?: store.settings.model
/** Modelo desta chamada: o principal, ou o alternativo enquanto o principal está em limite de requisições. */
internal fun AgentRunner.stepModel(agent: AgentProfile?): String {
    val primary = primaryModel(agent)
    dev.fluwy.app.llm.ModelFallback.chosen(agent?.id ?: "_default", primary)
    return dev.fluwy.app.llm.ModelFallback.resolve(primary, fallbackChain())
}

internal fun AgentRunner.toolName(o: kotlinx.serialization.json.JsonElement): String =
    runCatching { o.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.getOrDefault("")

internal fun AgentRunner.contextQuery(session: Session): String {
    val users = session.llm.filter { it.role == "user" && it.content?.startsWith("[Sistema]") != true }.takeLast(3)
    return users.joinToString(" ") { it.content.orEmpty().take(1500) } + " " + session.summary.take(800) + " " + session.title
}

/** Plano do passo + esquema de ferramentas já filtrado. */
internal fun AgentRunner.toolContextPlan(sessionId: String, s: Session, all: kotlinx.serialization.json.JsonArray): Pair<ToolContextPlanner.Plan, kotlinx.serialization.json.JsonArray> {
    val names = all.map { toolName(it) }
    val hub = runCatching { dev.fluwy.app.FluwyApp.instance.uiConnectors }.getOrNull()
    val conns = hub?.connectors.orEmpty().filter { c -> names.any { it.startsWith("uic_${c.spec.id}_") } }
        .associate { it.spec.id to (it.spec.name to it.spec.description) }
    val mcpDisplayNames = store.state.value.connectors.associate { connector ->
        "mcp_${connector.id.replace(Regex("[^A-Za-z0-9_]"), "_")}" to connector.name
    }
    val mcp = names.filter { it.startsWith("mcp_") && it.contains("__") }.map { it.substringBefore("__") }.distinct()
        .associateWith { mcpDisplayNames[it] ?: it.removePrefix("mcp_") }
    val used = s.items.filterIsInstance<ChatItem.Step>().map { it.tool }.toSet()
    val availableGroups = ToolContextPlanner.GROUPS.filterValues { groupTools -> groupTools.any { it in names } }.keys
    val mcpKeywords = store.state.value.connectors.associate { cn ->
        ("mcp_" + cn.id.replace(Regex("[^A-Za-z0-9_]"), "_")) to ConnectorRouting.keywordsFor(
            cn.name, cn.description, dev.fluwy.app.connectors.Connectors.toolNames(cn).map { it.first },
            cn.gatewayRef == dev.fluwy.app.connectors.ActivepiecesHub.REF, cn.inventory)
    }
    // Conector marcado com @ pelo usuário: as ferramentas dele ficam fixas no contexto desde o 1º passo.
    Mentions.find(lastUserText(s), mentionTargets()).forEach { h ->
        when (h.target.kind) {
            Mentions.Kind.CONNECTOR -> ToolContextPlanner.load(sessionId, listOf(h.target.id))
            else -> Unit
        }
    }
    val plan = ToolContextPlanner.plan(ToolContextPlanner.Input(
        sessionId = sessionId, query = contextQuery(s), usedTools = if (s.squadProject != null && s.squadParentId == null) used + "build_team" else used, builder = s.connectorBuilder || s.connectorEditId != null,
        allowedGroups = availableGroups,
        connectors = conns, mcpServers = mcp, mcpKeywords = mcpKeywords,
        historyTokens = ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary),
        contextBudget = store.settings.contextBudget,
    ))
    val connIdOf = { n: String -> conns.keys.firstOrNull { n.startsWith("uic_${it}_") } }
    val mcpOf = { n: String -> n.substringBefore("__").takeIf { n.contains("__") } }
    val kept = buildJsonArray { all.forEach { t -> val n = toolName(t); if (plan.keepTool(n, connIdOf, mcpOf)) add(t) } }
    return plan to kept
}

/** Recusa abrir no browser um app que o hub cobre antes de tentar o conector nesta tarefa (ver ConnectorRouting.browserGuard). */
internal fun AgentRunner.hubBrowserGuard(sessionId: String, url: String): String? {
    val hubRef = dev.fluwy.app.connectors.ActivepiecesHub.REF
    val hub = store.state.value.connectors.firstOrNull { it.gatewayRef == hubRef && it.enabled && it.connectedAt != null } ?: return null
    val agent = store.agent(store.session(sessionId)?.agentId)
    if (agent?.useConnectorHub == false) return null
    val items = store.session(sessionId)?.items.orEmpty()
    val sinceUser = items.drop(items.indexOfLast { it is ChatItem.User }.coerceAtLeast(0))
    val hubSteps = sinceUser.filterIsInstance<ChatItem.Step>().filter { it.tool in dev.fluwy.app.connectors.HubConnectors.TOOLS }
    val failures = hubSteps.count { it.status == StepStatus.ERROR || it.result.startsWith("ERRO") || it.result.startsWith("Falha") || it.result.contains("❌") }
    return ConnectorRouting.browserGuard(url, true, hubSteps.size, failures, hub.inventory)
}

internal fun AgentRunner.lastUserText(s: Session): String = (s.items.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.text.orEmpty()
