package dev.fluwy.app.agent

import dev.fluwy.app.data.AgentProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Agentes bots prontos (catálogo público CC0).
 * Dados públicos (CC0) via github.com/kydlikebtc/awesome-grokbot, gerados por tools/bots/build-bots.py.
 *
 * O marketplace não publica as instruções internas dos bots: o app monta as instruções a partir da descrição e
 * o usuário revisa tudo na tela do agente. Nada é salvo até ele tocar em Salvar.
 */
object BotCatalog {
    @Serializable
    data class Bot(
        val id: String,
        val name: String,
        val category: String,
        val categoryLabel: String,
        val summary: String,
        val summaryEn: String = "",
        val tags: List<String> = emptyList(),
        val author: String = "",
        val url: String = "",
    )

    @Serializable
    private data class File(val source: String = "", val count: Int = 0, val bots: List<Bot> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<Bot>? = null

    fun parse(text: String): List<Bot> = json.decodeFromString(File.serializer(), text).bots.filter { it.id.isNotBlank() && it.name.isNotBlank() }

    fun all(): List<Bot> = cache ?: runCatching {
        dev.fluwy.app.FluwyApp.instance.assets.open("bots/agentbots.json").use { it.readBytes().toString(Charsets.UTF_8) }.let { parse(it) }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache = it }

    fun find(id: String?): Bot? = id?.takeIf { it.isNotBlank() }?.let { k -> all().firstOrNull { it.id == k } }

    fun search(bots: List<Bot>, query: String, category: String?): List<Bot> {
        val q = query.trim().lowercase()
        return bots.filter { b ->
            (category == null || b.category == category) &&
                (q.isBlank() || listOf(b.name, b.summary, b.summaryEn, b.categoryLabel, b.tags.joinToString(" ")).any { it.lowercase().contains(q) })
        }
    }

    private val COLORS = listOf(0xFFFF6B3D, 0xFF4D8DFF, 0xFF2FBF71, 0xFFFFC53D, 0xFFB57BFF, 0xFFFF5C8A, 0xFF2EC4C4, 0xFF9C978E)

    private val FACES = dev.fluwy.app.ui.mascot.Mascots.builtinIds

    /** Posição fixa do bot no catálogo: garante um par mascote+cor diferente para cada bot. */
    private fun slot(b: Bot): Int = all().indexOfFirst { it.id == b.id }.takeIf { it >= 0 } ?: Math.floorMod(b.id.hashCode(), 96)

    /** Mascote do bot: percorre os 12 mascotes; com a cor abaixo, nenhum par se repete (até 96 bots). */
    fun faceFor(b: Bot): String = avatarAt(slot(b)).first

    fun colorFor(b: Bot): Long = avatarAt(slot(b)).second

    fun avatarAt(i: Int): Pair<String, Long> = FACES[i % FACES.size] to COLORS[(i / FACES.size + i % FACES.size) % COLORS.size]

    private fun categoryRules(category: String): List<String> = when (category) {
        "coding-shipping" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.1", mapOf<String, Any?>("lb" to "{{")))
        "content-publishing" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.2", mapOf<String, Any?>("lb" to "{{")))
        "customer-sales" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.3", mapOf<String, Any?>("lb" to "{{")))
        "finance-ops" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.4", mapOf<String, Any?>("lb" to "{{")))
        "personal-admin" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.5", mapOf<String, Any?>("lb" to "{{")))
        "research-briefings" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.6", mapOf<String, Any?>("lb" to "{{")))
        "teams-handoffs", "inbox-calendar" -> listOf(dev.fluwy.app.prompts.Prompts.render("bots.7", mapOf<String, Any?>("lb" to "{{")))
        else -> emptyList()
    }

    fun instructions(b: Bot): String = buildString {
        appendLine(dev.fluwy.app.prompts.Prompts.render("bots.8", mapOf<String, Any?>("name" to (b.name), "category_label" to (b.categoryLabel), "lb" to "{{")))
        appendLine()
        appendLine("Missão: ${b.summary}")
        appendLine()
        appendLine("Como trabalhar:")
        appendLine(dev.fluwy.app.prompts.Prompts.render("bots.9", mapOf<String, Any?>("lb" to "{{")))
        categoryRules(b.category).forEach { appendLine("- $it") }
        appendLine(dev.fluwy.app.prompts.Prompts.render("bots.10", mapOf<String, Any?>("lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("bots.11", mapOf<String, Any?>("lb" to "{{")))
        append(dev.fluwy.app.prompts.Prompts.render("bots.12", mapOf<String, Any?>("lb" to "{{")))
    }

    /** Rascunho do agente (ainda não salvo): a tela de edição só grava ao tocar em Salvar. */
    fun draft(b: Bot, base: AgentProfile): AgentProfile = base.copy(
        name = b.name,
        face = faceFor(b),
        color = colorFor(b),
        instructions = instructions(b),
    )
}
