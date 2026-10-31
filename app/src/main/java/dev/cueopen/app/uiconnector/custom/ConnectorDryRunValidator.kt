package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.files.TextReplace
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.UiBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Pipeline de pré-instalação estritamente offline. Não usa OkHttp, BrowserHost ou execução de shell. */
object ConnectorDryRunValidator {
    data class Result(val passed: Boolean, val report: String, val attestation: ConnectorValidationAttestation?)

    private const val MOCK_TEXT = "Amostra local de resposta para validar o mapeamento do conector."
    private val genericItem = Json.parseToJsonElement(
        """{"id":"dry-run-1","name":"Amostra de validação","title":"Amostra de validação","label":"Amostra","status":"ok","message":"Resposta mock local","text":"Amostra de validação","body":"Amostra de validação","value":1,"price":1,"url":"https://example.invalid/item","link":"https://example.invalid/item","pubDate":"2026-01-01T00:00:00Z","created_at":"2026-01-01"}""",
    )

    suspend fun validate(
        rawYaml: String,
        def: CustomDef,
        examples: Map<String, Map<String, String>> = emptyMap(),
        settingsInput: Map<String, String> = emptyMap(),
    ): Result {
        val report = StringBuilder()
        val findings = ConnectorGuardrails.staticCheck(def)
        val errors = findings.filter { it.level == ConnectorGuardrails.Level.ERROR }
        val warnings = findings.filter { it.level == ConnectorGuardrails.Level.WARN }
        if (errors.isNotEmpty()) {
            report.appendLine("GUARDRAILS — ${errors.size} erro(s):")
            errors.forEach { report.appendLine("✗ $it") }
            return Result(false, report.toString().trim(), null)
        }
        report.appendLine("✓ YAML e schema: definição tipada, hosts e templates passaram no parser real")
        report.appendLine("✓ guardrails estáticos: nenhum bloqueio de shell/arquivo conhecido")

        val shellTool = def.tools.firstOrNull { it.shell != null }
        if (shellTool != null || def.setup != null) {
            val name = shellTool?.name ?: "setup"
            report.appendLine("✗ ferramenta $name: depende de shell/setup. O app não executa código gerado no dry-run porque não pode garantir isolamento de rede/IO; instalação bloqueada. Refaça com API, compute ou operações file declarativas.")
            return Result(false, report.toString().trim(), null)
        }

        val adsLibraryBlock = adsLibraryPolicyFailure(def)
        if (adsLibraryBlock != null) {
            report.appendLine("✗ Política de fonte: $adsLibraryBlock")
            return Result(false, report.toString().trim(), null)
        }

        val hostErrors = declaredRequests(def).mapNotNull { req ->
            val url = if (req.url.isNotBlank()) req.url else def.baseUrl + (req.path.ifBlank { "/" })
            UrlGuard.check(url.substringBefore("{{"), def.hosts, permissive = false)?.let { "$url — $it" }
        }
        if (hostErrors.isNotEmpty()) {
            hostErrors.distinct().forEach { report.appendLine("✗ Request bloqueado: $it") }
            return Result(false, report.toString().trim(), null)
        }
        report.appendLine("✓ requests: URLs/hosts declarados passaram na política HTTPS; todas as respostas abaixo são mocks locais")

        val safeSettings = settingsInput.mapValues { (_, v) -> sanitizeExample(v) }
        val settings = ConnectorSettings { key ->
            safeSettings[key] ?: def.settings.firstOrNull { it.id == key }?.default
        }
        val http = MockHttp(def)
        val bridge = MockHostBridge(def.capabilities, def.hosts)
        val conn = CustomConnector(
            def = def,
            http = http,
            permissive = true, // transporte substituído integralmente por MockHttp; sem socket de rede
            ws = MockWs(def),
            bridge = bridge,
        )
        try {
            val loginValues = def.login.fields.associate { field ->
                field.id to when (field.type) {
                    dev.cueopen.app.uiconnector.FieldType.EMAIL -> "validation@example.invalid"
                    dev.cueopen.app.uiconnector.FieldType.NUMBER -> "1"
                    dev.cueopen.app.uiconnector.FieldType.PASSWORD -> "VALIDATION_ONLY"
                    else -> "validation-only"
                }
            }
            if (def.login.fields.isNotEmpty() || def.login.request != null || def.login.verify != null) {
                http.fixture = responseFixture(def, null, emptyMap())
                val login = conn.login(loginValues)
                if (login.isFailure) {
                    report.appendLine("✗ login simulado: ${login.exceptionOrNull()?.message ?: "falha"}")
                    return Result(false, redact(report.toString(), conn), null)
                }
                report.appendLine("✓ login: fluxo de campos/extract/verify testado com valores sintéticos (nenhuma credencial real)")
            }

            http.fixture = responseFixture(def, null, emptyMap())
            val blocks = conn.snapshot(settings)
            if (blocks.size < def.panels.size + def.actions.size) {
                report.appendLine("✗ painéis: saída de teste incompleta (${blocks.size} bloco(s))")
                return Result(false, redact(report.toString(), conn), null)
            }
            val emptyTable = blocks.filterIsInstance<UiBlock.Table>().firstOrNull { table ->
                table.rows.isEmpty() || table.rows.all { row -> row.isEmpty() || row.all { it.isBlank() } }
            }
            if (emptyTable != null) {
                report.appendLine("✗ painel '${emptyTable.title}': a fixture não preencheu linhas/colunas (${emptyTable.columns.joinToString()})")
                return Result(false, redact(report.toString(), conn), null)
            }
            if (def.panels.isNotEmpty()) report.appendLine("✓ painéis: ${def.panels.size} renderizado(s) com fixtures JSON/XML locais")

            val fileFixtures = LinkedHashMap<String, String>()
            for (tool in def.tools) {
                val args = sampleArgs(tool, examples[tool.name].orEmpty(), settings)
                val missing = tool.params.filter { it.required && args[it.name].isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    report.appendLine("✗ ferramenta ${tool.name}: não foi possível gerar amostra para ${missing.joinToString { it.name }}")
                    return Result(false, redact(report.toString(), conn), null)
                }
                if (tool.send != null) {
                    // callTool usa somente MockWs; nenhuma mensagem sai do dispositivo.
                    val out = conn.callTool(tool.name, args, settings)
                    if (out.startsWith("ERRO") || out.startsWith("RECUSAD") || out.isBlank()) {
                        report.appendLine("✗ ferramenta realtime ${tool.name}: ${out.take(700)}")
                        return Result(false, redact(report.toString(), conn), null)
                    }
                    report.appendLine("✓ ferramenta ${tool.name}: request/await exercitados com WebSocket falso; nenhuma mensagem externa enviada")
                    continue
                }
                val fileStep = tool.file
                val filePath = fileStep?.let { Template.render(it.path, Template.Ctx(args)) }
                var expectedAfterReplace: String? = null
                if (fileStep?.op == "write" && filePath != null) {
                    fileFixtures[filePath] = Template.render(fileStep.content.orEmpty(), Template.Ctx(args))
                }
                if (fileStep?.op == "replace" && filePath != null) {
                    val ctx = Template.Ctx(args)
                    val oldText = Template.render(fileStep.oldText.orEmpty(), ctx)
                    val newText = Template.render(fileStep.newText.orEmpty(), ctx)
                    val initial = fileFixtures[filePath] ?: oldText
                    val seeded = bridge.fileOp("write", filePath, initial)
                    if (!seeded.ok) {
                        report.appendLine("✗ ferramenta ${tool.name}: não foi possível preparar fixture virtual (${seeded.text.take(500)})")
                        return Result(false, redact(report.toString(), conn), null)
                    }
                    expectedAfterReplace = TextReplace.apply(initial, oldText, newText, fileStep.allowMultiple).updatedText
                }
                http.fixture = responseFixture(def, tool, args)
                val out = conn.callTool(tool.name, args, settings)
                if (out.startsWith("ERRO") || out.startsWith("RECUSAD") || out.isBlank()) {
                    report.appendLine("✗ ferramenta ${tool.name}: ${out.take(700)}")
                    return Result(false, redact(report.toString(), conn), null)
                }
                if (fileStep?.op == "read" && filePath != null) {
                    val expected = fileFixtures[filePath]
                    if (expected != null && out != expected) {
                        report.appendLine("✗ ferramenta ${tool.name}: leitura do filesystem virtual diverge do conteúdo gravado")
                        return Result(false, redact(report.toString(), conn), null)
                    }
                }
                if (fileStep?.op == "replace" && filePath != null) {
                    val actual = bridge.fileOp("read", filePath, null)
                    if (!actual.ok || expectedAfterReplace == null || actual.text != expectedAfterReplace) {
                        report.appendLine("✗ ferramenta ${tool.name}: resultado do replace diverge do esperado no filesystem virtual")
                        return Result(false, redact(report.toString(), conn), null)
                    }
                    fileFixtures[filePath] = actual.text
                    report.appendLine("✓ ferramenta ${tool.name}: substituição verificada no filesystem virtual")
                }
                if (fileStep?.op == "delete" && filePath != null) fileFixtures.remove(filePath)
                if (tool.vslOutputPolicy != null) {
                    val policy = tool.vslOutputPolicy
                    val vsl = VslOutputGuardrails.validate(out, args, policy)
                    if (!vsl.passed) {
                        vsl.errors.forEach { report.appendLine("✗ ferramenta ${tool.name}: $it") }
                        return Result(false, redact(report.toString(), conn), null)
                    }
                    report.appendLine("✓ ferramenta ${tool.name}: saída VSL simulada dentro de ${vsl.minWords}–${vsl.maxWords} palavras (estimativa; duração real varia)")
                } else {
                    report.appendLine("✓ ferramenta ${tool.name}: contrato e saída renderizados contra mock local")
                }
            }

            // Confere as capacidades não arbitrárias com substitutos em memória. Shell/browser nunca acessam SO ou rede.
            if (def.tools.any { it.file != null || it.shell != null || it.fetch != null }) {
                report.appendLine("✓ capacidades: filesystem virtual e transporte de browser substituto; nenhum comando/código ou página real foi executado")
            } else report.appendLine("✓ capacidades: nenhuma capacidade de aparelho declarada")
            if (def.tools.any { it.agent != null }) report.appendLine("✓ delegação LLM: contrato/prompt exercitados com resposta simulada; nenhum modelo, terminal ou arquivo real foi chamado pelo subagente")
            report.appendLine("✓ rede externa: nenhuma chamada externa foi feita; respostas exclusivamente mock local")
            report.appendLine("✓ efeitos externos: ações, ordens e envios não foram executados")
            warnings.forEach { report.appendLine("Aviso (não bloqueante): $it") }
            val checks = ConnectorAttestationPolicy.REQUIRED_CHECKS
            val proof = ConnectorAttestationPolicy.issue(rawYaml, checks)
            return Result(true, redact(report.toString().trim(), conn), proof)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            return Result(false, redact("✗ Falha no dry-run: ${t.message ?: t.javaClass.simpleName}\n${report}".trim(), conn), null)
        } finally {
            conn.dispose()
            bridge.clear()
        }
    }

    /** A biblioteca de anúncios só é elegível quando existe uma alternativa oficial autorizada; scraping nunca é aceito. */
    internal fun adsLibraryPolicyFailure(def: CustomDef): String? {
        val descriptor = (def.id + " " + def.name + " " + def.description + " " + def.tools.joinToString(" ") { it.name + " " + it.description }).lowercase()
        val asksLibrary = listOf(
            "facebook ads library", "meta ads library", "facebook ads", "meta ads", "ad library",
            "ads library", "facebook.com/ads/library", "biblioteca de anúncios", "biblioteca de anuncios",
            "anúncios patrocinados do facebook", "anuncios patrocinados do facebook",
        ).any { it in descriptor }
        if (!asksLibrary) return null
        val usesBrowserOrCode = Capabilities.BROWSER in def.capabilities || def.tools.any { it.fetch != null || it.shell != null }
        if (usesBrowserOrCode || def.host != "graph.facebook.com") {
            return "a Biblioteca de Anúncios não pode ser acessada por scraping/browser ou código gerado. Use somente a API oficial autorizada da Meta; nenhuma alternativa autorizada foi demonstrada, então a instalação fica bloqueada."
        }
        return "o dry-run offline não consegue comprovar token, permissão nem disponibilidade da API oficial da Meta. A instalação permanece bloqueada até existir validação permitida e autorizada; não contorne login, CAPTCHA ou proteções."
    }

    private fun declaredRequests(def: CustomDef): List<ReqDef> = buildList {
        def.login.request?.let(::add); def.login.verify?.let(::add)
        def.panels.mapNotNullTo(this) { it.request }
        def.actions.mapTo(this) { it.request }
        def.tools.mapNotNullTo(this) { it.request }
    }

    private fun sampleArgs(tool: ToolDef, supplied: Map<String, String>, settings: ConnectorSettings): Map<String, String> =
        tool.params.associate { p ->
            val provided = sanitizeExample(supplied[p.name].orEmpty())
            val value = provided.takeIf { it.isNotBlank() } ?: when {
                p.allowed.isNotEmpty() -> p.allowed.first()
                tool.vslOutputPolicy?.durationParam == p.name -> "10"
                tool.vslOutputPolicy?.languageParam == p.name -> "Português"
                tool.vslOutputPolicy?.guardrailsParam == p.name -> "Respeite as regras informadas; sem promessas garantidas."
                tool.vslOutputPolicy?.forbiddenTermsParam == p.name -> ""
                p.name.contains("idioma", true) || p.name.contains("language", true) -> "Português"
                p.name.contains("guardrail", true) || p.name.contains("regra", true) -> "Sem alegações garantidas."
                p.type == "boolean" -> "true"
                p.type == "integer" || p.type == "number" -> {
                    val limit = tool.limits.firstOrNull { it.param == p.name }?.let { settings.num(it.maxSetting, 0.0) }
                    when { limit == null -> "1"; limit > 1.0 -> "1"; limit > 0.0 -> limit.toString(); else -> "0" }
                }
                else -> "amostra de validação"
            }
            p.name to value
        }

    private fun sanitizeExample(value: String): String = Regex("\\{\\{secret:[^}]+\\}\\}")
        .replace(value) { "VALIDATION_ONLY" }
        .take(2_000)

    private fun responseFixture(def: CustomDef, tool: ToolDef?, args: Map<String, String>): String {
        val vslPolicy = tool?.vslOutputPolicy
        val duration = vslPolicy?.let { args[it.durationParam]?.toDoubleOrNull()?.toInt() } ?: 10
        val language = vslPolicy?.let { args[it.languageParam].orEmpty().ifBlank { "Português" } } ?: "Português"
        val vslText = vslPolicy?.let { VslOutputGuardrails.sample(duration.coerceIn(1, 240), language, it, args) } ?: MOCK_TEXT
        val item = genericItem as JsonObject
        fun textFields(copy: String) = mapOf(
            "text" to JsonPrimitive(copy), "body" to JsonPrimitive(copy),
            "copy" to JsonPrimitive(copy), "script" to JsonPrimitive(copy),
            "content" to JsonPrimitive(copy), "message" to JsonPrimitive(MOCK_TEXT),
        )
        val common = mapOf(
            "ok" to JsonPrimitive(true), "success" to JsonPrimitive(true),
            "token" to JsonPrimitive("VALIDATION_TOKEN"), "access_token" to JsonPrimitive("VALIDATION_TOKEN"),
            "id" to JsonPrimitive("dry-run-1"), "name" to JsonPrimitive("Amostra de validação"),
            "title" to JsonPrimitive("Amostra de validação"), "status" to JsonPrimitive("ok"),
            "value" to JsonPrimitive(1), "price" to JsonPrimitive(1),
        ) + textFields(if (vslPolicy != null) vslText else MOCK_TEXT)
        val collections = mapOf("items" to JsonArray(listOf(item)), "results" to JsonArray(listOf(item)),
            "rows" to JsonArray(listOf(item)), "entries" to JsonArray(listOf(item)), "ads" to JsonArray(listOf(item)))
        var root: JsonElement = JsonObject(common + collections + ("data" to JsonObject(common + collections)))
        // Atende referências JSONPath frequentes e caminhos explícitos do conector com amostras determinísticas.
        val paths = LinkedHashSet<String>()
        def.login.extract.values.filter { it.startsWith("$") }.forEach(paths::add)
        def.panels.forEach { panel ->
            panel.request?.let { req -> paths += responsePaths(req.body) }
            panel.stats.forEach { paths += responsePaths(it.value) }
            paths += responsePaths(panel.title); paths += responsePaths(panel.subtitle); paths += responsePaths(panel.text)
            if (panel.rows.startsWith("$")) paths += panel.rows
            panel.columns.forEach { paths += responsePaths(it.value) }
        }
        def.tools.forEach { t ->
            t.request?.let { paths += responsePaths(it.body) }
            paths += responsePaths(t.response)
            t.list?.let { list -> if (list.rows.startsWith("$")) paths += list.rows; paths += responsePaths(list.item) }
        }
        for (path in paths) {
            val value = if (vslPolicy != null && path.lowercase().contains(Regex("copy|script|content|text|body"))) JsonPrimitive(vslText) else JsonPrimitive(if (path.lowercase().contains(Regex("token|key|auth"))) "VALIDATION_TOKEN" else MOCK_TEXT)
            root = putPath(root, path, value) ?: root
        }
        // Garante linha de exemplo em todos os caminhos de lista usados pelas tabelas e ferramentas.
        val listPaths = (def.panels.map { it.rows } + def.tools.mapNotNull { it.list?.rows }).filter { it.startsWith("$") }
        for (path in listPaths) {
            root = if (path == "$") JsonArray(listOf(item))
            else putPath(root, path.removeSuffix("[*]"), JsonArray(listOf(item))) ?: root
        }
        return root.toString()
    }

    private fun responsePaths(element: JsonElement?): Set<String> = element?.let { responsePaths(it.toString()) }.orEmpty()
    private fun responsePaths(text: String): Set<String> {
        val regex = Regex("\\{\\{\\s*(\\$[^|}\\s]+)")
        return regex.findAll(text).map { it.groupValues[1] }.filter { JsonPath.isValid(it) }.toSet()
    }

    private class MockHttp(private val def: CustomDef) : HttpExec {
        var fixture: String = "{}"
        override suspend fun exec(r: HttpReq): HttpRes {
            val error = UrlGuard.check(r.url, def.hosts, permissive = false)
            if (error != null) return HttpRes(400, emptyMap(), "Request bloqueado no mock: $error")
            return HttpRes(200, mapOf("content-type" to listOf("application/json")), fixture)
        }
    }

    /** Workspace efêmero em memória; shell e fetch respondem fixtures, sem executar código nem abrir navegador. */
    private class MockHostBridge(private val capabilities: Set<String>, private val hosts: Set<String>) : HostBridge {
        private val files = LinkedHashMap<String, String>()
        override fun granted(capability: String): Boolean = capability in capabilities
        override suspend fun ensureSetup(command: String, timeoutSec: Int): BridgeResult? =
            BridgeResult(true, "dry-run: setup registrado, comando não executado")
        override suspend fun shell(command: String, timeoutSec: Int): BridgeResult =
            BridgeResult(true, "dry-run: shell não executado; fixture local de saída")
        override suspend fun runAgentTask(task: String, model: String?, maxSteps: Int?, timeoutSec: Int): BridgeResult =
            if (Capabilities.AGENT in capabilities && task.isNotBlank()) BridgeResult(true, "dry-run: agente LLM não executado; saída mock local")
            else BridgeResult(false, "delegação LLM bloqueada no mock")
        override suspend fun fileOp(op: String, path: String, content: String?): BridgeResult {
            val root = File("/connector-validation")
            val target = BridgeSecurity.confine(root, path) ?: return BridgeResult(false, "caminho fora do workspace")
            val key = target.path.removePrefix(root.path).trimStart('/')
            return when (op) {
                "write" -> { if ((content?.toByteArray()?.size ?: 0) > BridgeSecurity.MAX_FILE_BYTES) BridgeResult(false, "arquivo grande demais") else { files[key] = content.orEmpty(); BridgeResult(true, "gravado (${content.orEmpty().length} caracteres)") } }
                "read" -> files[key]?.let { BridgeResult(true, it) } ?: BridgeResult(true, "fixture de arquivo para teste")
                "list" -> BridgeResult(true, files.keys.filter { it.startsWith(key) }.joinToString("\n").ifBlank { "[]" })
                "delete" -> { files.keys.removeAll { it == key || it.startsWith("$key/") }; BridgeResult(true, "removido no filesystem virtual") }
                else -> BridgeResult(false, "operação desconhecida")
            }
        }
        override suspend fun fetch(url: String, waitSec: Int): BridgeResult {
            // Não há browser nem rede no stub. A validação apenas recusa URL fora do host/HTTPS permitido.
            return if (UrlGuard.check(url, hosts, permissive = false) == null)
                BridgeResult(true, "dry-run: página não aberta; fixture local de texto")
            else BridgeResult(false, "URL externa bloqueada no dry-run")
        }
        fun clear() = files.clear()
    }

    /** Transportador WebSocket falso: envia apenas eventos sintéticos que exercitam regras declaradas. */
    private class MockWs(private val def: CustomDef) : WsExec {
        override fun open(url: String, headers: Map<String, String>, listener: WsListener): WsConn {
            val rt = def.realtime ?: return object : WsConn { override fun send(text: String) = true; override fun close() {} }
            val failure = RtGuard.check(url, def.hosts, permissive = false)
            if (failure != null) { listener.onClosed(failure); return object : WsConn { override fun send(text: String) = false; override fun close() {} } }
            listener.onOpen()
            rt.rules.forEach { rule ->
                var event: JsonElement = JsonObject(emptyMap())
                rule.match.forEach { (path, expected) -> event = putPath(event, path, JsonPrimitive(if (expected == "*") "VALIDATION_SAMPLE" else expected)) ?: event }
                rule.set.values.forEach { path -> event = putPath(event, path, JsonPrimitive("1")) ?: event }
                if (rule.pushTo.isNotBlank() && rule.pushItem != "$") {
                    val sample = if (rule.pushKey.isNotBlank()) putPath(item, rule.pushKey, JsonPrimitive("validation-1")) ?: item else item
                    event = putPath(event, rule.pushItem, sample) ?: event
                }
                listener.onText(event.toString())
            }
            return object : WsConn {
                override fun send(text: String): Boolean {
                    val outgoing = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                    val rid = findRequestId(outgoing)
                    for (tool in def.tools) {
                        val await = tool.send?.await ?: continue
                        var response: JsonElement = JsonObject(emptyMap())
                        await.match.forEach { (path, expected) ->
                            val rendered = expected.replace("{{rid}}", rid ?: "validation-rid")
                            response = putPath(response, path, JsonPrimitive(rendered)) ?: response
                        }
                        listener.onText(response.toString())
                    }
                    return true
                }
                override fun close() {}
            }
        }

        private fun findRequestId(value: JsonElement?): String? = when (value) {
            is JsonObject -> value.entries.firstOrNull { (key, v) -> key.contains("rid", true) || key.contains("request_id", true) || key == "id" && v is JsonPrimitive }?.value?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: value.values.firstNotNullOfOrNull(::findRequestId)
            is JsonArray -> value.firstNotNullOfOrNull(::findRequestId)
            else -> null
        }
    }

    private val item: JsonElement get() = genericItem

    private sealed interface PathToken {
        data class Key(val name: String) : PathToken
        data class Index(val index: Int) : PathToken
    }

    private fun putPath(root: JsonElement, rawPath: String, value: JsonElement): JsonElement? {
        val tokens = ArrayList<PathToken>()
        val matches = Regex("\\.([A-Za-z0-9_-]+)|\\['([^']+)'\\]|\\[(\\d+|\\*)\\]").findAll(rawPath)
        for (m in matches) {
            val key = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (key.isNotBlank()) tokens += PathToken.Key(key)
            else tokens += PathToken.Index(m.groupValues[3].takeIf { it != "*" }?.toIntOrNull() ?: 0)
        }
        if (tokens.isEmpty()) return null
        fun assign(node: JsonElement, at: Int): JsonElement {
            val token = tokens[at]
            val last = at == tokens.lastIndex
            return when (token) {
                is PathToken.Key -> {
                    val obj = node as? JsonObject ?: JsonObject(emptyMap())
                    val child = if (last) value else assign(obj[token.name] ?: JsonNull, at + 1)
                    JsonObject(obj.toMutableMap().apply { put(token.name, child) })
                }
                is PathToken.Index -> {
                    val arr = (node as? JsonArray)?.toMutableList() ?: mutableListOf()
                    while (arr.size <= token.index) arr += JsonNull
                    arr[token.index] = if (last) value else assign(arr[token.index], at + 1)
                    JsonArray(arr)
                }
            }
        }
        return assign(root, 0)
    }

    private fun redact(text: String, connector: CustomConnector): String =
        dev.cueopen.app.uiconnector.Redactor.redact(text, connector.secrets())
}
