package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.uiconnector.ConnectorSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardrails do criador de conectores: o conector só pode ser proposto depois que setup, comandos,
 * scripts e ferramentas de terminal/arquivos foram validados de verdade.
 */
class ConnectorGuardrailsTest {

    private fun def(json: String): CustomDef {
        val p = CustomDefParser.parse(json)
        assertEquals(p.errors.toString(), emptyList<String>(), p.errors)
        return p.def!!
    }

    private fun errors(json: String) = ConnectorGuardrails.staticCheck(def(json)).filter { it.level == ConnectorGuardrails.Level.ERROR }.joinToString("\n")

    private fun shellTool(run: String, files: String = "", extra: String = "") = """{"id":"gtest","name":"G","description":"d","capabilities":["terminal"]$extra,
        "tools":[{"name":"rodar","description":"roda","params":[{"name":"url","description":"u"}],"risky":true,
          "shell":{${if (files.isNotEmpty()) "\"files\":$files," else ""}"run":${kotlinx.serialization.json.JsonPrimitive(run)}},"response":"{{$.body}}"}]}"""

    // -------------------------------------------------- estático
    @Test fun builtInTemplatesPassGuardrails() {
        for (t in CustomTemplates.ALL) {
            val p = CustomDefParser.parse(t.json)
            val d = p.def ?: continue
            val errs = ConnectorGuardrails.staticCheck(d).filter { it.level == ConnectorGuardrails.Level.ERROR }
            assertTrue("${t.title}: $errs", errs.isEmpty())
        }
    }

    @Test fun heredocInRunIsBlocked() {
        assertTrue(errors(shellTool("cat > a.py <<EOF\nprint(1)\nEOF\npython3 a.py")).contains("heredoc"))
    }

    @Test fun templateInsideQuotesIsBlocked() {
        assertTrue(errors(shellTool("yt-dlp '{{url}}'")).contains("{{url}}"))
        assertTrue(errors(shellTool("yt-dlp \"{{url}}\"")).contains("{{url}}"))
        assertEquals("", errors(shellTool("yt-dlp -o 'v.%(ext)s' {{url}}")))
    }

    @Test fun nonAlpinePackageManagersAndSudoAreBlocked() {
        assertTrue(errors(shellTool("ls", extra = ""","setup":{"run":"apt-get install -y ffmpeg"}""")).contains("Alpine"))
        assertTrue(errors(shellTool("sudo ls")).contains("sudo"))
    }

    @Test fun pipNeedsBreakSystemPackages() {
        assertTrue(errors(shellTool("ls", extra = ""","setup":{"run":"pip install yt-dlp"}""")).contains("break-system-packages"))
        assertEquals("", errors(shellTool("ls", extra = ""","setup":{"run":"pip install --break-system-packages -q yt-dlp"}""")))
    }

    @Test fun hidingFailuresAndBackgroundAreBlocked() {
        assertTrue(errors(shellTool("python3 a.py || true", files = """[{"path":"a.py","content":"print(1)"}]""")).contains("|| true"))
        assertTrue(errors(shellTool("python3 a.py &", files = """[{"path":"a.py","content":"print(1)"}]""")).contains("segundo plano"))
    }

    @Test fun interactiveAndDangerousCommandsAreBlocked() {
        assertTrue(errors(shellTool("read nome && echo ok")).contains("read"))
        assertTrue(errors(shellTool("nano a.txt")).contains("interativo"))
        assertTrue(errors(shellTool("rm -rf / ")).contains("rm"))
    }

    @Test fun unbalancedQuotesAreBlocked() {
        assertTrue(errors(shellTool("echo 'oi && ls")).contains("aspas"))
    }

    @Test fun commandsAndScriptsAreExtracted() {
        assertEquals(setOf("yt-dlp", "ls"), ConnectorGuardrails.externalCommands("yt-dlp --no-playlist -f 'best[ext=mp4]/best' -o 'download.%(ext)s' {{url}} && ls -la"))
        assertEquals(setOf("python3", "ls"), ConnectorGuardrails.externalCommands("TITULO={{titulo}} TOPICOS={{topicos}} python3 gerar.py && ls -la slides.html"))
        assertEquals(setOf("apk", "pip"), ConnectorGuardrails.externalCommands("command -v yt-dlp >/dev/null 2>&1 || { apk add --no-cache python3; pip install --break-system-packages -q yt-dlp; }"))
        assertEquals(setOf("gerar.py"), ConnectorGuardrails.scriptsReferenced("TITULO={{titulo}} python3 gerar.py && ls"))
        assertEquals(setOf("run.sh"), ConnectorGuardrails.scriptsReferenced("bash ./run.sh {{url}}"))
    }

    @Test fun hiddenFailuresAreDetected() {
        assertTrue(ConnectorGuardrails.hiddenFailure("bash: yt-dlp: command not found\n[exit 0]") != null)
        assertTrue(ConnectorGuardrails.hiddenFailure("Traceback (most recent call last):\n  File \"a.py\"") != null)
        assertTrue(ConnectorGuardrails.hiddenFailure("sh: ffmpeg: not found") != null)
        assertNull(ConnectorGuardrails.hiddenFailure("slides.html com 3 slides\n[exit 0]"))
    }

    @Test fun commandsRunIsolatedWithoutStdin() {
        val c = ConnectorGuardrails.isolate("cd sub && exit 3")
        assertEquals("cd /root/workspace 2>/dev/null; bash -c 'cd sub && exit 3' < /dev/null", c)
    }

    @Test fun externalEffectToolsNeverRunInValidation() {
        val d = def("""{"id":"mail","name":"M","description":"d","capabilities":["terminal"],
            "tools":[{"name":"enviar_email","description":"envia um e-mail","risky":true,"shell":{"run":"ls"}},
                     {"name":"listar","description":"lista","risky":true,"shell":{"run":"ls"}}]}""")
        assertFalse(ConnectorGuardrails.executableInValidation(d.tools[0]))
        assertTrue(ConnectorGuardrails.executableInValidation(d.tools[1]))
    }

    // -------------------------------------------------- validação executada (ponte falsa)
    private class ValidationBridge(
        val missing: Set<String> = emptySet(),
        val toolOutput: (String) -> BridgeResult = { BridgeResult(true, "ok\n[exit 0]") },
        val syntaxOk: Boolean = true,
        val setupOk: Boolean = true,
    ) : HostBridge {
        val toolRuns = ArrayList<String>()
        val writes = ArrayList<String>()
        var setupRuns = 0
        override fun granted(capability: String) = true
        override suspend fun ensureSetup(command: String, timeoutSec: Int): BridgeResult { setupRuns++; return BridgeResult(setupOk, if (setupOk) "OK" else "setup falhou (exit 1). ERROR") }
        override suspend fun shell(command: String, timeoutSec: Int): BridgeResult = when {
            command.startsWith("echo cue-validate-ok") -> BridgeResult(true, "cue-validate-ok")
            command.startsWith("for c in") -> BridgeResult(true, missing.joinToString("") { "FALTA:$it\n" } + "fim")
            command.startsWith("bash -n") || command.startsWith("python3 -c 'import ast") || command.startsWith("node --check") ->
                BridgeResult(syntaxOk, if (syntaxOk) "" else "SyntaxError: invalid syntax")
            else -> { toolRuns.add(command); toolOutput(command) }
        }
        override suspend fun fileOp(op: String, path: String, content: String?): BridgeResult { writes.add("$op:$path"); return BridgeResult(true, if (op == "read") "ok" else "OK") }
        override suspend fun fetch(url: String, waitSec: Int) = BridgeResult(true, "pagina")
    }

    private object NoHttp : HttpExec { override suspend fun exec(r: HttpReq) = HttpRes(200, emptyMap(), "{}") }
    private val noSettings = ConnectorSettings { null }
    private val slidesArgs = mapOf("criar_slides" to mapOf("titulo" to "Odonto", "topicos" to "a;b"))
    private fun slides() = def(CustomTemplates.FILE_GEN.json)

    @Test fun validConnectorPassesAndRunsToolTwice() = runBlocking {
        val b = ValidationBridge()
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = b).selfTest(noSettings, slidesArgs)
        assertTrue(report, ok)
        assertTrue(b.setupRuns >= 1)
        assertEquals(report, 2, b.toolRuns.size) // idempotência: rodou duas vezes
        assertTrue(b.writes.contains("write:gerar.py"))
        assertTrue(report, report.contains("python3"))
    }

    @Test fun missingCommandAfterSetupBlocks() = runBlocking {
        val b = ValidationBridge(missing = setOf("python3"))
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = b).selfTest(noSettings, slidesArgs)
        assertFalse(report, ok)
        assertTrue(report, report.contains("inexistente") && report.contains("python3"))
        assertEquals("a ferramenta não deve rodar sem os comandos", 0, b.toolRuns.size)
    }

    @Test fun failingSetupBlocks() = runBlocking {
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = ValidationBridge(setupOk = false)).selfTest(noSettings, slidesArgs)
        assertFalse(report, ok)
        assertTrue(report, report.contains("setup falhou"))
    }

    @Test fun scriptSyntaxErrorBlocks() = runBlocking {
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = ValidationBridge(syntaxOk = false)).selfTest(noSettings, slidesArgs)
        assertFalse(report, ok)
        assertTrue(report, report.contains("sintaxe"))
    }

    @Test fun hiddenFailureWithExitZeroBlocks() = runBlocking {
        val b = ValidationBridge(toolOutput = { BridgeResult(true, "Traceback (most recent call last):\nModuleNotFoundError: No module named 'pptx'\n[exit 0]") })
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = b).selfTest(noSettings, slidesArgs)
        assertFalse(report, ok)
        assertTrue(report, report.contains("ModuleNotFoundError") || report.contains("Traceback"))
    }

    @Test fun secondRunFailureBlocks() = runBlocking {
        var n = 0
        val b = ValidationBridge(toolOutput = { n++; if (n == 1) BridgeResult(true, "ok") else BridgeResult(false, "mkdir: can't create directory 'out': File exists\n[exit 1]") })
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = b).selfTest(noSettings, slidesArgs)
        assertFalse(report, ok)
        assertTrue(report, report.contains("2ª execução"))
    }

    @Test fun missingExampleArgsBlock() = runBlocking {
        val (ok, report) = CustomConnector(slides(), http = NoHttp, bridge = ValidationBridge()).selfTest(noSettings, emptyMap())
        assertFalse(report, ok)
        assertTrue(report, report.contains("args_yaml"))
    }

    @Test fun loginConnectorStillValidatesTerminal() = runBlocking {
        val d = def("""{"id":"logt","name":"L","description":"d","baseUrl":"https://api.exemplo.com","capabilities":["terminal"],
            "login":{"fields":[{"id":"token","label":"Token","type":"password"}]},
            "setup":{"run":"command -v jq >/dev/null 2>&1 || apk add --no-cache jq","timeoutSec":300},
            "tools":[{"name":"versao","description":"versão do jq","risky":true,"shell":{"run":"jq --version"},"response":"{{$.body}}"}]}""")
        val bad = ValidationBridge(missing = setOf("jq"))
        val (ok1, r1) = CustomConnector(d, http = NoHttp, bridge = bad).selfTest(noSettings, emptyMap())
        assertFalse(r1, ok1)
        val good = ValidationBridge()
        val (ok2, r2) = CustomConnector(d, http = NoHttp, bridge = good).selfTest(noSettings, emptyMap())
        assertTrue(r2, ok2)
        assertTrue(r2, r2.contains("login"))
        assertEquals(2, good.toolRuns.size)
    }

    @Test fun staticErrorsFailTheTest() = runBlocking {
        val d = def(shellTool("yt-dlp '{{url}}'"))
        val (ok, report) = CustomConnector(d, http = NoHttp, bridge = ValidationBridge()).selfTest(noSettings, mapOf("rodar" to mapOf("url" to "https://youtu.be/x")))
        assertFalse(report, ok)
        assertTrue(report, report.contains("GUARDRAILS"))
    }
}
