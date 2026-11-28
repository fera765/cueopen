package dev.fluwy.app.assistant

import dev.fluwy.app.data.AgentProfile
import java.text.Normalizer
import java.util.Locale

/** Seleciona um único agente somente quando o nome é identificável sem ambiguidade. */
object AssistantRoutingPolicy {
    const val PREFERENCES = "assistant_voice_routing"
    const val KEY_ACTIVE_SESSION_ID = "active_session_id"
    const val KEY_PROCESSED_REQUESTS = "processed_request_ids"

    const val ACTION_SUBMIT_COMMAND = "com.fluwy.bot.action.ASSISTANT_COMMAND"
    const val EXTRA_REQUEST_ID = "assistant_request_id"
    const val EXTRA_TRANSCRIPT = "assistant_transcript"
    const val EXTRA_ACTION = "assistant_open_action"
    const val EXTRA_SESSION_ID = "assistant_session_id"
    const val ACTION_OPEN_SETTINGS = "com.fluwy.bot.action.OPEN_ASSISTANT_SETTINGS"
    const val ACTION_OPEN_CHAT = "com.fluwy.bot.action.OPEN_ASSISTANT_CHAT"
    const val ACTION_REQUEST_MICROPHONE = "com.fluwy.bot.action.REQUEST_ASSISTANT_MICROPHONE"

    private val ignoredNameWords = setOf(
        "a", "as", "o", "os", "de", "da", "do", "das", "dos", "e",
        "agente", "agent", "assistente", "assistant", "bot",
    )
    private val screenshotIntent = Regex("\\b(print|screenshot|captur(?:a|e|ar)(?: a| da| de)? tela|tira(?:r)? (?:um )?print)\\b")

    fun findMentionedAgent(text: String, agents: List<AgentProfile>): AgentProfile? {
        val utteranceWords = normalizedWords(text).toSet()
        if (utteranceWords.isEmpty()) return null
        val matches = agents.mapNotNull { profile ->
            val meaningful = normalizedWords(profile.name).filterNot { it in ignoredNameWords }.distinct()
            if (meaningful.isEmpty() || !meaningful.all { it in utteranceWords }) null
            else profile to meaningful
        }.sortedWith(compareByDescending<Pair<AgentProfile, List<String>>> { it.second.size }
            .thenByDescending { it.second.sumOf(String::length) })
        val best = matches.firstOrNull() ?: return null
        val second = matches.getOrNull(1)
        // Não adivinhe entre perfis que têm o mesmo grau de correspondência e comprimento.
        if (second != null && second.second.size == best.second.size &&
            second.second.sumOf(String::length) == best.second.sumOf(String::length)) return null
        return best.first
    }

    fun explicitlyRequestsScreenshot(text: String): Boolean = screenshotIntent.containsMatchIn(normalizeForMatch(text))

    fun normalizeForMatch(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun normalizedWords(value: String) = normalizeForMatch(value).split(Regex("\\s+")).filter(String::isNotBlank)
}
