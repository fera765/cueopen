package dev.fluwy.app.workflow

import kotlinx.serialization.Serializable

/** Fluxo estilo n8n: nós ligados por arestas, disparados manualmente ou por horário. */
@Serializable
data class Workflow(
    val id: String = WfIds.new(),
    val name: String = "Novo fluxo",
    val enabled: Boolean = false,
    val nodes: List<WfNode> = emptyList(),
    val edges: List<WfEdge> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Assinatura (WfLogic.signature) da versão que passou no último teste completo; vazio = nunca testado. */
    val testedSig: String = "",
    /** Autonomia total: nós "Revisar e aprovar" seguem sozinhos (sem esperar o usuário). */
    val autonomous: Boolean = false,
)

@Serializable
data class WfNode(
    val id: String = WfIds.new(),
    val type: String,
    val label: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val config: Map<String, String> = emptyMap(),
)

/** Aresta da saída [port] de [from] para a entrada de [to]. Porta "main" (padrão), "true"/"false" (Condição). */
@Serializable
data class WfEdge(val id: String = WfIds.new(), val from: String, val to: String, val port: String = "main")

@Serializable
data class WfRun(
    val id: String = WfIds.new(),
    val workflowId: String,
    val workflowName: String = "",
    val trigger: String = "manual",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** running · ok · error · waiting (aprovação) · cancelled */
    val status: String = "running",
    val steps: List<WfStep> = emptyList(),
    val error: String = "",
)

@Serializable
data class WfStep(
    val nodeId: String,
    val label: String,
    val type: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val output: String = "",
    val error: String = "",
    /** Chat criado pelo nó (Agente IA), para abrir a conversa completa. */
    val sessionId: String? = null,
)

object WfIds {
    fun new(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
}

/** Nó pronto da paleta: um tipo básico com nome e configuração já preenchidos. */
data class WfPreset(val title: String, val category: String, val type: String, val description: String, val config: Map<String, String>)

/** Campo de configuração de um tipo de nó (o editor monta o formulário a partir disto). */
data class WfField(
    val key: String,
    val label: String,
    val kind: Kind = Kind.TEXT,
    val hint: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val default: String = "",
    val required: Boolean = false,
) {
    enum class Kind { TEXT, MULTILINE, NUMBER, SELECT, TIME, JSON }
}

/** Tipo de nó do catálogo. [ports] = saídas ("main" ou "true"/"false"). */
data class WfNodeType(
    val type: String,
    val title: String,
    val category: String,
    val description: String,
    val color: Long,
    val icon: String,
    val fields: List<WfField> = emptyList(),
    val ports: List<String> = listOf("main"),
    val trigger: Boolean = false,
    /** Fora da paleta (tipo antigo mantido só para fluxos já salvos). */
    val hidden: Boolean = false,
)

object WfCatalog {
    const val CAT_TRIGGER = "Gatilhos"
    const val CAT_AI = "IA e agentes"
    const val CAT_APPS = "Apps e integrações"
    const val CAT_LOGIC = "Lógica e dados"
    const val CAT_OUT = "Saídas"

    val TEMPLATE_HINT = "Use {{prev}} (saída do nó anterior), {{<id do nó>}} ou {{<id do nó>.campo}}, {{trigger.time}}."

    val TYPES: List<WfNodeType> = listOf(
        WfNodeType("trigger_manual", "Manual", CAT_TRIGGER, "Roda quando você toca em Executar.", 0xFF8A8F98, "play", trigger = true),
        WfNodeType("trigger_schedule", "Agendamento", CAT_TRIGGER, "Roda sozinho por intervalo, todo dia ou toda semana.", 0xFFFFA62B, "schedule", trigger = true,
            fields = listOf(
                WfField("mode", "Repetir", WfField.Kind.SELECT, options = listOf("interval" to "A cada X minutos", "daily" to "Todo dia", "weekly" to "Toda semana"), default = "daily"),
                WfField("minutes", "A cada (minutos)", WfField.Kind.NUMBER, "Mínimo 1", default = "60"),
                WfField("time", "Horário (HH:mm)", WfField.Kind.TIME, default = "09:00"),
                WfField("weekday", "Dia da semana", WfField.Kind.SELECT, options = listOf("1" to "Segunda", "2" to "Terça", "3" to "Quarta", "4" to "Quinta", "5" to "Sexta", "6" to "Sábado", "7" to "Domingo"), default = "1"),
            )),
        WfNodeType("trigger_webhook", "Webhook", CAT_TRIGGER, "Roda quando um sistema externo chama o endereço do fluxo (ponte segura pelo GitHub: cada chamada é um comentário numa issue, conferido a cada minuto).", 0xFF2EA043, "webhook", trigger = true,
            fields = listOf(
                WfField("repo", "Repositório (dono/repo)", WfField.Kind.TEXT, "Ex.: fulano/meus-webhooks", required = true),
                WfField("issue", "Número da issue", WfField.Kind.NUMBER, "A issue que recebe as chamadas", required = true),
                WfField("secret", "Segredo (1ª linha do comentário)", WfField.Kind.TEXT, "Mínimo 12 caracteres; chamadas sem ele são ignoradas", required = true),
                WfField("sample", "Exemplo de payload (usado no teste)", WfField.Kind.MULTILINE, "{\"evento\": \"teste\"}", default = "{\"evento\": \"teste\"}"),
            )),
        WfNodeType("trigger_whatsapp", "Mensagem no WhatsApp", CAT_TRIGGER, "Roda na hora em que chega uma mensagem no seu WhatsApp (webhook do API Hub › WhatsApp). Dados: {{trigger.payload.text}}, {{trigger.payload.chat}}, {{trigger.payload.chat_name}}, {{trigger.payload.sender_name}}, {{trigger.payload.type}}, {{trigger.payload.id}}.", 0xFF25D366, "webhook", trigger = true,
            fields = listOf(
                WfField("chat", "Conversa ou grupo (vazio = todas)", WfField.Kind.TEXT, "Nome do grupo/contato, número ou id (ex.: Família, 5511999999999)"),
                WfField("scope", "Tipo de conversa", WfField.Kind.SELECT, options = listOf("all" to "Todas", "private" to "Só conversas privadas", "groups" to "Só grupos"), default = "all"),
                WfField("keyword", "Só se contiver (opcional)", WfField.Kind.TEXT, "Palavras separadas por | (ex.: pedido|orçamento)"),
                WfField("from_me", "Incluir mensagens enviadas por mim", WfField.Kind.SELECT, options = listOf("false" to "Não", "true" to "Sim"), default = "false"),
                WfField("sample", "Exemplo de mensagem (usado no teste)", WfField.Kind.MULTILINE, default = "{\"chat\": \"5511999999999@c.us\", \"chat_name\": \"Contato\", \"sender_name\": \"Contato\", \"type\": \"chat\", \"text\": \"Olá, quero um orçamento\", \"is_group\": false, \"from_me\": false}"),
            )),
        WfNodeType("ai", "IA (texto)", CAT_AI, "Uma chamada rápida ao modelo, sem ferramentas: resumir, traduzir, classificar, formatar ou extrair dados do que veio antes.", 0xFF9B7BFF, "ai",
            fields = listOf(
                WfField("prompt", "Instrução", WfField.Kind.MULTILINE, "Inclua os dados com {{prev}} ou {{id.campo}}. $TEMPLATE_HINT", required = true),
                WfField("maxTokens", "Tamanho máximo da resposta (tokens)", WfField.Kind.NUMBER, default = "800"),
            )),
        WfNodeType("ai_decide", "Decisão por IA", CAT_AI, "A IA julga o item pelos seus critérios (ex.: \"precisa de resposta?\", \"é comercial?\") e segue por sim ou não, com o motivo.", 0xFFB06BFF, "decide",
            fields = listOf(
                WfField("criteria", "Critério (pergunta de sim/não)", WfField.Kind.MULTILINE, "Ex.: É um e-mail importante que precisa da minha resposta (cliente, comercial, atendimento, fulano@…)? Ignore propaganda e notificações automáticas.", required = true),
                WfField("input", "Item a julgar", WfField.Kind.TEXT, TEMPLATE_HINT, default = "{{prev}}"),
            ),
            ports = listOf("true", "false")),
        WfNodeType("agent", "Agente IA", CAT_AI, "Um agente do app executa a instrução (com ferramentas, browser, terminal e conectores) e devolve a resposta.", 0xFF7C5CFF, "agent",
            fields = listOf(
                WfField("prompt", "Instrução", WfField.Kind.MULTILINE, TEMPLATE_HINT, required = true),
                WfField("agent", "Agente (nome; vazio = padrão)", WfField.Kind.TEXT),
                WfField("timeout", "Tempo máximo (minutos)", WfField.Kind.NUMBER, default = "15"),
                WfField("maxSteps", "Máximo de passos", WfField.Kind.NUMBER, "Teto de ações do agente (economiza tokens)", default = "14"),
                WfField("budget", "Orçamento de tokens", WfField.Kind.NUMBER, "Ao chegar no teto, o agente entrega o que tem", default = "200000"),
            )),
        WfNodeType("connector", "Conector", CAT_APPS, "Usa qualquer conector ativo — apps (Gmail, Sheets, Slack, Notion…), servidores MCP ou conectores do app: escolha o conector, a ação e preencha os campos.", 0xFF2FBF71, "apps",
            fields = listOf(
                WfField("source", "Tipo", WfField.Kind.SELECT, options = listOf("hub" to "App conectado", "mcp" to "MCP", "app" to "Conector do app"), default = "hub"),
                WfField("conector", "Conector", WfField.Kind.TEXT, "Ex.: gmail", required = true),
                WfField("acao", "Ação", WfField.Kind.TEXT, "Ex.: send_email", required = true),
                WfField("campos", "Campos (JSON)", WfField.Kind.JSON, "Ex.: {\"to\":\"{{prev}}\"}. $TEMPLATE_HINT", default = "{}"),
                WfField("conexao", "Conta (opcional)", WfField.Kind.TEXT),
            )),
        WfNodeType("mcp", "Ferramenta MCP (antigo)", CAT_APPS, "Use o nó Conector › MCP.", 0xFF2EC4C4, "mcp", hidden = true,
            fields = listOf(
                WfField("tool", "Ferramenta", WfField.Kind.TEXT, "mcp_<conector>__<ferramenta>", required = true),
                WfField("args", "Argumentos (JSON)", WfField.Kind.JSON, TEMPLATE_HINT, default = "{}"),
            )),
        WfNodeType("webapi", "Web API do hub", CAT_APPS, "Chama uma Web API do API Hub (ex.: whatsapp_send_text, whatsapp_messages) e devolve o JSON. Ações externas (enviar, apagar) rodam de verdade só no fluxo publicado; no teste são validadas sem enviar.", 0xFF25D366, "http",
            fields = listOf(
                WfField("api", "API (slug)", WfField.Kind.TEXT, "Ex.: whatsapp_send_text", required = true),
                WfField("args", "Argumentos (JSON)", WfField.Kind.MULTILINE, "Ex.: {\"to\": \"{{trigger.payload.chat}}\", \"text\": \"Recebido!\"} — $TEMPLATE_HINT", default = "{}"),
            )),
        WfNodeType("atendente", "Atendente de WhatsApp (IA)", CAT_AI, "Responde quem manda mensagem: lê a mensagem do gatilho \"Mensagem no WhatsApp\", o histórico da conversa e a sua base de conhecimento, responde com IA e envia no próprio chat. Pode também acordar uma conversa do app e devolver a resposta dela.", 0xFF25D366, "ai",
            fields = listOf(
                WfField("persona", "Quem é o atendente", WfField.Kind.MULTILINE, "Ex.: Você é a Ana, consultora da Saúde+; tom cordial e direto.", default = "Você é um atendente cordial e objetivo."),
                WfField("knowledge", "Base de conhecimento / treinamento", WfField.Kind.MULTILINE, "Planos, preços, regras, perguntas frequentes, políticas. O atendente só afirma o que estiver aqui."),
                WfField("objective", "Objetivo", WfField.Kind.SELECT, options = listOf("atendimento" to "Atendimento geral", "vendas" to "Vendas (converter)", "recuperar_pagamento" to "Recuperar pagamento", "recuperar_cliente" to "Recuperar cliente", "suporte" to "Suporte"), default = "atendimento"),
                WfField("history", "Mensagens anteriores lidas", WfField.Kind.NUMBER, default = "10"),
                WfField("reply", "Envio", WfField.Kind.SELECT, options = listOf("auto" to "Responder sozinho", "draft" to "Só gerar a resposta (não enviar)"), default = "auto"),
                WfField("session", "Conversa do app para acordar (opcional)", WfField.Kind.TEXT, "Título da conversa: a mensagem vai para ela e a resposta dela volta para o WhatsApp"),
                WfField("handoff", "Passar para humano quando contiver", WfField.Kind.TEXT, "Ex.: atendente|humano|reclamação", default = "falar com humano|atendente humano"),
            )),
        WfNodeType("http", "Requisição HTTP", CAT_APPS, "Chama qualquer API (GET, POST, PUT, PATCH, DELETE).", 0xFF4D8DFF, "http",
            fields = listOf(
                WfField("method", "Método", WfField.Kind.SELECT, options = listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT", "PATCH" to "PATCH", "DELETE" to "DELETE"), default = "GET"),
                WfField("url", "URL", WfField.Kind.TEXT, TEMPLATE_HINT, required = true),
                WfField("headers", "Cabeçalhos (um por linha: Nome: valor)", WfField.Kind.MULTILINE),
                WfField("body", "Corpo", WfField.Kind.MULTILINE, TEMPLATE_HINT),
                WfField("timeout", "Tempo máximo (segundos)", WfField.Kind.NUMBER, default = "30"),
                WfField("onError", "Se der erro (4xx/5xx ou sem rede)", WfField.Kind.SELECT, options = listOf("stop" to "Parar o fluxo", "continue" to "Continuar (status 0 se sem rede)"), default = "stop"),
            )),
        WfNodeType("shell", "Terminal", CAT_APPS, "Roda um comando no Linux do app e devolve a saída.", 0xFF9C978E, "terminal",
            fields = listOf(
                WfField("command", "Comando", WfField.Kind.MULTILINE, TEMPLATE_HINT, required = true),
                WfField("timeout", "Tempo máximo (segundos)", WfField.Kind.NUMBER, default = "120"),
            )),
        WfNodeType("set", "Definir dados", CAT_LOGIC, "Monta campos novos a partir das saídas anteriores.", 0xFFB57BFF, "set",
            fields = listOf(WfField("values", "Campos (um por linha: nome = valor)", WfField.Kind.MULTILINE, TEMPLATE_HINT, required = true))),
        WfNodeType("if", "Condição (IF)", CAT_LOGIC, "Segue pelo caminho Verdadeiro ou Falso.", 0xFFFF5C8A, "branch",
            fields = listOf(
                WfField("left", "Valor", WfField.Kind.TEXT, TEMPLATE_HINT, default = "{{prev}}"),
                WfField("op", "Operador", WfField.Kind.SELECT, options = listOf(
                    "contains" to "contém", "not_contains" to "não contém", "equals" to "igual a", "not_equals" to "diferente de",
                    "gt" to "maior que", "lt" to "menor que", "empty" to "está vazio", "not_empty" to "não está vazio", "regex" to "casa com regex"), default = "contains"),
                WfField("right", "Comparar com", WfField.Kind.TEXT),
            ),
            ports = listOf("true", "false")),
        WfNodeType("if_new", "Se for novo", CAT_LOGIC, "Lembra o que já viu entre execuções: segue por \"sim\" só quando a chave (ex.: id do último e-mail) ainda não apareceu.", 0xFFFFB020, "new",
            fields = listOf(
                WfField("key", "Chave que identifica o item", WfField.Kind.TEXT, "Ex.: {{g1.data.results.messages.0.id}}", required = true),
                WfField("first", "Na primeira execução", WfField.Kind.SELECT, options = listOf("new" to "Tratar como novo", "skip" to "Só memorizar"), default = "skip"),
            ),
            ports = listOf("true", "false")),
        WfNodeType("review", "Revisar e aprovar", CAT_LOGIC, "Mostra o item (e-mail com fotos e anexos) e um rascunho editável numa página; você aprova (segue por sim com o texto final) ou recusa. Em modo automático segue sozinho.", 0xFF2FBF71, "review",
            fields = listOf(
                WfField("title", "Título", WfField.Kind.TEXT, default = "Revisar resposta"),
                WfField("item", "Item a mostrar", WfField.Kind.TEXT, "Ex.: {{g1}} (o e-mail). $TEMPLATE_HINT", default = "{{prev}}"),
                WfField("draft", "Rascunho (você pode editar antes de aprovar)", WfField.Kind.MULTILINE, "Ex.: {{a1}} (resposta escrita pela IA)"),
                WfField("mode", "Aprovação", WfField.Kind.SELECT, options = listOf("ask" to "Pedir minha aprovação", "auto" to "Automático (sem perguntar)"), default = "ask"),
                WfField("hours", "Esperar até (horas)", WfField.Kind.NUMBER, default = "24"),
            ),
            ports = listOf("true", "false")),
        WfNodeType("delay", "Esperar", CAT_LOGIC, "Pausa o fluxo por um tempo.", 0xFF56CCF2, "timer",
            fields = listOf(WfField("seconds", "Segundos", WfField.Kind.NUMBER, default = "10"))),
        WfNodeType("approval", "Aprovação", CAT_LOGIC, "Pausa até você aprovar ou recusar (notificação + tela de execuções).", 0xFFFFC53D, "approval",
            fields = listOf(
                WfField("question", "Pergunta", WfField.Kind.MULTILINE, TEMPLATE_HINT, default = "Posso continuar?"),
                WfField("hours", "Esperar até (horas)", WfField.Kind.NUMBER, default = "24"),
            ),
            ports = listOf("true", "false")),
        WfNodeType("notify", "Notificação", CAT_OUT, "Mostra uma notificação no celular.", 0xFFFF6B3D, "notify",
            fields = listOf(
                WfField("title", "Título", WfField.Kind.TEXT, default = "Fluxo"),
                WfField("text", "Texto", WfField.Kind.MULTILINE, TEMPLATE_HINT, default = "{{prev}}"),
                WfField("detail", "Conteúdo completo (quadro/página ao tocar)", WfField.Kind.MULTILINE, "Vazio = cartões com os dados do nó anterior. Aceita {{id}} (dados viram cartões), texto/Markdown ou um modelo do UI kit {\"blocks\":[…]}. $TEMPLATE_HINT"),
                WfField("layout", "Visual do quadro/página", WfField.Kind.SELECT, options = listOf("auto" to "Cartões (UI kit)", "markdown" to "Texto formatado"), default = "auto"),
                WfField("open", "Ao tocar na notificação", WfField.Kind.SELECT, options = listOf("modal" to "Quadro com o conteúdo", "page" to "Página com o conteúdo", "chat" to "Chat do fluxo (conversa com o conteúdo)", "flow" to "Abrir o fluxo", "none" to "Só abrir o app"), default = "modal"),
            )),
    )

    fun of(type: String): WfNodeType = TYPES.firstOrNull { it.type == type } ?: TYPES.first()

    /** Nós prontos (já configurados) que aparecem na paleta, além dos tipos básicos. */
    val PRESETS: List<WfPreset> = listOf(
        // Gatilhos
        WfPreset("Todo dia às 8h", CAT_TRIGGER, "trigger_schedule", "Roda todos os dias às 08:00.", mapOf("mode" to "daily", "time" to "08:00")),
        WfPreset("A cada 15 minutos", CAT_TRIGGER, "trigger_schedule", "Roda a cada 15 minutos.", mapOf("mode" to "interval", "minutes" to "15")),
        WfPreset("Toda segunda às 9h", CAT_TRIGGER, "trigger_schedule", "Roda toda segunda-feira às 09:00.", mapOf("mode" to "weekly", "weekday" to "1", "time" to "09:00")),
        WfPreset("Webhook (GitHub)", CAT_TRIGGER, "trigger_webhook", "Roda quando chega uma chamada na issue configurada.", mapOf("sample" to "{\"evento\": \"teste\"}")),
        WfPreset("Mensagem no WhatsApp", CAT_TRIGGER, "trigger_whatsapp", "Roda quando chega mensagem no WhatsApp (todas as conversas).", mapOf("scope" to "all")),
        WfPreset("Mensagem num grupo do WhatsApp", CAT_TRIGGER, "trigger_whatsapp", "Roda quando chega mensagem no grupo escolhido.", mapOf("scope" to "groups", "chat" to "")),
        WfPreset("Responder no WhatsApp", CAT_APPS, "webapi", "Envia uma resposta para a conversa que disparou o fluxo.", mapOf("api" to "whatsapp_send_text", "args" to "{\"to\": \"{{trigger.payload.chat}}\", \"text\": \"{{prev}}\", \"reply_to\": \"{{trigger.payload.id}}\"}")),
        WfPreset("Atendente de vendas no WhatsApp", CAT_AI, "atendente", "Responde clientes com base no seu conhecimento, focado em converter.", mapOf("objective" to "vendas", "reply" to "auto")),
        WfPreset("Toda hora", CAT_TRIGGER, "trigger_schedule", "Roda a cada 60 minutos.", mapOf("mode" to "interval", "minutes" to "60")),
        // IA
        WfPreset("Resumir texto", CAT_AI, "ai", "Resume a saída anterior em tópicos curtos.", mapOf("prompt" to "Resuma em até 5 tópicos curtos, em português:\n\n{{prev}}")),
        WfPreset("Traduzir para inglês", CAT_AI, "ai", "Traduz a saída anterior.", mapOf("prompt" to "Traduza para o inglês, mantendo o tom, e responda só com a tradução:\n\n{{prev}}")),
        WfPreset("Classificar sentimento", CAT_AI, "ai", "Responde positivo, negativo ou neutro.", mapOf("prompt" to "Classifique o sentimento do texto abaixo. Responda só com uma palavra: positivo, negativo ou neutro.\n\n{{prev}}")),
        WfPreset("Pesquisar na web", CAT_AI, "agent", "Pesquisa um tema e traz as fontes.", mapOf("prompt" to "Pesquise na web as novidades mais recentes sobre: inteligência artificial. Traga 5 itens, cada um com uma linha e o link.")),
        WfPreset("Notícias do dia", CAT_AI, "agent", "As notícias mais importantes de hoje, resumidas.", mapOf("prompt" to "Liste as 5 notícias mais importantes de hoje no Brasil, uma linha cada, com o link da fonte.")),
        WfPreset("Post para redes sociais", CAT_AI, "ai", "Escreve um post a partir da saída anterior.", mapOf("prompt" to "Escreva um post curto e envolvente para Instagram (com 3 hashtags) a partir disto:\n\n{{prev}}")),
        WfPreset("Extrair dados (JSON)", CAT_AI, "ai", "Transforma texto em JSON estruturado.", mapOf("prompt" to "Extraia nome, e-mail, telefone e assunto do texto abaixo e responda APENAS com um JSON válido com essas chaves:\n\n{{prev}}")),
        WfPreset("Responder e-mail", CAT_AI, "ai", "Rascunha uma resposta educada.", mapOf("prompt" to "Escreva uma resposta curta, cordial e profissional para esta mensagem:\n\n{{prev}}")),
        // Apps (hub de conectores / APIs)
        WfPreset("Gmail · enviar e-mail", CAT_APPS, "connector", "Envia um e-mail pelo Gmail conectado.", mapOf("source" to "hub", "conector" to "gmail", "acao" to "send_email",
            "campos" to "{\"receiver\": [\"email@exemplo.com\"], \"subject\": \"Resumo\", \"body_text\": \"{{prev}}\"}")),
        WfPreset("Google Sheets · nova linha", CAT_APPS, "connector", "Adiciona uma linha numa planilha.", mapOf("source" to "hub", "conector" to "google-sheets", "acao" to "insert_row",
            "campos" to "{\"values\": {\"A\": \"{{trigger.time}}\", \"B\": \"{{prev}}\"}}")),
        WfPreset("Slack · mensagem", CAT_APPS, "connector", "Envia mensagem num canal do Slack.", mapOf("source" to "hub", "conector" to "slack", "acao" to "send_channel_message",
            "campos" to "{\"channel\": \"#geral\", \"text\": \"{{prev}}\"}")),
        WfPreset("Notion · nova página", CAT_APPS, "connector", "Cria um item num banco do Notion.", mapOf("source" to "hub", "conector" to "notion", "acao" to "create_database_item",
            "campos" to "{\"title\": \"{{trigger.time}}\", \"content\": \"{{prev}}\"}")),
        WfPreset("Telegram · mensagem", CAT_APPS, "http", "Envia mensagem por um bot do Telegram (troque TOKEN e CHAT_ID).", mapOf("method" to "POST",
            "url" to "https://api.telegram.org/botTOKEN/sendMessage", "headers" to "Content-Type: application/json",
            "body" to "{\"chat_id\": \"CHAT_ID\", \"text\": \"{{prev}}\"}")),
        WfPreset("Discord · webhook", CAT_APPS, "http", "Posta num canal do Discord (cole a URL do webhook).", mapOf("method" to "POST",
            "url" to "https://discord.com/api/webhooks/SEU_WEBHOOK", "headers" to "Content-Type: application/json", "body" to "{\"content\": \"{{prev}}\"}")),
        WfPreset("Webhook (POST JSON)", CAT_APPS, "http", "Envia os dados para qualquer URL.", mapOf("method" to "POST", "url" to "https://seu-servidor.com/webhook",
            "headers" to "Content-Type: application/json", "body" to "{\"data\": \"{{prev}}\", \"time\": \"{{trigger.time}}\"}")),
        WfPreset("Cotação do dólar", CAT_APPS, "http", "USD → BRL agora (AwesomeAPI).", mapOf("method" to "GET", "url" to "https://economia.awesomeapi.com.br/json/last/USD-BRL")),
        WfPreset("Preço do Bitcoin", CAT_APPS, "http", "BTC em reais e dólares (CoinGecko).", mapOf("method" to "GET", "url" to "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=brl,usd")),
        WfPreset("Clima agora (São Paulo)", CAT_APPS, "http", "Temperatura e vento atuais (Open-Meteo).", mapOf("method" to "GET",
            "url" to "https://api.open-meteo.com/v1/forecast?latitude=-23.55&longitude=-46.63&current=temperature_2m,wind_speed_10m")),
        WfPreset("Verificar site no ar", CAT_APPS, "http", "Baixa uma página e devolve o status.", mapOf("method" to "GET", "url" to "https://example.com", "timeout" to "20", "onError" to "continue")),
        WfPreset("Espaço em disco", CAT_APPS, "shell", "Uso do armazenamento do Linux do app.", mapOf("command" to "df -h /root | tail -1")),
        // Lógica
        WfPreset("Se contém texto", CAT_LOGIC, "if", "Segue pelo sim se a saída contém um texto.", mapOf("left" to "{{prev}}", "op" to "contains", "right" to "erro")),
        WfPreset("Se HTTP deu certo", CAT_LOGIC, "if", "Sim quando o status HTTP é 200.", mapOf("left" to "{{prev.status}}", "op" to "equals", "right" to "200")),
        WfPreset("Se número maior que", CAT_LOGIC, "if", "Compara números (ex.: preço).", mapOf("left" to "{{prev}}", "op" to "gt", "right" to "100")),
        WfPreset("Se está vazio", CAT_LOGIC, "if", "Sim quando a saída anterior veio vazia.", mapOf("left" to "{{prev}}", "op" to "empty")),
        WfPreset("Esperar 1 minuto", CAT_LOGIC, "delay", "Pausa 60 segundos.", mapOf("seconds" to "60")),
        WfPreset("Só se for novo", CAT_LOGIC, "if_new", "Continua só quando o item (ex.: último e-mail) ainda não foi visto.", mapOf("key" to "{{prev}}", "first" to "skip")),
        WfPreset("Pedir aprovação", CAT_LOGIC, "approval", "Você aprova antes de continuar.", mapOf("question" to "Posso continuar com isto?\n\n{{prev}}")),
        WfPreset("Montar mensagem", CAT_LOGIC, "set", "Cria campos a partir das saídas.", mapOf("values" to "titulo = Resultado de {{trigger.time}}\ntexto = {{prev}}")),
        // Saídas
        WfPreset("Notificar resultado", CAT_OUT, "notify", "Mostra a saída anterior numa notificação.", mapOf("title" to "Fluxo concluído", "text" to "{{prev}}")),
        WfPreset("Alerta de problema", CAT_OUT, "notify", "Notificação de alerta.", mapOf("title" to "⚠ Atenção", "text" to "Algo deu errado: {{prev}}")),
    )
    fun categories(): List<String> = listOf(CAT_TRIGGER, CAT_AI, CAT_APPS, CAT_LOGIC, CAT_OUT)
}
