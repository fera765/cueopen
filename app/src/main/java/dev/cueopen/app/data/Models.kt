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

@Serializable
data class Settings(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val model: String = "deepseek/deepseek-v4-flash",
    val fastModel: String = "deepseek/deepseek-v4-flash",
    /** Modelo avaliador de acurácia para day trade (TypeSafe Jev por padrão; roteia p/ o melhor/mais barato). */
    val accuracyModel: String = "typesafe/jev-router",
    val disableThinking: Boolean = true,
    val maxSteps: Int = 1000,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoApproveCommands: Boolean = true,
    val sandboxPackages: String = "bash curl wget git python3 py3-pip nodejs npm",
    val userName: String = "",
    val notifyOnDone: Boolean = true,
    /** TypeSafe Jev: envia só as ferramentas/conectores relevantes a cada passo e ajusta orçamentos de contexto. */
    val jevOrchestration: Boolean = true,
    /** TypeSafe Jev Router: o modelo é escolhido por requisição (qualidade × custo) — só OpenRouter. */
    val jevRouting: Boolean = false,
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
    val settings: Settings = Settings(),
    val onboarded: Boolean = false,
)
