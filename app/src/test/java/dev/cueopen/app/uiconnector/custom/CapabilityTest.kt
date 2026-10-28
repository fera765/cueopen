package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.uiconnector.ConnectorSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapabilityTest {

    // -------------------------------------------------- escape de shell (anti-injeção)
    @Test fun shellQuoteNeutralizesInjection() {
        val ctx = Template.Ctx(mapOf("url" to "https://x; rm -rf / #", "q" to "a'b"))
        val cmd = Template.renderShell("yt-dlp {{url}} -o v.mp4 && echo {{q}}", ctx)
        // o valor perigoso entra inteiro entre aspas simples; o `;` e o `rm` não são sintaxe do shell
        assertEquals("yt-dlp 'https://x; rm -rf / #' -o v.mp4 && echo 'a'\\''b'", cmd)
        // texto literal do autor (&&, flags) é preservado
        assertTrue(cmd.contains(" && echo "))
    }

    @Test fun shellQuoteEmptyValue() {
        assertEquals("rm ''", Template.renderShell("rm {{x}}", Template.Ctx(emptyMap())))
    }

    // -------------------------------------------------- confinamento de arquivos
    @Test fun confineBlocksEscapes() {
        val root = File(System.getProperty("java.io.tmpdir"), "uic-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            assertNull(BridgeSecurity.confine(root, "../etc/passwd"))
            assertNull(BridgeSecurity.confine(root, "/etc/passwd"))
            assertNull(BridgeSecurity.confine(root, "a/../../b"))
            assertNull(BridgeSecurity.confine(root, "x\u0000y"))
            val ok = BridgeSecurity.confine(root, "sub/dir/file.txt")
            assertTrue(ok != null && ok.path.startsWith(root.path))
            assertEquals(root, BridgeSecurity.confine(root, ""))
        } finally { root.deleteRecursively() }
    }

    // -------------------------------------------------- validação do parser
    private fun errs(j: String) = CustomDefParser.parse(j).errors.joinToString("\n")

    @Test fun shellRequiresCapabilityAndRisky() {
        // sem "terminal" em capabilities e sem risky → dois erros
        val bad = """{"id":"t","name":"T","description":"d","tools":[
            {"name":"run","description":"d","shell":{"run":"ls"}}]}"""
        val e = errs(bad)
        assertTrue(e, e.contains("terminal") && e.contains("risky"))
    }

    @Test fun fileWriteRequiresFilesCapAndRisky() {
        val bad = """{"id":"t","name":"T","description":"d","tools":[
            {"name":"w","description":"d","file":{"op":"write","path":"a.txt","content":"x"}}]}"""
        val e = errs(bad)
        assertTrue(e, e.contains("files") && e.contains("risky"))
    }

    @Test fun fetchRequiresBrowserCapAndDeclaredHost() {
        val bad = """{"id":"t","name":"T","description":"d","baseUrl":"https://api.exemplo.com","capabilities":["browser"],
            "login":{"fields":[{"id":"k","label":"K","type":"password"}]},
            "tools":[{"name":"f","description":"d","fetch":{"url":"https://evil.com/x"}}]}"""
        assertTrue(errs(bad).contains("evil.com"))
    }

    @Test fun setupRequiresTerminalCapability() {
        val bad = """{"id":"t","name":"T","description":"d","setup":{"run":"pip install x"},"tools":[]}"""
        assertTrue(errs(bad).contains("terminal"))
    }

    @Test fun setupTimeoutRange() {
        val bad = """{"id":"t","name":"T","description":"d","capabilities":["terminal"],"setup":{"run":"x","timeoutSec":5000},"tools":[]}"""
        assertTrue(errs(bad).contains("timeoutSec"))
    }

    @Test fun capabilityMustBeKnown() {
        val bad = """{"id":"t","name":"T","description":"d","capabilities":["root"],"tools":[]}"""
        assertTrue(errs(bad).contains("capabilities"))
    }

    @Test fun shellFilesParseAndRunBeforeCommand() = runBlocking {
        val json = """{"id":"gen","name":"G","description":"gera","capabilities":["terminal"],
            "tools":[{"name":"gerar","description":"gera arquivo","params":[{"name":"txt","description":"t"}],
              "risky":true,"shell":{"files":[{"path":"a.py","content":"print('x')"}],"run":"python3 a.py"},"response":"{{$.body}}"}]}"""
        val p = CustomDefParser.parse(json)
        assertEquals(p.errors.toString(), emptyList<String>(), p.errors)
        val def = p.def!!
        val writes = ArrayList<String>()
        val bridge = object : HostBridge {
            override fun granted(capability: String) = true
            override suspend fun shell(command: String, timeoutSec: Int) = BridgeResult(true, "ok")
            override suspend fun fileOp(op: String, path: String, content: String?): BridgeResult { writes.add("$op:$path"); return BridgeResult(true, "w") }
            override suspend fun fetch(url: String, waitSec: Int) = BridgeResult(true, "")
        }
        val out = CustomConnector(def, http = NoHttp, bridge = bridge).callTool("gerar", mapOf("txt" to "oi"), noSettings())
        assertTrue(writes.toString(), writes.contains("write:a.py")) // arquivo gravado ANTES do comando
        assertTrue(out, out.contains("ok"))
    }

    @Test fun validTerminalConnectorParses() {
        val ok = """{"id":"yt","name":"YT","description":"baixa","capabilities":["terminal","files"],
            "tools":[
              {"name":"baixar","description":"baixa video","params":[{"name":"url","description":"url"}],
               "risky":true,"shell":{"run":"yt-dlp {{url}}","timeoutSec":60},"response":"{{$.body|trunc:100}}"},
              {"name":"arquivos","description":"lista","file":{"op":"list","path":""}}
            ]}"""
        assertEquals(emptyList<String>(), CustomDefParser.parse(ok).errors)
    }

    // -------------------------------------------------- execução com bridge falso
    private class FakeBridge(
        val grant: Set<String>,
        var lastCmd: String? = null,
    ) : HostBridge {
        override fun granted(capability: String) = capability in grant
        override suspend fun shell(command: String, timeoutSec: Int): BridgeResult {
            lastCmd = command
            return BridgeResult(true, "baixado ok")
        }
        override suspend fun fileOp(op: String, path: String, content: String?) = BridgeResult(true, "lista: a.txt")
        override suspend fun fetch(url: String, waitSec: Int) = BridgeResult(true, "pagina")
    }

    private object NoHttp : HttpExec {
        override suspend fun exec(r: HttpReq) = HttpRes(200, emptyMap(), "")
    }
    private fun ytDef(): CustomDef = CustomDefParser.parse(CustomTemplates.YT_DOWNLOAD.json).def!!
    private fun conn(bridge: HostBridge) = CustomConnector(ytDef(), http = NoHttp, bridge = bridge)
    private fun noSettings() = ConnectorSettings { null }

    @Test fun shellToolRunsWhenGranted() = runBlocking {
        val bridge = FakeBridge(setOf("terminal", "files"))
        val c = conn(bridge)
        val out = c.callTool("baixar", mapOf("url" to "https://youtu.be/abc; rm -rf /"), noSettings())
        // o comando chegou à ponte com a URL perigosa entre aspas (sem virar comando)
        assertTrue(bridge.lastCmd ?: "", (bridge.lastCmd ?: "").contains("'https://youtu.be/abc; rm -rf /'"))
        assertTrue(out, out.contains("baixado ok"))
    }

    @Test fun shellToolDeniedWithoutGrant() = runBlocking {
        val c = conn(FakeBridge(emptySet()))
        val out = c.callTool("baixar", mapOf("url" to "https://youtu.be/abc"), noSettings())
        assertTrue(out, out.startsWith("RECUSADO") && out.contains("autoriza"))
    }

    @Test fun defaultBridgeDeniesEverything() = runBlocking {
        val c = conn(NoHostBridge)
        val out = c.callTool("baixar", mapOf("url" to "https://youtu.be/abc"), noSettings())
        assertTrue(out, out.startsWith("RECUSADO"))
    }

    @Test fun fileListToolRuns() = runBlocking {
        val c = conn(FakeBridge(setOf("files")))
        val out = c.callTool("arquivos", emptyMap(), noSettings())
        assertTrue(out, out.contains("a.txt"))
    }
}
