package dev.cueopen.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Disciplina de uso do hub (Activepieces) pelo agente — evita o ciclo "cria fluxo → testa → trunca → apaga → cria".
 *
 *  1. Tarefa única (ler e-mails, enviar mensagem, criar issue): só ap_run_action. As ferramentas que constroem ou
 *     editam fluxos nem entram no contexto, e se forem chamadas são recusadas com a instrução certa.
 *  2. Automação (o usuário pediu algo recorrente/gatilho): no máximo UM fluxo novo por tarefa, com nome
 *     "Fluwy · …" (identificável para limpeza). Errou? Edita o fluxo criado — não cria outro nem apaga para recomeçar.
 *  3. Tentativas: a mesma chamada que já falhou 2x com o mesmo erro é recusada (pare e explique ao usuário).
 */
object HubGuard {
    const val FLOW_PREFIX = "Fluwy · "

    /** Criam fluxo novo. */
    val CREATE_FLOW = setOf("ap_build_flow", "ap_create_flow", "ap_duplicate_flow")

    /** Editam/operam fluxos (inclui criar). Leitura de fluxos (list/structure/runs) continua sempre liberada. */
    val FLOW_MUTATING = CREATE_FLOW + setOf(
        "ap_add_step", "ap_update_step", "ap_delete_step", "ap_update_trigger", "ap_add_branch", "ap_update_branch", "ap_delete_branch",
        "ap_lock_and_publish", "ap_change_flow_status", "ap_test_step", "ap_test_flow", "ap_rename_flow", "ap_move_flow_to_folder",
        "ap_create_folder", "ap_manage_notes", "ap_validate_flow", "ap_validate_step_config", "ap_retry_run", "ap_delete_flow",
    )

    private val AUTOMATION = listOf(
        "automatiz", "automacao", "automacoes", "fluxo", "flow", "todo dia", "todos os dias", "toda semana", "todo mes", "toda hora",
        "a cada", "sempre que", "quando chegar", "quando receber", "quando entrar", "agende", "agendar", "agendamento", "monitor",
        "recorrente", "diariamente", "semanalmente", "webhook", "gatilho", "trigger", "workflow", "rotina",
    )

    fun automationIntent(qNorm: String): Boolean = AUTOMATION.any { qNorm.contains(it) }

    /** Nome curto da ferramenta do hub ("mcp_activepieces_hub__ap_run_action" → "ap_run_action"). */
    fun shortName(fn: String): String = fn.substringAfter("__")

    /** Some do contexto: ferramentas de fluxo quando o pedido não é de automação. */
    fun hideTool(fn: String, hubPrefix: String, automation: Boolean): Boolean =
        fn.startsWith(hubPrefix + "__") && !automation && shortName(fn) in FLOW_MUTATING

    data class TaskState(
        val automation: Boolean,
        /** Fluxos criados com sucesso nesta tarefa (desde a última mensagem do usuário). */
        val flowsCreated: Int,
        /** Falhas anteriores desta mesma chamada (mesma ferramenta + mesmos argumentos) nesta tarefa. */
        val sameCallFailures: Int,
    )

    sealed class Decision {
        data class Allow(val args: JsonObject) : Decision()
        data class Refuse(val message: String) : Decision()
    }

    fun check(fn: String, args: JsonObject, hubPrefix: String, state: TaskState): Decision {
        val tool = shortName(fn)
        if (state.sameCallFailures >= 2)
            return Decision.Refuse("RECUSADO: esta mesma chamada ($tool) já falhou ${state.sameCallFailures}x nesta tarefa com os mesmos argumentos. " +
                "Não repita: mude os argumentos com base no erro (ex.: confira os campos com ${hubPrefix}__ap_get_piece_props) ou explique o bloqueio ao usuário.")
        if (tool in FLOW_MUTATING && !state.automation)
            return Decision.Refuse("RECUSADO: o pedido é uma tarefa única — não crie nem edite fluxos para isso. " +
                "Execute a ação direto com ${hubPrefix}__ap_run_action {pieceName, actionName, input, connectionExternalId}. " +
                "Fluxos só quando o usuário pedir uma automação recorrente (ex.: \"todo dia\", \"sempre que chegar\").")
        if (tool in CREATE_FLOW && state.flowsCreated >= 1)
            return Decision.Refuse("RECUSADO: você já criou um fluxo nesta tarefa. Não crie outro nem apague para recomeçar: " +
                "corrija o fluxo existente com ${hubPrefix}__ap_flow_structure + ${hubPrefix}__ap_update_step/ap_update_trigger.")
        if (tool in CREATE_FLOW) {
            val key = listOf("flowName", "displayName", "name").firstOrNull { args[it] is JsonPrimitive }
            if (key != null) {
                val name = args[key]!!.jsonPrimitive.contentOrNull.orEmpty().trim().ifBlank { "automação" }
                if (!name.startsWith(FLOW_PREFIX)) return Decision.Allow(JsonObject(args + (key to JsonPrimitive(FLOW_PREFIX + name))))
            }
        }
        return Decision.Allow(args)
    }

    // ---------------- Limpeza de automações ----------------
    data class FlowLine(val name: String, val id: String, val status: String, val published: Boolean)

    private val LINE = Regex("""^\s*-\s+(.+?)\s+\(([A-Za-z0-9_-]{6,})\)\s*\|\s*([A-Z_]+)\s*\|\s*(published|draft)""")

    /** Lê a saída de ap_list_flows ("- Nome (id) | ENABLED | published | trigger: …"). */
    fun parseFlows(text: String): List<FlowLine> = text.lines().mapNotNull { l ->
        LINE.find(l)?.groupValues?.let { g -> FlowLine(g[1].trim(), g[2], g[3], g[4] == "published") }
    }

    private val TEMP = Regex("(?i)^(fluwy · )?(tmp|temp|teste|test|rascunho|draft)\\b|_tmp|tmp_|\\(teste\\)|\\btemporári")

    /**
     * Automações sem uso para limpar: rascunhos (nunca publicados, desligados) criados para teste/temporários e
     * duplicatas (mesmo nome) — das duplicatas fica a publicada/ligada, ou a primeira. Publicadas e ligadas nunca saem.
     */
    fun cleanupCandidates(flows: List<FlowLine>): List<FlowLine> {
        val out = LinkedHashMap<String, FlowLine>()
        val live = { f: FlowLine -> f.published || f.status == "ENABLED" }
        flows.filter { !live(it) && TEMP.containsMatchIn(it.name) }.forEach { out[it.id] = it }
        flows.groupBy { it.name.lowercase().removePrefix(FLOW_PREFIX.lowercase()).trim() }.values.filter { it.size > 1 }.forEach { group ->
            val keep = group.firstOrNull(live) ?: group.first()
            group.filter { it.id != keep.id && !live(it) }.forEach { out[it.id] = it }
        }
        return out.values.toList()
    }
}
