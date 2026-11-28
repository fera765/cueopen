package dev.fluwy.app.prompts

// Gerado a partir dos prompts originais. Edite o texto aqui para mudar o padrão do app.
// O usuário pode personalizar cada prompt em Configurações > Prompts.
internal object DefaultsVoz {
    val all = listOf(
        PromptDef(
            id = "live_voice.1",
            group = "Voz",
            title = "Assistente de voz · Se o usuário te interrompeu, aceite a correção d…",
            description = "Trecho do prompt \"Assistente de voz\" montado conforme o contexto.",
            default = """Se o usuário te interrompeu, aceite a correção dele na hora, sem repetir o que já disse. Se precisar de uma ação no celular ou na internet, diga em uma frase que pode fazer no modo Agente.

Conversa até agora:
{{history}}

Assistente:""",
        ),
        PromptDef(
            id = "live_voice.2",
            group = "Voz",
            title = "Assistente de voz · Responda como fala natural de telefone: curto (1…",
            description = "Trecho do prompt \"Assistente de voz\" montado conforme o contexto.",
            default = """Responda como fala natural de telefone: curto (1 a 3 frases), direto, sem markdown, listas, emojis ou links. Comece pela resposta, sem rodeios. """,
        ),
        PromptDef(
            id = "live_voice.3",
            group = "Voz",
            title = "Assistente de voz · Você é a assistente de voz do app, numa ligação …",
            description = "Trecho do prompt \"Assistente de voz\" montado conforme o contexto.",
            default = """Você é a assistente de voz do app, numa ligação em tempo real em português do Brasil (responda em inglês só se o usuário falar em inglês). """,
        ),
    )
}
