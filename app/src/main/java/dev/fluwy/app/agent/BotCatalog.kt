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
        "coding-shipping" -> listOf("Para código, use o terminal (shell) e o browser para reproduzir e testar; registre os passos exatos e o que viu.")
        "content-publishing" -> listOf("Escreva no tom do usuário (peça um exemplo se não tiver) e entregue o texto pronto para revisar; nunca publique sozinho.")
        "customer-sales" -> listOf("Use o CRM, e-mail e agenda conectados quando existirem; rascunhe mensagens para revisão e nunca envie em massa.")
        "finance-ops" -> listOf("Nunca faça pagamento, compra ou alteração de orçamento sem a aprovação explícita do usuário; mostre valores e fontes.")
        "personal-admin" -> listOf("Confirme com o usuário antes de reservar, comprar, se candidatar ou cancelar qualquer coisa.")
        "research-briefings" -> listOf("Cruze pelo menos duas fontes, cite os links e destaque o que mudou desde a última vez.")
        "teams-handoffs", "inbox-calendar" -> listOf("Use e-mail, agenda e mensagens conectados; organize por prioridade e deixe todo envio para o usuário aprovar.")
        else -> emptyList()
    }

    fun instructions(b: Bot): String = buildString {
        appendLine("Você é o agente \"${b.name}\" (${b.categoryLabel}), um agente pronto do catálogo de agentes bots.")
        appendLine()
        appendLine("Missão: ${b.summary}")
        appendLine()
        appendLine("Como trabalhar:")
        appendLine("- Quando a tarefa envolver um app (e-mail, agenda, CRM, planilhas, Drive, Slack…), use os conectores: conectores_listar, conector_acoes e conector_executar. Se o app não estiver conectado, use o browser ou peça ao usuário para conectá-lo em Apps › Conectores.")
        categoryRules(b.category).forEach { appendLine("- $it") }
        appendLine("- Antes de enviar, publicar, comprar, agendar ou apagar algo, mostre o rascunho e espere a confirmação do usuário.")
        appendLine("- Nunca invente dados, métricas, nomes ou fontes; diga claramente o que não encontrou.")
        append("- Entregue o resultado curto e organizado, com o mais importante primeiro.")
    }

    /** Rascunho do agente (ainda não salvo): a tela de edição só grava ao tocar em Salvar. */
    fun draft(b: Bot, base: AgentProfile): AgentProfile = base.copy(
        name = b.name,
        face = faceFor(b),
        color = colorFor(b),
        instructions = instructions(b),
    )
}
