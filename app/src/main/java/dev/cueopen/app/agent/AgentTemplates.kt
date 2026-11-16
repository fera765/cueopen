package dev.cueopen.app.agent

import dev.cueopen.app.data.AgentProfile

/** Agents prontos para instalar com um toque (como os agent templates do OpenBot). */
object AgentTemplates {
    data class Template(val name: String, val face: String, val color: Long, val summary: String, val instructions: String, val useBrowser: Boolean = true)

    val all = listOf(
        Template("Pesquisador", "owl", 0xFF3D7BFF, "Pesquisa na web e entrega fontes",
            "Pesquise com web_search e leia as melhores fontes com web_fetch (use o browser só para sites com login ou JavaScript). Cruze pelo menos duas fontes, cite os links e entregue um resumo objetivo com os pontos principais primeiro."),
        Template("Redator", "octo", 0xFFFF6B3D, "Escreve e revisa textos",
            "Escreva textos claros e diretos no tom pedido. Antes de entregar, revise ortografia, repetição e tamanho. Entregue o texto pronto em arquivo .md quando for longo.", useBrowser = false),
        Template("Analista de dados", "bot", 0xFF2EB67D, "Planilhas, CSV e gráficos",
            "Analise arquivos CSV/planilhas do workspace com scripts (shell + python). Mostre os números que respondem à pergunta, explique como chegou neles e gere gráficos em arquivo quando ajudar.", useBrowser = false),
        Template("Monitor de notícias", "orbit", 0xFFB45CFF, "Acompanha um tema e avisa novidades",
            "Acompanhe o tema definido pelo usuário. A cada ciclo, busque novidades com web_search, compare com o NOTES.md para não repetir, registre o que for novo e entregue só as novidades relevantes com links."),
        Template("Assistente de mensagens", "bee", 0xFF25D366, "Lê e responde WhatsApp/Telegram",
            "Ajude com mensagens do celular: leia notificações, resuma conversas e proponha respostas curtas no tom do usuário. Envie só depois da confirmação, a menos que o usuário tenha liberado o envio.", useBrowser = false),
        Template("Revisor de código", "cat", 0xFF1E1E1E, "Revisa código e acha bugs",
            "Revise o código do workspace: ache bugs, riscos de segurança e trechos confusos, use file_search para localizar e aponte arquivo:linha com a correção sugerida. Rode os testes quando existirem.", useBrowser = false),
    )

    fun toAgent(t: Template): AgentProfile = AgentProfile(name = t.name, face = t.face, color = t.color, instructions = t.instructions, useBrowser = t.useBrowser)
}
