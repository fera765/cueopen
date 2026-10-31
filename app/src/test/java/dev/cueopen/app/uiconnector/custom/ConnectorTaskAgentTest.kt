package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.llm.LlmReply
import dev.cueopen.app.llm.ToolCall
import dev.cueopen.app.llm.Usage
import dev.cueopen.app.uiconnector.ConnectorSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorTaskAgentTest {
    private val settings = ConnectorSettings { null }

    @Test fun `all local dev studio agent flows have no local step cap`() {
        val parsed = CustomDefParser.parse(LocalDevStudioTemplate.YAML)
        assertEquals(parsed.errors.toString(), emptyList<String>(), parsed.errors)
        val def = parsed.def!!
        assertEquals("fluwyopendev", def.id)
        assertEquals(setOf(Capabilities.AGENT, Capabilities.TERMINAL, Capabilities.FILES), def.capabilities)
        assertEquals(3, def.tools.size)
        assertEquals(LocalDevStudioTemplate.UNBOUNDED_AGENT_TOOLS, def.tools.filter { it.agent != null }.map { it.name }.toSet())
        assertTrue(def.tools.filter { it.name in LocalDevStudioTemplate.UNBOUNDED_AGENT_TOOLS }.all { it.agent!!.maxSteps == null })
        assertFalse(LocalDevStudioTemplate.YAML.contains("budgetTokens:"))
        assertTrue(ConnectorGuardrails.staticCheck(def).none { it.level == ConnectorGuardrails.Level.ERROR })
    }

    @Test fun `all exact Fluwy Open Dev agent flows remain unbounded and retain configured timeout`() = runBlocking {
        val def = CustomDefParser.parse(LocalDevStudioTemplate.YAML).def!!
        val bridge = CaptureBridge(setOf(Capabilities.AGENT))
        val connector = CustomConnector(def, bridge = bridge)

        for (toolName in LocalDevStudioTemplate.UNBOUNDED_AGENT_TOOLS) {
            connector.callTool(toolName, mapOf("pedido" to "Execute $toolName"), settings)
            assertEquals("$toolName deve permanecer ilimitado", null, bridge.maxSteps)
            assertTrue("$toolName deve preservar o timeout", bridge.timeoutSec == 600)
            assertEquals("o nome exato do fluxo precisa chegar ao executor", toolName, bridge.toolName)
        }

        val legacyYaml = LocalDevStudioTemplate.YAML.replaceFirst("timeoutSec: 600", "maxSteps: 8\n      timeoutSec: 600")
        val legacy = CustomDefParser.parse(legacyYaml)
        assertEquals(legacy.errors.toString(), emptyList<String>(), legacy.errors)
        assertTrue(legacy.def!!.tools.first { it.name == "desenvolver_ui" }.agent!!.maxSteps == null)

        val renamed = CustomDefParser.parse(LocalDevStudioTemplate.YAML.replace("id: fluwyopendev", "id: fluwyopendevclone")).def!!
        for (toolName in LocalDevStudioTemplate.UNBOUNDED_AGENT_TOOLS) {
            assertEquals("$toolName no clone deve manter o padrão geral", 6, renamed.tools.first { it.name == toolName }.agent!!.maxSteps)
        }
    }

    @Test fun `agent task requires opt-in risky declaration and bounded values`() {
        val base = """{"id":"tasker","name":"Tasker","description":"local", "capabilities":["agent"],
          "tools":[{"name":"run_task","description":"delegates", "params":[{"name":"pedido","description":"task"}],
          "risky":true,"agent":{"task":"Faça {{pedido}}","maxSteps":4,"budgetTokens":2000,"timeoutSec":60}}]}"""
        assertTrue(CustomDefParser.parse(base).errors.isEmpty())
        assertTrue(CustomDefParser.parse(base.replace("\"agent\"],", "\"terminal\"],")).errors.any { it.contains("agent") })
        assertTrue(CustomDefParser.parse(base.replace("\"risky\":true", "\"risky\":false")).errors.any { it.contains("risky") })
        assertTrue(CustomDefParser.parse(base.replace("\"maxSteps\":4", "\"maxSteps\":99")).errors.any { it.contains("maxSteps") })
        assertTrue(CustomDefParser.parse(base.replace("\"budgetTokens\":2000", "\"budgetTokens\":10")).errors.isEmpty())
        assertTrue(CustomDefParser.parse(base.replace("{{pedido}}", "{{password}}")).errors.any { it.contains("variável desconhecida") })
    }

    @Test fun `delegation sends only tool parameters and refuses without live agent grant`() = runBlocking {
        val yaml = """id: taskdemo
            |name: Task Demo
            |description: local task
            |version: "1"
            |capabilities: [agent]
            |tools:
            |  - name: run_task
            |    description: execute task
            |    risky: true
            |    params:
            |      - name: pedido
            |        description: user task
            |    agent:
            |      task: "Faça: {{pedido}}"
            |      maxSteps: 2
            |      budgetTokens: 1000
            |      timeoutSec: 30
            |""".trimMargin()
        val def = CustomDefParser.parse(yaml).def!!
        val bridge = CaptureBridge(setOf(Capabilities.AGENT))
        val connector = CustomConnector(def, bridge = bridge)
        val out = connector.callTool("run_task", mapOf("pedido" to "Crie uma landing page"), settings, delegatedModel = "provider/model")
        assertTrue(out, out.contains("Concluído"))
        assertEquals("Faça: Crie uma landing page", bridge.task)
        assertEquals("provider/model", bridge.model)
        assertTrue(bridge.maxSteps == 2)

        val denied = CaptureBridge(emptySet())
        val noGrant = CustomConnector(def, bridge = denied).callTool("run_task", mapOf("pedido" to "x"), settings)
        assertTrue(noGrant, noGrant.startsWith("RECUSADO"))
        assertEquals(null, denied.task)
    }

    @Test fun `inner agent only receives live terminal and files tools and can build through both`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL, Capabilities.FILES))
        val model = ScriptedModel { turn, tools, _ ->
            if (turn == 1) {
                val names = toolNames(tools)
                assertEquals(setOf("shell", "file_list", "file_read", "file_write", "file_edit"), names)
                LlmReply(null, listOf(
                    ToolCall("w1", "file_write", """{"path":"index.html","content":"<main>Open Dev</main>"}"""),
                    ToolCall("s1", "shell", """{"command":"ls -la","timeout_sec":15}"""),
                ), rawCalls("w1", "file_write", "s1", "shell"))
            } else {
                assertTrue(turnMessages.contains("OK: saved:index.html"))
                assertTrue(turnMessages.contains("OK: checks passed"))
                LlmReply("Criado index.html e verificado com ls.", emptyList(), null)
            }
        }
        val result = ConnectorTaskAgent("demo", model, bridge).run("Crie uma página", "test/model", 4)
        assertTrue(result.text, result.ok)
        assertEquals("<main>Open Dev</main>", bridge.files["index.html"])
        assertTrue(bridge.commands.contains("ls -la"))
        assertEquals(listOf("test/model", "test/model"), model.models)
        assertFalse(model.observedToolSets.flatten().any { it.contains("browser") || it.startsWith("uic_") || it.contains("wallet") })
    }

    @Test fun `delegated file API rejects traversal before bridge call`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.FILES))
        val model = ScriptedModel { turn, _, _ ->
            if (turn == 1) LlmReply(null, listOf(ToolCall("bad", "file_write", """{"path":"../outside.txt","content":"x"}""")), rawCalls("bad", "file_write"))
            else {
                assertTrue(turnMessages.contains("caminho inválido"))
                LlmReply("Recusei o caminho fora do workspace.", emptyList(), null)
            }
        }
        val result = ConnectorTaskAgent("demo", model, bridge).run("Crie um arquivo", null, 3)
        assertTrue(result.ok)
        assertTrue(bridge.files.isEmpty())
        assertEquals(0, bridge.fileCalls)
    }

    @Test fun `revoked capability is checked again at tool execution`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.FILES))
        val model = ScriptedModel { turn, _, _ ->
            if (turn == 1) {
                bridge.grants.clear()
                LlmReply(null, listOf(ToolCall("revoked", "file_write", """{"path":"index.html","content":"x"}""")), rawCalls("revoked", "file_write"))
            } else {
                assertTrue(turnMessages.contains("autorização de arquivos foi revogada"))
                LlmReply("Não fiz alterações porque a permissão foi revogada.", emptyList(), null)
            }
        }
        val result = ConnectorTaskAgent("demo", model, bridge).run("Crie uma página", null, 3)
        assertTrue(result.ok)
        assertTrue(bridge.files.isEmpty())
        assertEquals(0, bridge.fileCalls)
    }

    @Test fun `offline dry-run approves connector without calling a live model`() = runBlocking {
        val result = ConnectorDryRunValidator.validate(LocalDevStudioTemplate.YAML, CustomDefParser.parse(LocalDevStudioTemplate.YAML).def!!)
        assertTrue(result.report, result.passed)
        assertTrue(result.report, result.report.contains("nenhum modelo, terminal ou arquivo real foi chamado"))
    }


    @Test fun `agent only grant exposes no tools and cannot inherit parent tools`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.AGENT))
        val model = ScriptedModel { _, tools, _ ->
            assertTrue(tools.isEmpty())
            LlmReply("Revisão sem ferramentas concluída.", emptyList(), null)
        }
        val result = ConnectorTaskAgent("demo", model, bridge).run("Revise a especificação", null, 2)
        assertTrue(result.text, result.ok)
        assertEquals(listOf(emptySet<String>()), model.observedToolSets)
        assertTrue(bridge.commands.isEmpty())
        assertEquals(0, bridge.fileCalls)
    }

    @Test fun `unknown browser tool call is refused even when terminal and files are authorized`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL, Capabilities.FILES))
        val model = ScriptedModel { turn, _, _ ->
            if (turn == 1) LlmReply(null, listOf(ToolCall("browser", "browser_open", "{}")), rawCalls("browser", "browser_open"))
            else {
                assertTrue(turnMessages.contains("ferramenta não permitida"))
                LlmReply("Nenhum browser foi aberto.", emptyList(), null)
            }
        }
        val result = ConnectorTaskAgent("demo", model, bridge).run("Abra uma página", null, 3)
        assertTrue(result.ok)
        assertTrue(bridge.commands.isEmpty())
        assertEquals(0, bridge.fileCalls)
    }

    @Test fun `absolute scheme and traversal paths never reach file bridge`() = runBlocking {
        val badPaths = listOf("/etc/passwd", "a/../../outside", "file:///secret", "https://example.test/x", "folder\\escape")
        for (badPath in badPaths) {
            val bridge = FakeBridge(mutableSetOf(Capabilities.FILES))
            val args = buildJsonObject { put("path", badPath); put("content", "x") }.toString()
            val model = ScriptedModel { turn, _, _ ->
                if (turn == 1) LlmReply(null, listOf(ToolCall("bad", "file_write", args)), rawCalls("bad", "file_write"))
                else {
                    assertTrue("path=$badPath; $turnMessages", turnMessages.contains("caminho inválido"))
                    LlmReply("Caminho recusado.", emptyList(), null)
                }
            }
            val result = ConnectorTaskAgent("demo", model, bridge).run("Crie um arquivo", null, 3)
            assertTrue("path=$badPath; ${result.text}", result.ok)
            assertEquals("path=$badPath", 0, bridge.fileCalls)
            assertTrue("path=$badPath", bridge.files.isEmpty())
        }
    }

    @Test fun `step limit remains while usage is not capped by an internal token budget`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL))
        val stepModel = ScriptedModel { _, _, _ ->
            LlmReply(null, listOf(ToolCall("cmd", "shell", """{"command":"pwd","timeout_sec":5}""")), rawCalls("cmd", "shell"))
        }
        val stepResult = ConnectorTaskAgent("demo", stepModel, bridge).run("Verifique", null, 1)
        assertFalse(stepResult.ok)
        assertTrue(stepResult.text.contains("limite de 1 passos"))
        assertEquals(1, stepModel.calls)
        assertEquals(listOf("pwd"), bridge.commands)

        val usageModel = ScriptedModel { _, _, _ -> LlmReply("OK", emptyList(), null, Usage(prompt = 900_000, completion = 200_000)) }
        val usageResult = ConnectorTaskAgent("demo", usageModel, FakeBridge(mutableSetOf())).run("Resuma", null, 2)
        assertTrue(usageResult.text, usageResult.ok)
        assertEquals(listOf(null), usageModel.maxTokenRequests)
    }

    @Test fun `unbounded task continues past the former twelve step ceiling`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL))
        val iterations = ConnectorTaskAgent.MAX_STEPS + 1
        val model = ScriptedModel { turn, _, _ ->
            if (turn <= iterations) {
                val id = "cmd$turn"
                LlmReply(null, listOf(ToolCall(id, "shell", """{"command":"pwd","timeout_sec":5}""")), rawCalls(id, "shell"))
            } else {
                LlmReply("Concluído após $iterations verificações.", emptyList(), null)
            }
        }
        val result = ConnectorTaskAgent("fluwyopendev", model, bridge, LocalDevStudioTemplate.UNBOUNDED_TOOL)
            .run("Desenvolva e verifique", null, maxSteps = null)
        assertTrue(result.text, result.ok)
        assertEquals(iterations + 1, model.calls)
        assertEquals(iterations, bridge.commands.size)
    }

    @Test fun `each exact Open Dev flow can progress beyond 24 tool calls`() = runBlocking {
        val callCount = ConnectorTaskAgent.MAX_TOOL_CALLS + 6
        for (toolName in LocalDevStudioTemplate.UNBOUNDED_AGENT_TOOLS) {
            val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL))
            val model = ScriptedModel { turn, _, _ ->
                if (turn <= callCount) {
                    val id = "cmd$turn"
                    LlmReply("Progresso $turn", listOf(ToolCall(id, "shell", """{"command":"echo $turn","timeout_sec":5}""")), rawCalls(id, "shell"))
                } else LlmReply("Concluído após $callCount chamadas de ferramenta.", emptyList(), null)
            }

            val result = ConnectorTaskAgent(LocalDevStudioTemplate.ID, model, bridge, toolName)
                .run("Execute as verificações em progresso", null, maxSteps = null)

            assertTrue("$toolName: ${result.text}", result.ok)
            assertEquals("$toolName executou as chamadas além do teto antigo", callCount, bridge.commands.size)
            assertEquals(callCount + 1, model.calls)
            assertTrue("$toolName deve manter as ferramentas disponíveis após 24 chamadas", model.observedToolSets[24].contains("shell"))
        }
    }

    @Test fun `24-call ceiling remains for other connectors and non-Open-Dev tool names`() = runBlocking {
        for ((connectorId, toolName) in listOf("demo" to null, LocalDevStudioTemplate.ID to "outra_ferramenta")) {
            val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL))
            val model = ScriptedModel { turn, tools, _ ->
                if (tools.isEmpty()) LlmReply("Resumo final após o limite local.", emptyList(), null)
                else {
                    val id = "cmd$turn"
                    LlmReply("Progresso $turn", listOf(ToolCall(id, "shell", """{"command":"pwd","timeout_sec":5}""")), rawCalls(id, "shell"))
                }
            }

            val result = ConnectorTaskAgent(connectorId, model, bridge, toolName)
                .run("Execute verificações", null, maxSteps = null)

            assertTrue(result.text, result.ok)
            assertTrue(result.text, result.text.contains("Resumo final"))
            assertEquals(ConnectorTaskAgent.MAX_TOOL_CALLS, bridge.commands.size)
            assertEquals(ConnectorTaskAgent.MAX_TOOL_CALLS + 1, model.calls)
            assertTrue(model.observedToolSets.last().isEmpty())
        }
    }

    @Test fun `repeated identical tool failure stops as a no-progress cycle without capping steps`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL)).apply {
            shellResult = BridgeResult(false, "runner exit code 1")
        }
        val model = ScriptedModel { turn, _, _ ->
            val id = "cmd$turn"
            LlmReply(null, listOf(ToolCall(id, "shell", """{"command":"pwd","timeout_sec":5}""")), rawCalls(id, "shell"))
        }

        val result = ConnectorTaskAgent("fluwyopendev", model, bridge).run("Investigue a revisão", null, maxSteps = null)

        assertFalse(result.ok)
        assertTrue(result.text, result.text.contains("ciclo sem progresso"))
        assertEquals(2, model.calls)
        assertEquals(2, bridge.commands.size)
    }

    @Test fun `identical truncated response is stopped but distinct continuation can continue indefinitely`() = runBlocking {
        val stalled = ScriptedModel { _, _, _ -> LlmReply("mesmo trecho", emptyList(), null, truncated = true) }
        val stalledResult = ConnectorTaskAgent("fluwyopendev", stalled, FakeBridge(mutableSetOf())).run("Resuma", null, maxSteps = null)
        assertFalse(stalledResult.ok)
        assertTrue(stalledResult.text, stalledResult.text.contains("se repetiu sem progresso"))
        assertEquals(2, stalled.calls)

        val continuations = 48
        val progressing = ScriptedModel { turn, _, _ ->
            if (turn <= continuations) LlmReply("parte $turn", emptyList(), null, truncated = true)
            else LlmReply("Resumo concluído após $continuations continuações.", emptyList(), null)
        }
        val completed = ConnectorTaskAgent("fluwyopendev", progressing, FakeBridge(mutableSetOf())).run("Continue", null, maxSteps = null)
        assertTrue(completed.text, completed.ok)
        assertEquals(continuations + 1, progressing.calls)
    }

    @Test fun `known native context and completion limits determine per-call max tokens`() = runBlocking {
        val model = ScriptedModel { _, _, _ -> LlmReply("Concluído", emptyList(), null) }
        val result = ConnectorTaskAgent("demo", model, FakeBridge(mutableSetOf()))
            .run("Resuma", null, 2, modelContextTokens = 4_096, modelMaxCompletionTokens = 12_000)
        assertTrue(result.text, result.ok)
        val requested = model.maxTokenRequests.single()!!
        assertTrue("não deve haver o antigo teto baixo de 2k: $requested", requested > 2_048)
        assertTrue(requested < 4_096)
    }

    @Test fun `old tool history is compacted only to fit a known native context window`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.FILES))
        bridge.files["large.txt"] = "x".repeat(30_000)
        val model = ScriptedModel { turn, _, messages ->
            if (turn == 1) LlmReply(null, listOf(ToolCall("read", "file_read", """{"path":"large.txt"}""")), rawCalls("read", "file_read"))
            else {
                val history = messages.joinToString("\n") { it.content.orEmpty() }
                assertTrue(history.contains("Histórico antigo de ferramentas compactado"))
                assertFalse(history.contains("x".repeat(100)))
                LlmReply("Concluído", emptyList(), null)
            }
        }
        val result = ConnectorTaskAgent("demo", model, bridge)
            .run("Inspecione o arquivo", null, 3, modelContextTokens = 4_096, modelMaxCompletionTokens = 8_000)
        assertTrue(result.text, result.ok)
        assertEquals(2, model.calls)
    }

    private fun toolNames(tools: JsonArray): Set<String> = tools.mapNotNull { (it as? JsonObject)?.get("function")?.let { f -> (f as? JsonObject)?.get("name") as? JsonPrimitive }?.content }.toSet()
    private fun rawCalls(vararg idsAndNames: String): String {
        val arr = buildJsonArray {
            idsAndNames.toList().chunked(2).forEach { (id, name) ->
                add(buildJsonObject {
                    put("id", id); put("type", "function")
                    put("function", buildJsonObject { put("name", name); put("arguments", "{}") })
                })
            }
        }
        return arr.toString()
    }

    private class CaptureBridge(private val grants: Set<String>) : HostBridge {
        var task: String? = null
        var model: String? = null
        var maxSteps: Int? = -1
        var timeoutSec: Int? = null
        var toolName: String? = null
        override fun granted(capability: String) = capability in grants
        override suspend fun shell(command: String, timeoutSec: Int) = BridgeResult(true, "shell")
        override suspend fun fileOp(op: String, path: String, content: String?) = BridgeResult(true, "file")
        override suspend fun fetch(url: String, waitSec: Int) = BridgeResult(false, "no browser")
        override suspend fun runAgentTask(task: String, model: String?, maxSteps: Int?, timeoutSec: Int, toolName: String?): BridgeResult {
            this.task = task; this.model = model; this.maxSteps = maxSteps; this.timeoutSec = timeoutSec; this.toolName = toolName
            return BridgeResult(true, "Concluído localmente")
        }
    }

    private class FakeBridge(val grants: MutableSet<String>) : HostBridge {
        val files = linkedMapOf<String, String>()
        val commands = arrayListOf<String>()
        var fileCalls = 0
        var shellResult = BridgeResult(true, "checks passed")
        override fun granted(capability: String) = capability in grants
        override suspend fun shell(command: String, timeoutSec: Int) = BridgeResult(true, "shell")
        override suspend fun agentShell(command: String, timeoutSec: Int): BridgeResult {
            commands += command
            return shellResult
        }
        override suspend fun fileOp(op: String, path: String, content: String?): BridgeResult {
            fileCalls++
            return when (op) {
                "write" -> { files[path] = content.orEmpty(); BridgeResult(true, "saved:$path") }
                "read" -> files[path]?.let { BridgeResult(true, it) } ?: BridgeResult(false, "missing")
                "list" -> BridgeResult(true, files.keys.joinToString())
                else -> BridgeResult(false, "unsupported")
            }
        }
        override suspend fun fetch(url: String, waitSec: Int) = BridgeResult(false, "no browser")
    }

    private class ScriptedModel(
        private val next: ScriptedModel.(turn: Int, tools: JsonArray, messages: List<LlmMessage>) -> LlmReply,
    ) : ConnectorTaskModel {
        var calls = 0
        val models = arrayListOf<String?>()
        val maxTokenRequests = arrayListOf<Int?>()
        val observedToolSets = arrayListOf<Set<String>>()
        var turnMessages: String = ""
        override suspend fun chat(messages: List<LlmMessage>, tools: JsonArray, model: String?, maxTokens: Int?, conversationId: String): LlmReply {
            calls++
            models += model
            maxTokenRequests += maxTokens
            observedToolSets += tools.mapNotNull { (it as? JsonObject)?.get("function")?.let { f -> (f as? JsonObject)?.get("name") as? JsonPrimitive }?.content }.toSet()
            turnMessages = messages.joinToString("\n") { it.content.orEmpty() }
            return next(calls, tools, messages)
        }
    }
}
