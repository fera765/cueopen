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

    @Test fun `local dev studio template parses with bounded agent tasks`() {
        val parsed = CustomDefParser.parse(LocalDevStudioTemplate.YAML)
        assertEquals(parsed.errors.toString(), emptyList<String>(), parsed.errors)
        val def = parsed.def!!
        assertEquals("fluwyopendev", def.id)
        assertEquals(setOf(Capabilities.AGENT, Capabilities.TERMINAL, Capabilities.FILES), def.capabilities)
        assertEquals(3, def.tools.size)
        assertTrue(def.tools.all { it.agent != null && it.agent!!.maxSteps in 1..12 && it.agent!!.budgetTokens <= 12_000 })
        assertTrue(ConnectorGuardrails.staticCheck(def).none { it.level == ConnectorGuardrails.Level.ERROR })
    }

    @Test fun `agent task requires opt-in risky declaration and bounded values`() {
        val base = """{"id":"tasker","name":"Tasker","description":"local", "capabilities":["agent"],
          "tools":[{"name":"run_task","description":"delegates", "params":[{"name":"pedido","description":"task"}],
          "risky":true,"agent":{"task":"Faça {{pedido}}","maxSteps":4,"budgetTokens":2000,"timeoutSec":60}}]}"""
        assertTrue(CustomDefParser.parse(base).errors.isEmpty())
        assertTrue(CustomDefParser.parse(base.replace("\"agent\"],", "\"terminal\"],")).errors.any { it.contains("agent") })
        assertTrue(CustomDefParser.parse(base.replace("\"risky\":true", "\"risky\":false")).errors.any { it.contains("risky") })
        assertTrue(CustomDefParser.parse(base.replace("\"maxSteps\":4", "\"maxSteps\":99")).errors.any { it.contains("maxSteps") })
        assertTrue(CustomDefParser.parse(base.replace("\"budgetTokens\":2000", "\"budgetTokens\":10")).errors.any { it.contains("budgetTokens") })
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
        val result = ConnectorTaskAgent("demo", model, bridge).run("Crie uma página", "test/model", 4, 6_000)
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
        val result = ConnectorTaskAgent("demo", model, bridge).run("Crie um arquivo", null, 3, 4_000)
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
        val result = ConnectorTaskAgent("demo", model, bridge).run("Crie uma página", null, 3, 4_000)
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
        val result = ConnectorTaskAgent("demo", model, bridge).run("Revise a especificação", null, 2, 1_000)
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
        val result = ConnectorTaskAgent("demo", model, bridge).run("Abra uma página", null, 3, 4_000)
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
            val result = ConnectorTaskAgent("demo", model, bridge).run("Crie um arquivo", null, 3, 4_000)
            assertTrue("path=$badPath; ${result.text}", result.ok)
            assertEquals("path=$badPath", 0, bridge.fileCalls)
            assertTrue("path=$badPath", bridge.files.isEmpty())
        }
    }

    @Test fun `step and token budgets stop the delegated loop`() = runBlocking {
        val bridge = FakeBridge(mutableSetOf(Capabilities.TERMINAL))
        val stepModel = ScriptedModel { _, _, _ ->
            LlmReply(null, listOf(ToolCall("cmd", "shell", """{"command":"pwd","timeout_sec":5}""")), rawCalls("cmd", "shell"))
        }
        val stepResult = ConnectorTaskAgent("demo", stepModel, bridge).run("Verifique", null, 1, 4_000)
        assertFalse(stepResult.ok)
        assertTrue(stepResult.text.contains("limite de 1 passos"))
        assertEquals(1, stepModel.calls)
        assertEquals(listOf("pwd"), bridge.commands)

        val tokenModel = ScriptedModel { _, _, _ -> LlmReply("x".repeat(2_200), emptyList(), null) }
        val tokenResult = ConnectorTaskAgent("demo", tokenModel, FakeBridge(mutableSetOf())).run("Resuma", null, 2, 1_000)
        assertFalse(tokenResult.ok)
        assertTrue(tokenResult.text.contains("orçamento total de tokens do agente excedido"))

        val reportedUsageModel = ScriptedModel { _, _, _ -> LlmReply("OK", emptyList(), null, Usage(prompt = 900, completion = 200)) }
        val reportedUsage = ConnectorTaskAgent("demo", reportedUsageModel, FakeBridge(mutableSetOf())).run("Resuma", null, 2, 1_000)
        assertFalse(reportedUsage.ok)
        assertTrue(reportedUsage.text.contains("orçamento total de tokens do agente excedido"))
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
        override fun granted(capability: String) = capability in grants
        override suspend fun shell(command: String, timeoutSec: Int) = BridgeResult(true, "shell")
        override suspend fun fileOp(op: String, path: String, content: String?) = BridgeResult(true, "file")
        override suspend fun fetch(url: String, waitSec: Int) = BridgeResult(false, "no browser")
        override suspend fun runAgentTask(task: String, model: String?, maxSteps: Int, budgetTokens: Int, timeoutSec: Int): BridgeResult {
            this.task = task; this.model = model
            return BridgeResult(true, "Concluído localmente")
        }
    }

    private class FakeBridge(val grants: MutableSet<String>) : HostBridge {
        val files = linkedMapOf<String, String>()
        val commands = arrayListOf<String>()
        var fileCalls = 0
        override fun granted(capability: String) = capability in grants
        override suspend fun shell(command: String, timeoutSec: Int) = BridgeResult(true, "shell")
        override suspend fun agentShell(command: String, timeoutSec: Int): BridgeResult {
            commands += command
            return BridgeResult(true, "checks passed")
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
        val observedToolSets = arrayListOf<Set<String>>()
        var turnMessages: String = ""
        override suspend fun chat(messages: List<LlmMessage>, tools: JsonArray, model: String?, maxTokens: Int, conversationId: String): LlmReply {
            calls++
            models += model
            observedToolSets += tools.mapNotNull { (it as? JsonObject)?.get("function")?.let { f -> (f as? JsonObject)?.get("name") as? JsonPrimitive }?.content }.toSet()
            turnMessages = messages.joinToString("\n") { it.content.orEmpty() }
            return next(calls, tools, messages)
        }
    }
}
