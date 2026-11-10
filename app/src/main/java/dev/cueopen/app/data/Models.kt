package dev.cueopen.app.data

import dev.cueopen.app.uiconnector.custom.ConnectorValidationAttestation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)
fun now(): Long = System.currentTimeMillis()

@Serializable
enum class SessionStatus { IDLE, RUNNING, WAITING_USER, DONE, ERROR, STOPPED, SLEEPING }

@Serializable
enum class StepStatus { RUNNING, OK, ERROR }

/** Um item da linha do tempo de uma tarefa (mensagem, passo de ferramenta, plano...). */
@Serializable
sealed class ChatItem {
    abstract val id: String
    abstract val time: Long

    @Serializable
    data class User(
        override val id: String = newId(),
        override val time: Long = now(),
        val text: String,
        val attachments: List<String> = emptyList(),
        /** Texto da resposta do agente citada ao responder; null em mensagens antigas/sem citação. */
        val replyTo: String? = null,
    ) : ChatItem()

    @Serializable
    data class Assistant(
        override val id: String = newId(),
        override val time: Long = now(),
        val text: String,
        val final: Boolean = false,
    ) : ChatItem()

    @Serializable
    data class Step(
        override val id: String = newId(),
        override val time: Long = now(),
        val tool: String,
        val title: String,
        val args: String,
        val result: String = "",
        val status: StepStatus = StepStatus.RUNNING,
    ) : ChatItem()

    @Serializable
    data class Plan(
        override val id: String = newId(),
        override val time: Long = now(),
        val goal: String,
        val phases: List<PlanPhase>,
    ) : ChatItem()

    /** Arquivo/imagem gerado pelo agente, exibido inline no chat. */
    @Serializable
    data class Media(
        override val id: String = newId(),
        override val time: Long = now(),
        val path: String,
        val caption: String = "",
    ) : ChatItem()

    /** Conector UI aprovado em dry-run offline, aguardando o usuário tocar em Instalar. */
    @Serializable
    data class ConnectorProposal(
        override val id: String = newId(),
        override val time: Long = now(),
        val connectorId: String,
        val name: String,
        val description: String,
        val host: String,
        val tools: List<String> = emptyList(),
        @SerialName("json") val yaml: String,
        val installed: Boolean = false,
        /** Capacidades declaradas no YAML; o dry-run não as executa e a autorização cabe ao usuário. */
        val capabilities: List<String> = emptyList(),
        /** Campo legado para compatibilidade com cards antigos; o builder atual não guarda credenciais. */
        val loginSecrets: Map<String, String> = emptyMap(),
        /** Prova persistida do dry-run offline, vinculada ao SHA-256 canônico da definição YAML. */
        val validation: ConnectorValidationAttestation? = null,
    ) : ChatItem()

    /** Alterações de configuração propostas por IA para um único UI Connector, aguardando aplicação humana. */
    @Serializable
    data class ConnectorEditProposal(
        override val id: String = newId(),
        override val time: Long = now(),
        val connectorId: String,
        val connectorName: String,
        val summary: String,
        val settings: Map<String, String> = emptyMap(),
        val enabled: Boolean? = null,
        val agentRequired: Boolean? = null,
        /** Definição funcional sanitizada (nunca contém valores protegidos), pronta para revisão no card. */
        val definitionYaml: String? = null,
        /** Fingerprint não reversível da configuração vista ao propor; bloqueia aplicação sobre estado desatualizado. */
        val baseFingerprint: String,
        val applied: Boolean = false,
    ) : ChatItem()

    /** Perguntas de múltipla escolha do agente (ask_question): aparece só quando há uma dúvida real. */
    @Serializable
    data class Question(
        override val id: String = newId(),
        override val time: Long = now(),
        val questions: List<QuestionSpec>,
        /** Respostas por pergunta (vazio = pulou). */
        val answers: List<List<String>> = emptyList(),
        /** pending | answered | dismissed */
        val state: String = "pending",
    ) : ChatItem()

    /**
     * Formulário SEGURO (ask_secure): API key, token, e-mail/senha, campos de formulário. Só os NOMES dos
     * campos ficam aqui (e no histórico); os valores vão direto para o cofre do aparelho, nunca para a IA.
     */
    @Serializable
    data class SecureForm(
        override val id: String = newId(),
        override val time: Long = now(),
        val title: String,
        val reason: String = "",
        val fields: List<SecureField>,
        /** pending | submitted | cancelled */
        val state: String = "pending",
        val submitted: List<String> = emptyList(),
        /** Agente da seção que pediu o formulário (exibido no chat do coordenador); null = este chat. */
        val forSessionId: String? = null,
        val forAgentName: String? = null,
    ) : ChatItem()

    /**
     * Pedido de confirmação antes de uma ação sensível (comprar/pagar, apagar, enviar/publicar,
     * transferir, conta). A aprovação vale UMA vez para a mesma chamada (ferramenta + argumentos).
     */
    @Serializable
    data class Approval(
        override val id: String = newId(),
        override val time: Long = now(),
        val tool: String,
        /** Chave da chamada (ferramenta + argumentos normalizados) — nunca contém segredos resolvidos. */
        val callKey: String,
        val category: String,
        val summary: String,
        /** pending | approved | denied | used */
        val state: String = "pending",
    ) : ChatItem()

    /**
     * Card de autorização da Seção: o usuário escolhe o que o time pode usar (browser, terminal,
     * carteira, conectores, acesso ao Android, confirmações) e ativa. Só então o coordenador começa.
     */
    @Serializable
    data class SectionSetup(
        override val id: String = newId(),
        override val time: Long = now(),
        val sectionId: String,
        val sectionName: String,
        val agentNames: List<String> = emptyList(),
        /** pending | activated | cancelled */
        val state: String = "pending",
        val suggestedCaps: List<String> = emptyList(),
        val suggestWallet: Boolean = false,
    ) : ChatItem()

    @Serializable
    data class Error(
        override val id: String = newId(),
        override val time: Long = now(),
        val text: String,
    ) : ChatItem()
}

@Serializable
data class QuestionOption(val label: String, val description: String = "")

@Serializable
data class QuestionSpec(
    val question: String,
    val header: String = "",
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false,
    /** Mostra "Outra resposta" com campo de texto. */
    val allowOther: Boolean = true,
)

/** Campo do formulário seguro. type: password | token | api_key | email | text | number | url */
@Serializable
data class SecureField(val name: String, val label: String, val type: String = "password", val required: Boolean = true, val hint: String = "")

@Serializable
data class PlanPhase(val title: String, val done: Boolean = false)

/** Mensagem crua no formato OpenAI/OpenRouter, preservada para continuar a conversa. */
@Serializable
data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class Session(
    val id: String = newId(),
    val title: String = "Nova tarefa",
    val agentId: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val status: SessionStatus = SessionStatus.IDLE,
    val items: List<ChatItem> = emptyList(),
    val llm: List<LlmMessage> = emptyList(),
    val pinned: Boolean = false,
    val scheduledTaskId: String? = null,
    val favorite: Boolean = false,
    /** Resumo estruturado de tudo que já saiu da janela de contexto (compactação). */
    val summary: String = "",
    val compactions: Int = 0,
    /** Modo contínuo/monitoramento: o agente alterna trabalho e `sleep` até o usuário pausar. */
    val continuous: Boolean = false,
    val wakeAt: Long? = null,
    val wakeReason: String = "",
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tokensCached: Long = 0,
    val llmCalls: Int = 0,
    /** Modelo usado na chamada mais recente desta sessão. */
    val modelUsed: String? = null,
    /** Custo estimado acumulado em USD apenas para chamadas com preço conhecido. */
    val costUsd: Double = 0.0,
    val hasPricedUsage: Boolean = false,
    val autoCompact: Boolean = true,
    /** Sessão aberta pelo criador de UI Connectors: só nela as ferramentas connector_* existem. */
    val connectorBuilder: Boolean = false,
    /** Seção (time de Agents) a que este chat pertence; null = chat comum. */
    val sectionId: String? = null,
    /** Chat de edição vinculado a um único UI Connector; não habilita ferramentas gerais nem outros conectores. */
    val connectorEditId: String? = null,
    /** Fingerprint da configuração apresentada ao abrir o chat de edição. */
    val connectorEditFingerprint: String? = null,
)

/** Memória de longo prazo (global ou de um Agent), recuperada por relevância — não toda de uma vez. */
@Serializable
data class MemoryItem(
    val id: String = newId(),
    val text: String,
    val agentId: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Long = now(),
    val lastUsedAt: Long = now(),
    val uses: Int = 0,
)

@Serializable
data class AgentProfile(
    val id: String = newId(),
    val name: String,
    val face: String = "wink",
    val color: Long = 0xFFFF6B3D,
    val instructions: String = "",
    val model: String? = null,
    val skillIds: List<String> = emptyList(),
    val memory: List<String> = emptyList(),
    val useBrowser: Boolean = true,
    /** Permite tarefas que usam Browser continuarem em segundo plano, separado do acesso normal ao Browser. */
    val browserAutonomy: Boolean = false,
    /** Marca se o toggle de autonomia do Browser foi quem adicionou o consentimento BACKGROUND. */
    val browserAutonomyGrantedBackground: Boolean = false,
    val useTerminal: Boolean = true,
    val createdAt: Long = now(),
    // ---- Autonomia 24/7 (heartbeat) ----
    val autonomous: Boolean = false,
    val mission: String = "",
    val heartbeatMinutes: Int = 30,
    val activeStartHour: Int = 0,
    val activeEndHour: Int = 24,
    // Orçamento diário de tokens (guarda de custo). 300k parava um day-trader em ~30 min de forma
    // SILENCIOSA; elevado para um teto generoso adequado a ciclos frequentes 24/7.
    val dailyTokenBudget: Long = 20_000_000,
    val homeSessionId: String? = null,
    val lastBeatAt: Long? = null,
    val beats: Int = 0,
    val actions: Int = 0,
    val tokensDay: String = "",
    val tokensToday: Long = 0,
    val notifyOnAction: Boolean = true,
    /** Permissões do dispositivo liberadas PARA ESTE agente (ids em DeviceCaps). */
    val deviceCaps: List<String> = emptyList(),
    /** UI Connectors (SDK próprio, ex.: "iqoption") ativados PARA ESTE agente. */
    val uiConnectorIds: List<String> = emptyList(),
    /**
     * Confirmações: "ask" = pergunta antes das categorias em [confirmCategories]; "auto" = 100% autônomo,
     * executa tudo sem perguntar. Padrão seguro para agentes novos e antigos.
     */
    val confirmMode: String = "ask",
    /** Categorias que exigem confirmação no modo "ask" (ids de ActionGate.Category). */
    val confirmCategories: List<String> = listOf("purchase", "delete", "send", "transfer", "account"),
    /** Ciclos autônomos com falha em sequência; ao chegar no limite a autonomia é desligada. */
    val consecutiveFailures: Int = 0,
)

/**
 * Seção: um time de Agents criado a partir de um pedido em linguagem natural ("crie uma seção
 * Direct Response com estes agentes…"). Tem um Agent coordenador e um chat próprio na sidebar.
 */
@Serializable
data class Section(
    val id: String = newId(),
    val name: String,
    val description: String = "",
    val coordinatorAgentId: String,
    val agentIds: List<String> = emptyList(),
    /** Chat principal da seção (com o coordenador). */
    val sessionId: String,
    /** false até o usuário ativar as permissões no card de autorização. */
    val configured: Boolean = false,
    /** Primeira tarefa a enviar ao coordenador quando a seção for ativada. */
    val firstTask: String = "",
    val createdAt: Long = now(),
    /** Chat onde a seção foi criada: seus dados seguros e o pedido original são herdados pela seção. */
    val originSessionId: String? = null,
    /** Plano persistido (team_plan): tarefas, dependências e estados. Sobrevive ao fechamento do app. */
    val planGoal: String = "",
    val plan: List<SectionTask> = emptyList(),
    /** Item ChatItem.Plan do chat do coordenador que espelha o plano (progresso real). */
    val planItemId: String? = null,
)

/** Estados de uma tarefa do plano. REVIEW = o agente entregou e o coordenador precisa validar. */
@Serializable
enum class TaskStatus { PENDING, RUNNING, REVIEW, DONE, FAILED, BLOCKED, CANCELLED }

@Serializable
data class SectionTask(
    val id: String,
    val agentId: String,
    val agentName: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,
    val result: String = "",
    val note: String = "",
    val startedAt: Long? = null,
    val endedAt: Long? = null,
)

/** Conector MCP remoto (OAuth público com registro dinâmico de cliente, sem API key). */
@Serializable
data class Connector(
    val id: String,
    val name: String,
    val url: String,
    val description: String = "",
    val needsAuth: Boolean = true,
    val enabled: Boolean = true,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val tokenEndpoint: String? = null,
    val toolsJson: String? = null,
    val connectedAt: Long? = null,
    val lastError: String? = null,
)

@Serializable
data class Skill(
    val id: String = newId(),
    val name: String,
    val description: String,
    val instructions: String,
    val icon: String = "sparkles",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

@Serializable
enum class Repeat { ONCE, HALF_HOUR, HOURLY, DAILY, WEEKLY }

@Serializable
data class ScheduledTask(
    val id: String = newId(),
    val title: String,
    val prompt: String,
    val agentId: String? = null,
    val repeat: Repeat = Repeat.DAILY,
    val startAt: Long = now(),
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastSessionId: String? = null,
    /** Chat de origem; excluir esse chat também encerra somente esta agenda. */
    val sourceSessionId: String? = null,
)

@Serializable
data class KnowledgeItem(
    val id: String = newId(),
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val createdAt: Long = now(),
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Pacotes da instalação base do sandbox; migra apenas o default antigo, sem tocar em listas personalizadas. */
object SandboxPackageDefaults {
    const val DEFAULT = "bash curl wget git python3 py3-pip py3-yaml nodejs npm"
    private const val PREVIOUS_DEFAULT = "bash curl wget git python3 py3-pip nodejs npm"

    fun migrate(packages: String): String {
        fun normalized(value: String) = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.sorted()
        return if (normalized(packages) == normalized(PREVIOUS_DEFAULT)) DEFAULT else packages
    }
}

@Serializable
data class Settings(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val model: String = "deepseek/deepseek-v4-flash",
    val fastModel: String = "deepseek/deepseek-v4-flash",
    val disableThinking: Boolean = true,
    val maxSteps: Int = 1000,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoApproveCommands: Boolean = true,
    val sandboxPackages: String = SandboxPackageDefaults.DEFAULT,
    val userName: String = "",
    val notifyOnDone: Boolean = true,
    /** Orçamento de contexto (tokens estimados) antes de compactar. */
    val contextBudget: Int = 24_000,
    val keepRecentMessages: Int = 10,
    /** Migração única do consentimento de background dos agentes autônomos preexistentes. */
    val deviceBackgroundConsentMigrated: Boolean = false,
    /** IDs refer to profiles held by the encrypted proxy store, never credentials. */
    val browserProxyId: String? = null,
    val terminalProxyId: String? = null,
    val agentApiProxyId: String? = null,
)

@Serializable
data class AppState(
    val sessions: List<Session> = emptyList(),
    val agents: List<AgentProfile> = emptyList(),
    val skills: List<Skill> = BuiltInSkills.all,
    val scheduled: List<ScheduledTask> = emptyList(),
    val knowledge: List<KnowledgeItem> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val connectors: List<Connector> = emptyList(),
    /** Seções criadas pelo usuário no chat: um time de Agents com coordenador e chat próprio. */
    val sections: List<Section> = emptyList(),
    val settings: Settings = Settings(),
    val onboarded: Boolean = false,
    /** Seção Browser: sessões de login salvas (só metadados; cookies ficam no perfil do WebView). */
    val browserLogins: List<BrowserLogin> = emptyList(),
    /** Seção Browser: scripts do usuário injetados nos browsers dos agentes, por domínio. */
    val userScripts: List<UserScript> = emptyList(),
)

/**
 * Sessão de login salva. NÃO guarda senha nem cookie: o login fica no perfil persistente do WebView
 * (armazenamento do próprio Chromium). Aqui ficam domínio, estado, uso e quais agentes podem usá-la.
 */
@Serializable
data class BrowserLogin(
    val id: String = newId(),
    val name: String,
    val url: String,
    val domain: String,
    val createdAt: Long = now(),
    val lastVerifiedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    /** active | expired | revoked */
    val status: String = "active",
    /** Vazio = todos os agentes podem usar. */
    val allowedAgentIds: List<String> = emptyList(),
)

@Serializable
data class UserScript(
    val id: String = newId(),
    val name: String,
    val description: String = "",
    val code: String,
    /** Domínios onde roda (ex.: facebook.com, *.exemplo.com). Vazio = não roda em lugar nenhum. */
    val domains: List<String> = emptyList(),
    /** Só roda se a URL contiver este trecho (opcional). */
    val pathContains: String = "",
    /** Maior roda primeiro. */
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** Vazio = todos os agentes. */
    val agentIds: List<String> = emptyList(),
    val runs: Int = 0,
    val errors: Int = 0,
    val consecutiveErrors: Int = 0,
    val lastError: String = "",
    val lastRunAt: Long? = null,
    /** Últimas execuções: "hh:mm host · ok|erro: …" */
    val history: List<String> = emptyList(),
)
