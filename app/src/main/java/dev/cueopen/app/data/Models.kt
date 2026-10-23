package dev.cueopen.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)
fun now(): Long = System.currentTimeMillis()

@Serializable
enum class SessionStatus { IDLE, RUNNING, WAITING_USER, DONE, ERROR, STOPPED }

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

    @Serializable
    data class Error(
        override val id: String = newId(),
        override val time: Long = now(),
        val text: String,
    ) : ChatItem()
}

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
enum class Repeat { ONCE, HOURLY, DAILY, WEEKLY }

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
    val model: String = "anthropic/claude-sonnet-4.5",
    val fastModel: String = "google/gemini-2.5-flash",
    val disableThinking: Boolean = true,
    val maxSteps: Int = 40,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoApproveCommands: Boolean = true,
    val sandboxPackages: String = "bash curl wget git python3 py3-pip nodejs npm",
    val userName: String = "",
    val notifyOnDone: Boolean = true,
)

@Serializable
data class AppState(
    val sessions: List<Session> = emptyList(),
    val agents: List<AgentProfile> = emptyList(),
    val skills: List<Skill> = BuiltInSkills.all,
    val scheduled: List<ScheduledTask> = emptyList(),
    val knowledge: List<KnowledgeItem> = emptyList(),
    val settings: Settings = Settings(),
    val onboarded: Boolean = false,
)
