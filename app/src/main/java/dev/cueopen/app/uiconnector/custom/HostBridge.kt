package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.files.TextReplace
import java.io.File

/**
 * Ponte entre um UI Connector e os recursos do aparelho: TERMINAL (sandbox Linux), ARQUIVOS
 * (workspace isolado do conector) e BROWSER (busca de página renderizada). É o ponto onde as
 * capacidades `capabilities` do conector são efetivamente executadas — e onde a segurança é imposta.
 *
 * Princípios de segurança (ver também [BridgeSecurity]):
 *  - Cada conector só usa uma capacidade se ela estiver DECLARADA no JSON e AUTORIZADA pelo usuário.
 *  - Todo acesso a arquivos fica CONFINADO ao workspace do conector (sem escapar com ".." ou caminho absoluto).
 *  - Comandos de shell recebem os argumentos já escapados (ver [Template.renderShell]); rodam no sandbox
 *    isolado do conector, com tempo-limite e saída limitada.
 *  - O browser só abre URLs https dos hosts declarados pelo conector.
 *
 * O contrato é Kotlin puro para permitir testes na JVM com uma implementação falsa.
 */
interface HostBridge {
    /** O usuário autorizou esta capacidade para este conector? (terminal | files | browser | agent) */
    fun granted(capability: String): Boolean

    /**
     * Prepara o ambiente do conector UMA vez (instala/configura dependências). Devolve null se já foi
     * feito (ou não há setup); senão o resultado do setup (ok=false aborta a ferramenta).
     */
    suspend fun ensureSetup(command: String, timeoutSec: Int): BridgeResult? = null

    /** Esquece que o setup já rodou (auto-reparo: um comando sumiu do ambiente → refaz o setup). */
    fun resetSetup() {}

    /** Roda um comando no sandbox do conector (cwd = workspace do conector). */
    suspend fun shell(command: String, timeoutSec: Int): BridgeResult

    /** Saída de terminal para subagente; padrão mantém compatibilidade com bridges de teste. */
    suspend fun agentShell(command: String, timeoutSec: Int): BridgeResult = shell(command, timeoutSec)

    /** Executa tarefa LLM limitada ao conector; bridges sem implementação negam por padrão. */
    suspend fun runAgentTask(task: String, model: String?, maxSteps: Int?, timeoutSec: Int): BridgeResult =
        BridgeResult(false, "delegação LLM indisponível neste contexto")

    /** Operação de arquivo dentro do workspace do conector: read | write | list | delete. */
    suspend fun fileOp(op: String, path: String, content: String?): BridgeResult

    /** Substitui texto em arquivo existente; a implementação padrão atende bridges virtuais de teste. */
    suspend fun replaceFile(path: String, oldText: String, newText: String, allowMultiple: Boolean = false): BridgeResult {
        val current = fileOp("read", path, null)
        if (!current.ok) return current
        val plan = TextReplace.apply(current.text, oldText, newText, allowMultiple)
        if (!plan.success) return BridgeResult(false, plan.error ?: "substituição recusada")
        val written = fileOp("write", path, plan.updatedText)
        if (!written.ok) return written
        val mode = if (plan.strategy == "exact") "literal" else "whitespace"
        return BridgeResult(true, "OK: ${plan.replacements} ocorrência(s) substituída(s) ($mode).")
    }

    /** Abre uma URL https (host declarado) no browser e devolve o texto renderizado da página. */
    suspend fun fetch(url: String, waitSec: Int): BridgeResult
}

/** Resultado de uma operação da ponte. [ok]=false vira "ERRO ..." na saída da ferramenta. */
data class BridgeResult(val ok: Boolean, val text: String)

/** Ponte vazia: nega tudo. Usada quando não há contexto Android (testes) ou nenhuma capacidade. */
object NoHostBridge : HostBridge {
    override fun granted(capability: String) = false
    override suspend fun shell(command: String, timeoutSec: Int) =
        BridgeResult(false, "terminal indisponível neste contexto")
    override suspend fun fileOp(op: String, path: String, content: String?) =
        BridgeResult(false, "arquivos indisponíveis neste contexto")
    override suspend fun fetch(url: String, waitSec: Int) =
        BridgeResult(false, "browser indisponível neste contexto")
}

/** Capacidades reconhecidas pela plataforma. */
object Capabilities {
    const val TERMINAL = "terminal"
    const val FILES = "files"
    const val BROWSER = "browser"
    const val AGENT = "agent"
    val ALL = setOf(TERMINAL, FILES, BROWSER, AGENT)

    fun label(cap: String): String = when (cap) {
        TERMINAL -> "Terminal (Linux/Alpine)"
        FILES -> "Arquivos (workspace do conector)"
        BROWSER -> "Browser (abrir páginas)"
        AGENT -> "Delegação ao agente LLM"
        else -> cap
    }

    /** Aviso mostrado ao usuário ao autorizar a capacidade. */
    fun warning(cap: String): String = when (cap) {
        TERMINAL -> "Executa comandos Linux no ambiente Alpine/proot exclusivo deste conector; não monta o armazenamento Android nem os chats/outros conectores, mas comandos podem ler o próprio sistema Alpine e acessar a internet. Não é um sandbox de shell restrito só a uma pasta. Autorize apenas código confiável."
        FILES -> "Lê e grava arquivos apenas na pasta (workspace) deste conector — nunca fora dela."
        BROWSER -> "Abre páginas https dos sites declarados pelo conector para ler o conteúdo."
        AGENT -> "Envia a tarefa e as leituras autorizadas ao modelo LLM configurado (ou ao modelo do agente selecionado); pode consumir a cota/custo do provedor. O subagente só recebe terminal/arquivos se também autorizados para este conector; não recebe browser, carteira, ferramentas do aparelho ou outros conectores."
        else -> ""
    }
}

/** Utilidades de segurança da ponte (puras — testáveis na JVM). */
object BridgeSecurity {
    const val MAX_CMD_CHARS = 8_000
    const val MAX_OUTPUT_CHARS = 24_000
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MIN_TIMEOUT = 1
    const val MAX_TIMEOUT = 300
    /** O setup (instalar dependências) pode demorar mais que um comando comum. */
    const val MAX_SETUP_TIMEOUT = 900
    val FILE_OPS = setOf("read", "write", "list", "delete", "replace")

    fun clampTimeout(sec: Int): Int = sec.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
    fun clampSetupTimeout(sec: Int): Int = sec.coerceIn(MIN_TIMEOUT, MAX_SETUP_TIMEOUT)

    /**
     * Resolve um caminho relativo DENTRO de [root], recusando qualquer coisa que escape do workspace
     * (caminho absoluto, "..", ou normalização que saia da raiz). Devolve null se o caminho for inválido.
     */
    fun confine(root: File, rel: String): File? {
        val cleaned = rel.trim().trim('/')
        if (cleaned.isEmpty()) return root
        // Recusa caminho absoluto, caminho com esquema, ou segmentos de subida.
        if (rel.trim().startsWith("/") || rel.contains("://") || rel.contains("\u0000")) return null
        val segments = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        val target = File(root, segments.joinToString("/"))
        val rootPath = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val targetCanon = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }
        // Confirma que o alvo canônico continua sob a raiz canônica (defende contra symlinks).
        val rp = rootPath.path.trimEnd('/')
        val tp = targetCanon.path
        return if (tp == rp || tp.startsWith("$rp/")) target else null
    }
}
