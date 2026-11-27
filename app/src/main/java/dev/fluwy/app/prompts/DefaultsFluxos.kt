package dev.fluwy.app.prompts

// Gerado a partir dos prompts originais. Edite o texto aqui para mudar o padrão do app.
// O usuário pode personalizar cada prompt em Configurações > Prompts.
internal object DefaultsFluxos {
    val all = listOf(
        PromptDef(
            id = "workflow.1",
            group = "Fluxos",
            title = "Nós de IA · Responda SOMENTE com JSON, sem texto fora dele: …",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Responda SOMENTE com JSON, sem texto fora dele: {"decisao": true ou false, "motivo": "frase curta em português", "categoria": "uma palavra", "confianca": 0 a 1}""",
        ),
        PromptDef(
            id = "workflow.2",
            group = "Fluxos",
            title = "Nós de IA · Você é um classificador rigoroso. Decida se o IT…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Você é um classificador rigoroso. Decida se o ITEM atende ao CRITÉRIO.

CRITÉRIO:
{{criteria}}

ITEM:
{{item}}

""",
        ),
        PromptDef(
            id = "workflow.3",
            group = "Fluxos",
            title = "Nós de IA · Nada para julgar: o item chegou vazio (confira o…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Nada para julgar: o item chegou vazio (confira o campo "Item a julgar").""",
        ),
        PromptDef(
            id = "workflow.4",
            group = "Fluxos",
            title = "Nós de IA · A IA não devolveu uma decisão válida: …",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """A IA não devolveu uma decisão válida: {{raw}}""",
        ),
        PromptDef(
            id = "workflow.5",
            group = "Fluxos",
            title = "Nós de IA · API whatsapp_send_text não está no hub.",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """API whatsapp_send_text não está no hub.""",
        ),
        PromptDef(
            id = "workflow.6",
            group = "Fluxos",
            title = "Nós de IA · Responda só com o texto da mensagem a enviar.",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """
Responda só com o texto da mensagem a enviar.""",
        ),
        PromptDef(
            id = "workflow.7",
            group = "Fluxos",
            title = "Nós de IA · Regras: responda em português do Brasil, como me…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Regras: responda em português do Brasil, como mensagem de WhatsApp (curta, no máximo 3 frases, sem markdown). Use APENAS informações da base de conhecimento abaixo; se não souber, diga que vai confirmar e não invente preços, prazos ou coberturas. Nunca peça senha nem dados de cartão.""",
        ),
        PromptDef(
            id = "workflow.8",
            group = "Fluxos",
            title = "Nós de IA · Você é um atendente cordial e objetivo.",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Você é um atendente cordial e objetivo.""",
        ),
        PromptDef(
            id = "workflow.9",
            group = "Fluxos",
            title = "Nós de IA · Objetivo: atender bem e responder as dúvidas.",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Objetivo: atender bem e responder as dúvidas.""",
        ),
        PromptDef(
            id = "workflow.10",
            group = "Fluxos",
            title = "Nós de IA · Objetivo: resolver o problema do cliente passo a…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Objetivo: resolver o problema do cliente passo a passo com base nas instruções da base.""",
        ),
        PromptDef(
            id = "workflow.11",
            group = "Fluxos",
            title = "Nós de IA · Objetivo: reconquistar cliente que se afastou. P…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Objetivo: reconquistar cliente que se afastou. Pergunte o motivo, valorize o relacionamento e ofereça o que a base permitir (condição, benefício).""",
        ),
        PromptDef(
            id = "workflow.12",
            group = "Fluxos",
            title = "Nós de IA · Objetivo: recuperar pagamento pendente com empat…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Objetivo: recuperar pagamento pendente com empatia. Lembre o valor/vencimento se estiver na base, ofereça as formas de pagamento/negociação previstas e confirme o próximo passo.""",
        ),
        PromptDef(
            id = "workflow.13",
            group = "Fluxos",
            title = "Nós de IA · Objetivo: converter em venda. Entenda a necessid…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """Objetivo: converter em venda. Entenda a necessidade, apresente a opção mais adequada da base, trate objeções com fatos da base e conduza ao próximo passo (proposta, link, contratação). Sem pressão agressiva.""",
        ),
        PromptDef(
            id = "workflow.14",
            group = "Fluxos",
            title = "Nós de IA · A conversa \"…\" não respondeu em 5 minutos.",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """A conversa "{{sess_title}}" não respondeu em 5 minutos.""",
        ),
        PromptDef(
            id = "workflow.15",
            group = "Fluxos",
            title = "Nós de IA · [WhatsApp · …] … (Responda de forma curta: sua r…",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """[WhatsApp · {{chat}}] {{text}}
(Responda de forma curta: sua resposta será enviada de volta no WhatsApp.)""",
        ),
        PromptDef(
            id = "workflow.16",
            group = "Fluxos",
            title = "Nós de IA · (teste) a mensagem iria para a conversa \"…\" e a …",
            description = "Trecho do prompt \"Nós de IA\" montado conforme o contexto.",
            default = """(teste) a mensagem iria para a conversa "{{sess_title}}" e a resposta dela voltaria aqui.""",
        ),
    )
}
