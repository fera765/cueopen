package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.CueApp
import java.io.File

/**
 * Implementação Android da [HostBridge]. Dá a um UI Connector acesso SEGURO e ISOLADO a:
 *  - TERMINAL: comandos rodam no sandbox Linux, numa sessão/ambiente exclusivo do conector
 *    (`__uic__<id>`) — nunca no ambiente de um chat do usuário.
 *  - ARQUIVOS: lê/grava apenas dentro do workspace desse ambiente (ver [BridgeSecurity.confine]).
 *  - BROWSER: abre páginas https apenas dos hosts declarados pelo conector, numa aba própria.
 *
 * A autorização de cada capacidade é consultada AO VIVO em [granted] (o usuário pode revogar a
 * qualquer momento na tela do conector).
 */
class AndroidHostBridge(
    private val def: CustomDef,
    private val isGranted: (String) -> Boolean,
    /** Ambiente do sandbox. Produção: `__uic__<id>`; validação do criador: [validationSession]. */
    private val sessionId: String = "__uic__${def.id}",
) : HostBridge {

    companion object {
        /** Ambiente ISOLADO usado só para validar um conector antes da instalação (nunca o de produção). */
        fun validationSession(id: String) = "__uic__${id}__v"

        /**
         * Ponte do teste de validação: concede todas as capacidades DECLARADAS, mas só dentro do
         * ambiente descartável de validação. Limpa o workspace e a marca do setup antes, para provar que o conector
         * funciona a partir do zero (nada sobrando de execuções anteriores).
         */
        fun forValidation(def: CustomDef): AndroidHostBridge {
            val sid = validationSession(def.id)
            runCatching { CueApp.instance.sandbox.workspaceFor(sid).deleteRecursively() }
            // o setup roda de novo a cada teste: prova que ele prepara o ambiente sozinho
            runCatching { java.io.File(CueApp.instance.sandbox.publicFor(sid), ".setup-ok").delete() }
            return AndroidHostBridge(def, { true }, sid)
        }

        /** Libera terminal e browser do ambiente de validação (os arquivos ficam para inspeção). */
        fun releaseValidation(id: String) {
            val sid = validationSession(id)
            runCatching { CueApp.instance.terminals.close(sid) }
            runCatching { CueApp.instance.closeBrowser(sid) }
        }

        /** Apaga o ambiente de validação inteiro (ex.: depois que o conector foi instalado). */
        fun deleteValidation(id: String) {
            releaseValidation(id)
            runCatching { CueApp.instance.sandbox.deleteEnvironment(validationSession(id)) }
        }
    }

    override fun granted(capability: String): Boolean = capability in def.capabilities && isGranted(capability)

    private val app get() = CueApp.instance

    // ------------------------------------------------------------------ setup (prepara o ambiente 1x)
    override suspend fun ensureSetup(command: String, timeoutSec: Int): BridgeResult? {
        val sandbox = app.sandbox
        if (!sandbox.isInstalled()) return BridgeResult(false, "O sandbox Linux não está instalado. Abra Configurações → Sandbox para instalar o terminal.")
        sandbox.ensureEnvironment(sessionId)
        val marker = java.io.File(sandbox.publicFor(sessionId), ".setup-ok")
        val tag = Integer.toHexString(command.hashCode())
        if (runCatching { marker.readText().trim() == tag }.getOrDefault(false)) return null // já preparado
        return try {
            val r = app.terminals.get(sessionId).runAgentCommand(ConnectorGuardrails.isolate(command), BridgeSecurity.clampSetupTimeout(timeoutSec).toLong(), background = false)
            if (!r.timedOut && r.exitCode == 0) {
                runCatching { marker.writeText(tag) }
                BridgeResult(true, r.output.takeLast(4_000))
            } else {
                BridgeResult(false, (if (r.timedOut) "setup excedeu o tempo-limite. " else "setup falhou (exit ${r.exitCode}). ") + r.output.takeLast(4_000))
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            BridgeResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    override fun resetSetup() {
        runCatching { java.io.File(app.sandbox.publicFor(sessionId), ".setup-ok").delete() }
    }

    /**
     * Dentro do sandbox do conector, /root, /public e /home são o mesmo bind (tracebacks mostram
     * /public/workspace/...). Para o AGENTE (que roda em outro ambiente) esses arquivos ficam em
     * /root/connectors/<pasta>/workspace — reescreve os caminhos para ele conseguir abrir/copiar.
     */
    private fun agentPaths(text: String): String {
        val dir = app.sandbox.environmentRoot(sessionId).name
        val target = "/root/connectors/$dir/workspace"
        return text.replace(Regex("(?<![A-Za-z0-9_/])/(public|home|root)/workspace(?=[/\\s'\":]|$)"), target)
    }

    // ------------------------------------------------------------------ terminal
    override suspend fun shell(command: String, timeoutSec: Int): BridgeResult {
        val cmd = command.take(BridgeSecurity.MAX_CMD_CHARS)
        val sandbox = app.sandbox
        if (!sandbox.isInstalled()) return BridgeResult(false, "O sandbox Linux não está instalado. Abra Configurações → Sandbox para instalar o terminal.")
        sandbox.ensureEnvironment(sessionId)
        return try {
            // isolado: cwd sempre o workspace, processo bash próprio (cd/export/exit não vazam nem
            // matam a sessão) e stdin vazio (nada fica esperando digitação e travando até o timeout).
            val r = app.terminals.get(sessionId).runAgentCommand(ConnectorGuardrails.isolate(cmd), BridgeSecurity.clampTimeout(timeoutSec).toLong(), background = false)
            val failedQuiet = !r.timedOut && r.exitCode != 0 && r.output.isBlank()
            val body = buildString {
                append(agentPaths(dev.cueopen.app.agent.ShellOutput.compact(r.output)).ifBlank { "(sem saída)" })
                if (r.timedOut) append("\n[timeout após ${BridgeSecurity.clampTimeout(timeoutSec)}s]")
                append("\n[exit ${r.exitCode}]")
                if (failedQuiet) append("\nDica: o comando falhou sem imprimir nada. Confirme se as dependências foram instaladas (use o bloco setup) e rode o comando com '2>&1' para ver o erro; evite '>/dev/null' ao diagnosticar.")
            }.takeLast(BridgeSecurity.MAX_OUTPUT_CHARS)
            BridgeResult(!r.timedOut && r.exitCode == 0, body)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            BridgeResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ arquivos
    private fun workspace(): File = app.sandbox.workspaceFor(sessionId)

    override suspend fun fileOp(op: String, path: String, content: String?): BridgeResult {
        val root = workspace().apply { mkdirs() }
        val target = BridgeSecurity.confine(root, path)
            ?: return BridgeResult(false, "caminho inválido '$path' (fora do workspace do conector).")
        return try {
            when (op.lowercase()) {
                "list" -> {
                    val base = if (path.isBlank()) root else target
                    if (!base.exists()) return BridgeResult(false, "pasta inexistente: '$path'")
                    val listing = base.walkTopDown().maxDepth(3).filter { it != base }.take(500)
                        .joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.relativeTo(root).path + if (it.isFile) " (${it.length()}b)" else "" }
                        .ifBlank { "(vazio)" }
                    BridgeResult(true, listing.take(BridgeSecurity.MAX_OUTPUT_CHARS))
                }
                "read" -> {
                    if (!target.isFile) return BridgeResult(false, "arquivo inexistente: '$path'")
                    if (target.length() > BridgeSecurity.MAX_FILE_BYTES) return BridgeResult(false, "arquivo grande demais (máx. ${BridgeSecurity.MAX_FILE_BYTES / (1024 * 1024)} MB).")
                    BridgeResult(true, target.readText().take(BridgeSecurity.MAX_OUTPUT_CHARS))
                }
                "write" -> {
                    val data = content.orEmpty()
                    if (data.toByteArray(Charsets.UTF_8).size > BridgeSecurity.MAX_FILE_BYTES) return BridgeResult(false, "conteúdo grande demais (máx. ${BridgeSecurity.MAX_FILE_BYTES / (1024 * 1024)} MB).")
                    target.parentFile?.mkdirs()
                    if (target.isDirectory) return BridgeResult(false, "'$path' é uma pasta.")
                    target.writeText(data)
                    BridgeResult(true, "OK: ${target.relativeTo(root).path} (${target.length()} bytes)")
                }
                "delete" -> {
                    if (target == root) return BridgeResult(false, "não é possível apagar a raiz do workspace.")
                    val ok = target.deleteRecursively()
                    BridgeResult(ok, if (ok) "OK: '$path' removido." else "nada para remover em '$path'.")
                }
                else -> BridgeResult(false, "operação de arquivo desconhecida: '$op'")
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            BridgeResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ browser
    override suspend fun fetch(url: String, waitSec: Int): BridgeResult {
        val u = url.trim()
        if (!u.startsWith("https://")) return BridgeResult(false, "a URL deve começar com https://")
        val host = u.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        if (host.isEmpty() || host !in def.hosts) return BridgeResult(false, "host '$host' não está nos hosts declarados pelo conector (baseUrl/allowedHosts).")
        return try {
            val browser = app.browserFor(sessionId)
            val nav = browser.navigate(u)
            if (nav.startsWith("ERROR:") || nav.startsWith("TIMEOUT:")) return BridgeResult(false, nav)
            if (waitSec > 0) kotlinx.coroutines.delay(waitSec.coerceIn(0, 10) * 1000L)
            val text = browser.read(12_000)
            BridgeResult(true, text.take(BridgeSecurity.MAX_OUTPUT_CHARS))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            BridgeResult(false, t.message ?: t.javaClass.simpleName)
        }
    }
}
