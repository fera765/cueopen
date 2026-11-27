package dev.fluwy.app.agent

/** Passos de uma tarefa delegada: aviso para entregar e corte (com entrega parcial ao coordenador). */
const val MEMBER_SOFT_STEPS = 40
const val MEMBER_HARD_STEPS = 60

/** Tipo do Step que representa a pílula de um agente da seção no chat do coordenador (args = "chatId|agentId"). */
const val TEAM_MEMBER_STEP = "team_member"

/** O que o "computador" do agente está fazendo agora (mostrado no card ao vivo do chat). */
data class Activity(val kind: Kind = Kind.IDLE, val detail: String = "") {
    enum class Kind { IDLE, THINKING, TERMINAL, BROWSER, EDITOR, SEARCH }
}

