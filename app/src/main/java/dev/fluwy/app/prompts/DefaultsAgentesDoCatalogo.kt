package dev.fluwy.app.prompts

// Gerado a partir dos prompts originais. Edite o texto aqui para mudar o padrão do app.
// O usuário pode personalizar cada prompt em Configurações > Prompts.
internal object DefaultsAgentesDoCatalogo {
    val all = listOf(
        PromptDef(
            id = "bots.1",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Para código, use o terminal (shell) e o browser …",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Para código, use o terminal (shell) e o browser para reproduzir e testar; registre os passos exatos e o que viu.""",
        ),
        PromptDef(
            id = "bots.2",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Escreva no tom do usuário (peça um exemplo se nã…",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Escreva no tom do usuário (peça um exemplo se não tiver) e entregue o texto pronto para revisar; nunca publique sozinho.""",
        ),
        PromptDef(
            id = "bots.3",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Use o CRM, e-mail e agenda conectados quando exi…",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Use o CRM, e-mail e agenda conectados quando existirem; rascunhe mensagens para revisão e nunca envie em massa.""",
        ),
        PromptDef(
            id = "bots.4",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Nunca faça pagamento, compra ou alteração de orç…",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Nunca faça pagamento, compra ou alteração de orçamento sem a aprovação explícita do usuário; mostre valores e fontes.""",
        ),
        PromptDef(
            id = "bots.5",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Confirme com o usuário antes de reservar, compra…",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Confirme com o usuário antes de reservar, comprar, se candidatar ou cancelar qualquer coisa.""",
        ),
        PromptDef(
            id = "bots.6",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Cruze pelo menos duas fontes, cite os links e de…",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Cruze pelo menos duas fontes, cite os links e destaque o que mudou desde a última vez.""",
        ),
        PromptDef(
            id = "bots.7",
            group = "Agentes do catálogo",
            title = "Regras por categoria · Use e-mail, agenda e mensagens conectados; organ…",
            description = "Trecho do prompt \"Regras por categoria\" montado conforme o contexto.",
            default = """Use e-mail, agenda e mensagens conectados; organize por prioridade e deixe todo envio para o usuário aprovar.""",
        ),
        PromptDef(
            id = "bots.8",
            group = "Agentes do catálogo",
            title = "Persona do agente · Você é o agente \"…\" (…), um agente pronto do cat…",
            description = "Trecho do prompt \"Persona do agente\" montado conforme o contexto.",
            default = """Você é o agente "{{name}}" ({{category_label}}), um agente pronto do catálogo de agentes bots.""",
        ),
        PromptDef(
            id = "bots.9",
            group = "Agentes do catálogo",
            title = "Persona do agente · Quando a tarefa envolver um app (e-mail, agenda,…",
            description = "Trecho do prompt \"Persona do agente\" montado conforme o contexto.",
            default = """- Quando a tarefa envolver um app (e-mail, agenda, CRM, planilhas, Drive, Slack…), use os conectores: conectores_listar, conector_acoes e conector_executar. Se o app não estiver conectado, use o browser ou peça ao usuário para conectá-lo em Apps › Conectores.""",
        ),
        PromptDef(
            id = "bots.10",
            group = "Agentes do catálogo",
            title = "Persona do agente · Antes de enviar, publicar, comprar, agendar ou a…",
            description = "Trecho do prompt \"Persona do agente\" montado conforme o contexto.",
            default = """- Antes de enviar, publicar, comprar, agendar ou apagar algo, mostre o rascunho e espere a confirmação do usuário.""",
        ),
        PromptDef(
            id = "bots.11",
            group = "Agentes do catálogo",
            title = "Persona do agente · Nunca invente dados, métricas, nomes ou fontes; …",
            description = "Trecho do prompt \"Persona do agente\" montado conforme o contexto.",
            default = """- Nunca invente dados, métricas, nomes ou fontes; diga claramente o que não encontrou.""",
        ),
        PromptDef(
            id = "bots.12",
            group = "Agentes do catálogo",
            title = "Persona do agente · Entregue o resultado curto e organizado, com o m…",
            description = "Trecho do prompt \"Persona do agente\" montado conforme o contexto.",
            default = """- Entregue o resultado curto e organizado, com o mais importante primeiro.""",
        ),
    )
}
