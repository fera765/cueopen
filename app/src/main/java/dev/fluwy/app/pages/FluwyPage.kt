package dev.fluwy.app.pages

import kotlinx.serialization.Serializable

/**
 * Fluwy Pages: páginas que os agentes criam e mantêm atualizadas (como os Pages do Grok Bot) — relatório/documento,
 * quadro kanban, checklist, tabela ou página HTML livre. Pertencem a um "espaço" (o chat ou a seção), então todos os
 * agentes da seção leem e atualizam a mesma página. Atualizações são operações pequenas e atômicas (um lote inteiro
 * entra ou nada entra), o que deixa vários agentes mexerem na mesma página sem um apagar o trabalho do outro.
 */
@Serializable
data class FluwyPage(
    val id: String,
    val spaceId: String,
    val title: String,
    val kind: String = KIND_DOC,
    val markdown: String = "",
    val html: String = "",
    val columns: List<String> = emptyList(),
    val cards: List<PageCard> = emptyList(),
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String = "",
    val pinned: Boolean = false,
    /** Quadro gerado do plano da seção (team_plan): os cartões "task_*" seguem o estado das tarefas. */
    val sectionId: String? = null,
    /** Dados da página (JSON) — páginas html gravam aqui pelo window.Fluwy.saveData; o agente lê no page_read. */
    val data: String = "",
    /** Agent que criou a página: ao excluir o agent, a página sai junto. */
    val creatorAgentId: String? = null,
) {
    companion object {
        const val KIND_DOC = "doc"
        const val KIND_KANBAN = "kanban"
        const val KIND_CHECKLIST = "checklist"
        const val KIND_TABLE = "table"
        const val KIND_HTML = "html"
        val KINDS = listOf(KIND_DOC, KIND_KANBAN, KIND_CHECKLIST, KIND_TABLE, KIND_HTML)
        val DEFAULT_COLUMNS = listOf("A fazer", "Fazendo", "Revisão", "Feito")
        val CHECKLIST_COLUMNS = listOf("Pendente", "Feito")
    }
}

@Serializable
data class PageCard(
    val id: String,
    val title: String,
    val column: String,
    val assignee: String = "",
    val note: String = "",
    val updatedBy: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
