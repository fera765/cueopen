package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

internal fun AgentRunner.tools(agentIn: AgentProfile?, allowMultiAgent: Boolean = true, builder: Boolean = false, connectorEditId: String? = null, sectionId: String? = null,
                  scriptBuilder: Boolean = false, webApiBuilder: Boolean = false) = buildJsonArray {
    if (webApiBuilder) {
        add(tool("webapi_probe",
            "DESCOBERTA DE API: abre a página do site no WebView das APIs, espera carregar, rola e devolve as requisições fetch/XHR que o site fez (refeitas de dentro da página, com status, tipo e trecho da resposta) " +
                "e os estados JSON embutidos (ytInitialPlayerResponse, __NEXT_DATA__, __NUXT__…). Use ANTES de escrever o código para achar a API interna do site.",
            buildJsonObject {
                put("url", str("Página do site (ex.: https://www.youtube.com/watch?v=…, https://g1.globo.com/)"))
                put("pattern", str("Regex opcional para filtrar as URLs das requisições (ex.: api|graphql|json|timedtext)"))
                put("desktop", bool("true = abre como computador"))
            }, listOf("url")))
        add(tool("webapi_test",
            "Testa uma Web API do hub num WebView separado (mesma sessão/cookies e proxy do browser): monta a URL pelo template, carrega, espera wait_for e roda o código. Devolve o JSON exato que a LLM vai receber.",
            buildJsonObject {
                put("url_template", str("Template literal JS com args, ex.: https://site.com/busca?q=\${enc(args.query)}&p=\${Number(args.page||1)}"))
                put("code", str("Corpo de uma função async (args, api) que lê a página e faz return { results: [...], count, has_more, next_page }"))
                put("code_file", str("Alternativa a code para código grande: caminho de um arquivo .js na área de trabalho com o corpo da função (escreva com file_write em partes)"))
                put("args", str("Argumentos de teste em JSON, ex.: {\"query\":\"tênis\",\"page\":2}"))
                put("wait_for", str("Seletor CSS que indica página pronta (opcional)"))
                put("desktop", bool("true = abre o site como computador (WhatsApp Web e outros que redirecionam no celular)"))
                put("inject", str("URLs https de bibliotecas JS a injetar antes do código, separadas por vírgula (ex.: WA-JS no WhatsApp Web)"))
            }, listOf("url_template", "args")))
        add(tool("webapi_save",
            "Salva a Web API no hub (só depois de um webapi_test que PASSOU com o mesmo código e template). Vira uma ferramenta que qualquer agente chama com webapi_call.",
            buildJsonObject {
                put("slug", str("Identificador: minúsculas, números e _ (ex.: mercadolivre_ofertas)"))
                put("name", str("Nome curto para o usuário"))
                put("description", str("O que devolve, em 1 frase (a LLM decide por ela quando usar)"))
                put("url_template", str("O mesmo do teste")); put("code", str("O mesmo do teste")); put("code_file", str("Ou o mesmo arquivo usado no teste")); put("wait_for", str("O mesmo do teste (opcional)")); put("desktop", bool("O mesmo do teste")); put("inject", str("O mesmo do teste"))
                put("params", str("JSON: [{\"name\":\"query\",\"type\":\"string\",\"description\":\"…\",\"required\":true,\"default\":\"\"}]"))
            }, listOf("slug", "name", "description", "url_template", "params")))
    }
    if (scriptBuilder) {
        add(tool("userscript_inspect",
            "Descoberta, o primeiro passo antes de escrever ou testar um userscript: abre a url (ou usa a página atual) e devolve fatos do DOM real — quantos elementos cada seletor encontra, com amostras de HTML; " +
                "onde aparece um texto visível (caminho CSS e atributos estáveis); iframes, shadow DOM e se o conteúdo é dinâmico. É barata: use quantas vezes precisar.",
            buildJsonObject {
                put("url", str("Página a inspecionar (vazio = página atual)"))
                put("selectors", str("Seletores CSS candidatos, um por linha (máx. 8)"))
                put("text", str("Texto visível do elemento-alvo, para localizar onde ele fica no DOM (opcional)"))
            }, emptyList()))
        add(tool("userscript_test",
            "Testa o userscript numa página real (recarrega, injeta com a API GM_*, espera 2,5 s) e dá o veredito. PASSOU exige duas coisas: nenhum erro (síncrono, assíncrono ou console.error) " +
                "e a expressão verify verdadeira. Devolve erros, GM_log e o valor de verify. Requer um userscript_inspect anterior; depois de 2 falhas seguidas, requer nova descoberta.",
            buildJsonObject {
                put("code", str("Script completo, com cabeçalho ==UserScript== (@name, @version, @match…)"))
                put("url", str("Página real para testar (precisa casar com @match)"))
                put("verify", str("Uma única expressão JS que comprova o efeito na página, ex.: document.querySelectorAll('.meu-badge').length  ou  !document.querySelector('.banner-cookies')"))
            }, listOf("code", "url", "verify")))
        add(tool("userscript_propose",
            "Mostra ao usuário o card \"Instalar no Browser\". Aceita apenas o mesmo código que passou no userscript_test.",
            buildJsonObject { put("code", str("Script final completo (idêntico ao que passou no teste)")) }, listOf("code")))
    }
    // ---------- Seções (time de Agents com coordenador)
    val sectionHere = store.section(sectionId)
    // O coordenador só coordena: o trabalho (browser, terminal, Android) é dos agentes do time.
    val agent = if (sectionHere != null && agentIn?.id == sectionHere.coordinatorAgentId)
        agentIn.copy(useTerminal = false, useBrowser = false, deviceCaps = emptyList(), uiConnectorIds = emptyList())
    else agentIn
    if (allowMultiAgent && sectionId == null) add(tool("create_section",
        "Cria uma seção na sidebar: um time de Agents com um coordenador e um chat próprio. Use apenas quando o usuário pedir explicitamente uma seção, time ou equipe de agentes. " +
            "Cada agente recebe nome, descrição e regras fixas exatamente como o usuário pediu. Depois de criar, o app mostra um card de autorização para o usuário escolher as permissões; aguarde ele ativar antes de qualquer outra ação. " +
            "REUTILIZE a biblioteca: se um agente necessário já existe, coloque no agents_json só {\"existing\": \"<nome exato>\"} (ele entra na seção sem duplicar); crie novo só o que não existir." +
            store.state.value.agents.filterNot { it.name.startsWith("Coordenador ·") }.take(80).joinToString("", prefix = " Biblioteca atual:") { "\n- ${it.name}: ${it.mission.take(60)}" },
        buildJsonObject {
            put("name", str("Nome da seção, como o usuário pediu (ex.: Direct Response)"))
            put("description", str("Objetivo da seção em 1-2 frases"))
            put("coordinator_instructions", str("Como o coordenador deve agir (dividir tarefas, cobrar prazos, formato do relatório, idioma), nas palavras do usuário"))
            put("agents_json", str("JSON array: [{\"name\":\"Radar\",\"role\":\"minerador\",\"description\":\"o que faz\",\"rules\":\"regras fixas\",\"needs_browser\":true,\"needs_terminal\":false}]"))
            put("first_task", str("Primeira tarefa executável, que o time começa no instante em que a seção é ativada — normalmente o pedido do usuário escrito como ação concreta. Não use \"aguardar o usuário\" nem \"esperar instruções\": se faltar algum detalhe, a primeira tarefa é o primeiro agente iniciar o levantamento ou a pesquisa com o que já se sabe."))
            put("suggested_android_caps", str("Permissões Android a sugerir no card, separadas por vírgula, entre: background, notif_post, notif_read, accessibility, screen (opcional)"))
            put("suggest_wallet", bool("true se o time precisa da carteira"))
            put("autonomous", bool("true quando o usuário pedir que a seção funcione de forma autônoma, sozinha, sem intervenção ou 100% automática"))
            put("schedule_minutes", int("Repetição pedida pelo usuário, em minutos: 60 = a cada hora, 1440 = uma vez por dia, 10080 = semanal; 0 = roda uma única vez. O próprio app faz o agendamento na ativação, então não use set_schedule para isso."))
        }, listOf("name", "agents_json")))
    if (allowMultiAgent && sectionHere != null && agent?.id == sectionHere.coordinatorAgentId) {
        add(tool("team_delegate",
            "Passa uma tarefa a um Agent do seu time, pelo nome. Ele começa na hora, no próprio chat, com as próprias regras e permissões, e o usuário vê a pílula dele trabalhando. " +
                "A chamada não bloqueia: você pode acionar vários agentes em paralelo, e recebe um [Relatório automático] quando cada um terminar. " +
                "Escreva a tarefa de forma específica: objetivo, entregável, prazo e critério de pronto. Use wait=true apenas se precisar do resultado antes de seguir.",
            buildJsonObject {
                put("agent", str("Nome exato do Agent do time"))
                put("task", str("Tarefa completa e autossuficiente (objetivo e critério de pronto): o agente não vê a sua conversa, só este texto"))
                put("output_format", str("Formato da entrega (ex.: tabela em resultados.md com colunas X/Y; lista de 10 links com nota)"))
                put("tools_hint", str("Ferramentas/fontes a usar (ex.: web_search + web_fetch em sites oficiais)"))
                put("boundaries", str("O que NÃO fazer / fronteira com a tarefa dos outros agentes"))
                put("wait", bool("true = espera o resultado (até timeout_minutes). Padrão: false"))
                put("timeout_minutes", int("Com wait=true, a espera máxima em minutos (1-30; padrão 15)"))
            }, listOf("agent", "task")))
        add(tool("team_plan",
            "Registra o plano da seção: etapas, agente responsável e dependências. O app salva o plano (ele sobrevive ao fechamento do app), inicia na hora as etapas sem dependência " +
                "e, a cada etapa que você aprova com team_review, inicia sozinho as seguintes, passando as entregas anteriores como insumo. Use no começo de todo pedido com mais de uma etapa.",
            buildJsonObject {
                put("goal", str("Objetivo final do usuário em uma frase"))
                put("tasks_json", str("Array JSON: [{\"id\":\"T1\",\"agent\":\"Pesquisador\",\"task\":\"tarefa completa, com entregável e critério de pronto\",\"depends_on\":[]}, {\"id\":\"T2\",\"agent\":\"Separador\",\"task\":\"...\",\"depends_on\":[\"T1\"]}]. Etapas sem dependência entre si rodam em paralelo."))
            }, listOf("goal", "tasks_json")))
        add(tool("team_review",
            "Valida a entrega de uma etapa do plano. approve = aprovada (as etapas dependentes começam sozinhas); redo = refazer com o ajuste descrito em note (máx. 3 tentativas); block = depende do usuário; cancel = a etapa deixou de ser necessária.",
            buildJsonObject {
                put("task_id", str("Id da etapa (ex.: T2)"))
                put("verdict", str("approve | redo | block | cancel"))
                put("note", str("Motivo curto; em redo, descreva exatamente o que ajustar"))
            }, listOf("task_id", "verdict")))
        add(tool("section_done", "Seção autônoma: declara que o objetivo inteiro da seção foi entregue e encerra o ciclo com o relatório final. Chame somente quando não restar nenhuma fase a fazer; até lá, o app continua acionando você para seguir.",
            buildJsonObject { put("summary", str("Relatório final consolidado: entregáveis, arquivos, números")) }, listOf("summary")))
        add(tool("team_reassign",
            "Passa uma etapa do plano para outro agente do time e a reinicia do zero com ele. Se o agente atual estiver trabalhando nela, ele é parado. Use quando o agente travou, não tem a capacidade necessária (ex.: sem browser) ou outro faria melhor. Etapas já aprovadas não podem ser reatribuídas.",
            buildJsonObject { put("task_id", str("Id da etapa (ex.: T2)")); put("agent", str("Nome do agente que assume a etapa")); put("note", str("Opcional: orientação para o novo responsável (o que mudar em relação à tentativa anterior)")) },
            listOf("task_id", "agent")))
        add(tool("team_status", "Mostra o estado de cada Agent do time (trabalhando, aguardando o usuário, concluído) e a última resposta de cada um.", buildJsonObject {}))
        add(tool("team_message",
            "Envia agora uma orientação ou correção a um agente do time; ele a recebe no próximo passo, sem interromper o trabalho. Use quando um checkpoint mostrar desvio, travamento ou falta de insumo.",
            buildJsonObject { put("agent", str("Nome do agente")); put("message", str("Orientação objetiva: o que fazer ou parar de fazer e qual é o critério de pronto")) },
            listOf("agent", "message")))
        add(tool("team_stop",
            "Para um agente do time imediatamente — quando a tarefa já foi cumprida ou há desvio, loop ou insumo ausente — e devolve o que ele entregou até ali. Se for o caso, redirecione depois com team_delegate.",
            buildJsonObject { put("agent", str("Nome do agente")); put("reason", str("Motivo curto")) },
            listOf("agent", "reason")))
    }
    if (!connectorEditId.isNullOrBlank()) {
        add(tool("connector_edit_propose", "Cria um card de atualização para o conector já associado a esta conversa; nada é aplicado até o usuário confirmar no card. settings_json é um objeto JSON com os IDs listados; definition_yaml é opcional e serve apenas para alterações funcionais de conector customizado. Não inclua segredos.", buildJsonObject {
            put("summary", str("Resumo curto do que será alterado"))
            put("settings_json", str("Objeto JSON em texto com os IDs dos settings e os novos valores; use {} se nenhum setting mudar"))
            put("enabled", bool("Novo estado habilitado, se solicitado"))
            put("agent_required", bool("Novo estado de Agent required?, se solicitado"))
            put("definition_yaml", str("YAML completo e atualizado da definição customizada, somente quando a funcionalidade mudar; mantenha os tokens __FLUWY_LOCK_...__ exatamente como estão. Omita se a definição não mudar"))
        }, listOf("summary", "settings_json")))
        if (agent?.useBrowser != false) {
            add(tool("browser_navigate", "Abre uma página pública ou uma busca, para consulta. Neste chat não abra páginas que exijam login nem URLs que disparem mudanças.", buildJsonObject { put("url", str("URL pública ou termos de busca")) }, listOf("url")))
            add(tool("browser_new_tab", "Cria uma aba isolada no browser desta conversa e, opcionalmente, abre nela uma URL pública.", buildJsonObject { put("url", str("URL pública opcional")) }))
            add(tool("browser_select_tab", "Seleciona uma aba pelo id devolvido por browser_read ou browser_new_tab.", buildJsonObject { put("tab_id", str("Id da aba nesta conversa")) }, listOf("tab_id")))
            add(tool("browser_close_tab", "Fecha uma aba desta conversa. A última aba aberta não pode ser fechada.", buildJsonObject { put("tab_id", str("Id da aba nesta conversa")) }, listOf("tab_id")))
            add(tool("browser_snapshot", "Lê a página atual (headings, texto, links e controles) sem interagir com ela.", buildJsonObject { put("max_chars", int("Limite de caracteres (padrão 9000)")) }))
            add(tool("browser_read", "Lê o texto e os elementos da página atual, sem clicar nem preencher campos.", buildJsonObject {}))
            add(tool("browser_scroll", "Rola a página atual, sem acionar controles.", buildJsonObject { put("direction", str("up ou down")) }))
            add(browserWaitForTool())
            add(tool("browser_back", "Volta para a página anterior da aba ativa (como o botão voltar). Use depois de seguir um link errado; confira o resultado com browser_read.", buildJsonObject {}))
        }
        return@buildJsonArray
    }
    if (allowMultiAgent) add(tool("multi_agents", "Divide o trabalho em subtarefas executadas por workers em paralelo (no máximo cinco ao mesmo tempo). Cada worker parte de um snapshot do projeto e trabalha numa cópia isolada. Ao terminar, as alterações de cada um são aplicadas ao projeto principal: arquivos novos e arquivos que só ele mudou entram direto; se dois alterarem o mesmo arquivo, o principal mantém a primeira versão e a outra fica em <arquivo>.worker-N para você mesclar. Para evitar conflitos, dê a cada worker arquivos ou pastas diferentes. tasks_json aceita strings independentes ou objetos {id, task, depends_on}; subtarefas com dependências rodam em ondas. budget_tokens vale por worker.", buildJsonObject {
        put("tasks_json", str("Array JSON de strings independentes ou de objetos {id, task, depends_on:[ids]}. Dependências rodam em ondas; ciclos são rejeitados"))
        put("repo_path", str("Caminho opcional do repositório Git dentro do sandbox (padrão: workspace atual)"))
        put("max_workers", int("Quantidade de workers (1-5; padrão 3)"))
        put("budget_tokens", int("Orçamento de tokens por worker (2000-12000; padrão 6000)"))
    }, listOf("tasks_json")))
    if (allowMultiAgent) add(tool("build_team", "Exército de agentes para DESENVOLVER SOFTWARE (site, landing page, app web, front-end, back-end, API, sistema). " +
        "Use SEMPRE que o usuário pedir para construir, desenvolver ou programar software, no lugar de fazer tudo sozinho: dezenas de agentes com nome e papel " +
        "(Layout, Copy, Design, Frontend, Backend, API, Banco de dados, QA, SEO, Visuals…) trabalham AO MESMO TEMPO no mesmo projeto (/root/workspace compartilhado), até 6 de cada vez. " +
        "No front-end o usuário assiste aos agentes trabalhando ao vivo no browser (cursor e caixa de cada um sobre o elemento que edita). " +
        "Fluxo obrigatório no front-end: em preview passe a PASTA do projeto (pode estar vazia). O app ANTES cria o projeto React + Vite em branco se não houver um, instala as dependências, sobe o servidor de desenvolvimento e confere que o app abriu e desenhou no browser; só então o time entra. Se o app não ficar de pé, nenhum agente começa e você recebe o log para corrigir. " +
        "API Node (Express, Fastify…): crie package.json com a dependência e um server.js que escuta process.env.PORT, e passe a pasta em preview: o app instala, sobe e confere que responde antes do time entrar. " +
        "Cada edição do time aparece na hora (HMR), com o elemento sendo construído e o cursor do agente sobre ele. Se o preview quebrar, o app corrige sozinho: instala pacote que faltou ou chama o agente Doc para consertar. " +
        "Projeto novo: divida em 8 a 40 tarefas pequenas, uma por agente, cada uma num elemento ou arquivo diferente; use depends_on só quando uma parte precisa da outra. " +
        "NÃO crie tarefa de revisão geral (\"QA/revisar o app inteiro\"): o app já confere compilação, imports, export default e âncoras no fim e chama o agente Doc para cada falha; revisão, se precisar, é por seção e objetiva. " +
        "EDIÇÕES DEPOIS: projeto já feito pelo time = toda mudança seguinte também vai pelo time (o app recusa edição direta do orquestrador). Para ajustes, 1 a 3 tarefas pequenas com file do componente existente " +
        "(o app não recria a base nem duplica seções) e o mesmo preview; leia antes a seção \"## Entregas\" de .fluwy/TIME.md para dividir certo. Não reduza budget_tokens (o app garante no mínimo 800 mil por agente e amplia o teto quando a tarefa precisa). " +
        "No fim o app ainda roda o build de produção e chama o Doc se falhar. " +
        "Bloqueia até o time terminar e devolve o relatório; depois teste, corrija o que faltar e mostre o resultado ao usuário.", buildJsonObject {
        put("goal", str("Objetivo do time em uma frase"))
        put("tasks_json", str("Array JSON de objetos {id, role, task, file, target, depends_on:[ids]}. file (front-end): componente da tarefa, ex.: \"src/components/Hero.jsx\" — o app cria o esqueleto e liga no App ANTES do time, na ordem das tarefas. target: seletor CSS do elemento, ex.: \"#hero\""))
        put("preview", str("Pasta do projeto (ex.: /root/workspace/app): front-end React/Vite/Next ou API Node; o app instala, sobe e confere antes do time entrar. Ou a URL de um servidor já rodando (http://127.0.0.1:PORTA)"))
        put("contract", str("Contrato compartilhado por TODOS os agentes: nome da marca, paleta (cores Tailwind/hex), fontes, tom de voz, dados reais (endereço, telefone, preços), ids/âncoras e rotas. Evita que cada agente invente nomes e links diferentes"))
        put("max_parallel", int("Agentes trabalhando ao mesmo tempo (1-8; padrão 6)"))
        put("budget_tokens", int("Orçamento de tokens por agente, contando o prompt de cada chamada (padrão 1500000; mínimo aplicado 800000; o app amplia se precisar)"))
    }, listOf("goal", "tasks_json")))
    if (allowMultiAgent) add(tool("workflow_manage", "Fluxos (automação estilo n8n; página Fluxos na barra lateral). Use SÓ quando o usuário pedir claramente para criar/alterar um fluxo ou automação recorrente. " +
        "Roteiro obrigatório: 1) descubra os conectores ativos (conectores_listar; ações e campos com conector_acoes); 2) action=create (salva RASCUNHO e valida); " +
        "3) corrija com update até a validação passar; 4) action=test (executa de verdade e devolve cada passo); 5) se falhar, update + test de novo; " +
        "6) só com teste ok: action=publish (ativa o agendamento). Nunca diga que publicou sem publish OK. Avise o usuário que o teste executa ações reais (ex.: envia o e-mail). " +
        "Actions: list | get | create | update | validate | test | status | publish | run | disable | delete. " +
        "workflow_json = {name, nodes:[{id, type, label, config}], edges:[{from, to, port}]} (ids curtos como t1, h1, a1; layout é automático). Tipos e config (valores em texto): " +
        "trigger_manual; trigger_schedule (mode=interval|daily|weekly, minutes, time HH:mm, weekday 1-7 — ou when=\"frase do usuário\", ex. \"a cada 2 minutos\", \"todo dia às 8h\", \"toda segunda 18:30\"; roda com o app aberto, em segundo plano ou fechado). " +
        "WHATSAPP (sem conector: é o API Hub): gatilho trigger_whatsapp (chat=nome/número/id da conversa ou grupo, scope=all|private|groups, keyword=palavras|separadas, from_me=true só se o usuário conversa consigo mesmo/no próprio número) — dispara NA HORA a cada mensagem, dados em {{t1.payload.text}}, .chat, .chat_name, .sender_name, .id; " +
        "FLUXO LIGADO A UMA CONVERSA OU AGENTE: rode action=list (traz os títulos das conversas e os agentes) e pergunte com ask_question qual usar, com os nomes reais como opções — nunca invente. Dúvidas que mudam o fluxo (qual contato/grupo, qual conversa, responder sozinho ou só rascunho, base de conhecimento) também vão por ask_question ANTES de criar. " +
        "Fluxo com trigger_whatsapp/trigger_webhook fica ATIVO assim que salvo e válido (escuta em tempo real); não diga ao usuário para executar manualmente. " +
        "NUNCA use trigger_schedule + agent lendo whatsapp_messages a cada minuto para \"monitorar\" WhatsApp (gasta IA a cada minuto e perde mensagens). " +
        "webapi (api=slug do hub ex. whatsapp_send_text, args=JSON com {{…}}) — chama qualquer Web API do hub; ações externas rodam de verdade só publicado (no teste são validadas sem enviar). " +
        "atendente (persona, knowledge=dados de treinamento/base de conhecimento, objective=atendimento|vendas|recuperar_pagamento|recuperar_cliente|suporte, history=nº de mensagens anteriores, reply=auto|draft, session=título de uma conversa do app para acordar e repassar, handoff=palavras que pedem humano) — " +
        "ATENDENTE DE WHATSAPP pronto: lê a mensagem do gatilho, o histórico da conversa e a base de conhecimento, responde com IA (ou repassa à sessão e devolve a resposta dela) e envia no próprio chat; saída {reply, sent, chat, handoff}. " +
        "Ex. atendimento de plano de saúde: trigger_whatsapp(chat=Cliente X ou vazio p/ todos) → atendente(objective=vendas, knowledge=tabela de planos/regras). Ex. conversar com uma sessão pelo WhatsApp: trigger_whatsapp(chat=meu número, from_me=true) → atendente(session=Nome da conversa). " +
        "E-MAIL DE DESTINO: nunca invente nem use exemplo (x@y.com, email@exemplo.com). Se o fluxo envia e-mail e o usuário não disse o destino, pergunte com ask_question (opções: o e-mail da conta conectada, se souber, + allow_other) ou ask_secure se ele preferir não expor; coloque em campos.receiver e envie pelo conector de e-mail dele (gmail send_email). O fluxo só é aceito/publicado com destinatário real, assunto e corpo. FORMATO: corpo de e-mail em Markdown limpo (títulos, listas, tabelas, links) — o app converte para HTML de e-mail com estilos inline; notificação/página/chat usam Markdown estilo GitHub. TESTE e PUBLICAÇÃO: action=test lê de verdade (sites, buscas, e-mails, APIs GET) mas simula todo envio externo (e-mail, POST, webhook, ações de escrita) e valida o conteúdo; action=publish roda esse teste ponta a ponta e só ativa se passar. NOTÍCIAS/FEEDS: http GET num RSS/Atom já devolve {{id.items}} (title, link, date, source, image) e {{id.text}} (lista compacta, 1 por linha) — use {{id.text}} no nó de IA; nunca passe {{id.body}} (XML cru) nem baixe feed por shell. Um {{…}} que chega vazio faz o teste falhar com o nome do caminho; IA/agente que respondem \"não recebi os dados\" também falham. Nó agent: a última mensagem dele é a saída do nó; peça o resultado pronto, sem relato do processo. Ao errar o id de um fluxo, o erro lista os ids existentes. WEBHOOK: trigger_webhook {repo dono/repo, issue N, secret ≥12 caracteres} — quem chama posta um comentário na issue (1ª linha = segredo, resto = payload JSON), use {{trigger.payload.campo}}. NOTIFY open=chat abre uma conversa do fluxo com o conteúdo (Markdown GitHub: tabelas, links, listas). Portão: título/texto/corpo vazios, {{…}} sem valor ou JSON cru fazem o teste falhar e bloqueiam a publicação — corrija e teste de novo. QUANDO EXECUTAR: se o pedido é recorrente e o usuário NÃO disse quando, ANTES do create chame ask_question UMA vez com no máximo 2 perguntas diretas, opções de rádio e allow_other=true " +
        "(ex.: header \"Frequência\": \"1x por dia\", \"A cada hora\", \"Dia e horário fixos\", \"Só quando eu mandar\"; e, se fizer sentido, header \"Horário\": \"08:00\", \"12:00\", \"18:00\"). " +
        "A resposta (inclusive o texto livre de \"Outra resposta\", ex. \"a cada 2 minutos\") vai em when=. Se o usuário já disse a frequência, não pergunte. \"Só quando eu mandar\" = trigger_manual. " +
        "ai (prompt, maxTokens) — chamada direta ao modelo SEM ferramentas: use para resumir/formatar/classificar/extrair dados (rápido; prefira a agent); " +
        "agent (prompt, agent=nome existente ou vazio, timeout min) — agente completo com ferramentas/browser, só quando precisa agir/pesquisar; " +
        "ai_decide (criteria=pergunta de sim/não com os critérios do usuário, input={{id}}; ports true/false; saída {decision, reason, category}) — decisões inteligentes: importante? precisa de resposta? é comercial? é de fulano?; " +
        "review (title, item={{id}} do que mostrar, draft=rascunho ex. {{a1}}, mode=ask|auto, hours) — página com o item completo (e-mail com fotos e anexos) e o rascunho EDITÁVEL; aprovado → port true com {{id.draft}} (texto final editado), recusado → false. " +
        "Use review antes de qualquer ação externa sensível (responder e-mail, postar, enviar mensagem) quando o usuário NÃO der autonomia; com autonomia explícita (\"responda sozinho\", \"sem minha aprovação\") use mode=auto. Em test o review nunca envia (segue por false). " +
        "Filtros nos {{…}}: | email (endereço), | name, | text (legível), | oneline, | lower, | upper, | default:x. Ex. responder e-mail importante: trigger_schedule → connector g1 gmail gmail_search_email {\"max_results\":1} → if_new {{g1.data.results.messages.0.id}} → ai_decide → (true) ai a1 escreve a resposta com {{g1.data.results.messages.0 | text}} → review r1 item {{g1}} draft {{a1}} → (true) connector gmail send_email receiver [\"{{g1.data.results.messages.0.from | email}}\"] subject \"Re: {{g1.data.results.messages.0.subject}}\" body_text {{r1.draft}} → notify. " +
        "if_new (key=valor que identifica o item ex. {{g1.data.results.messages.0.id}}, first=skip|new; ports true=novo/false=já visto; lembra entre execuções) — para \"só se for novo\"; " +
        "connector (source=hub|mcp|app, conector=<id do app ex. gmail | id do servidor MCP | id do conector do app>, acao=<nome técnico>, campos=objeto JSON com os campos da ação, conexao=<conta, opcional>) — saída {status, connector, action, summary, data, text}; " +
        "http (method, url, headers, body, onError=stop|continue) — saída {status, body, data (se JSON), text (texto limpo se HTML)}; shell (command); set (values: linhas nome = valor); " +
        "if (left, op=contains|not_contains|equals|not_equals|gt|lt|empty|not_empty|regex, right; edges com port true/false); delay (seconds); approval (question; port true/false); notify (title, text curto, detail=conteúdo completo, open=modal|page|flow|none). " +
        "NOTIFICAÇÕES: o usuário nunca vê JSON nem dados brutos. text = frase curta com o essencial (ex.: assunto do e-mail); detail = o conteúdo completo que aparece no quadro/página ao tocar. " +
        "Deixe detail vazio para mostrar o texto + os dados do nó anterior já formatados, ou use {{id}}/{{id.data…}} (dados viram texto claro automaticamente); para um formato próprio, passe por um nó ai antes. " +
        "NUNCA escreva \"abra o fluxo para ver\": a informação tem de estar no próprio quadro/página. " +
        "UI KIT do quadro/página (layout=auto, padrão): com detail vazio ou detail={{id}} o app monta sozinho cartões a partir dos dados (e-mails viram cartões com avatar, remetente, assunto, data, prévia e anexos; notícias com imagem e link; objetos viram campos; listas planas viram tabela) — é o recomendado. " +
        "Para um visual próprio, detail pode ser um modelo {\"blocks\":[…]} com blocos: header{title,subtitle,image}, text{text Markdown}, card{title,subtitle,meta,body,image,avatar,badge,url,chips[]}, list{title,items:[card]}, image{url,caption}, gallery{images[]}, kv{items:[{label,value}]}, " +
        "stats{items:[{label,value,delta}]}, table{columns[],rows[[]]}, chips{items[]}, quote{text,author}, alert{tone info|ok|warn|err,title,text}, button{label,url}, section{title,blocks[]}, progress{label,value}, divider; " +
        "{\"type\":\"auto\",\"data\":\"{{id}}\"} insere os cartões gerados dos dados. Um nó ai também pode devolver esse JSON e a notificação usa detail={{a1}}. layout=markdown = texto simples. " +
        "Dados entre nós: {{prev}} (saída anterior), {{<id>}}, {{<id>.campo.sub}} (JSON), {{trigger.time}}. Ex.: site → resumo → e-mail: trigger_schedule(interval 30) → http GET → ai prompt \"Resuma: {{h1.text}}\" → connector gmail send_email campos {\"receiver\":[\"x@y.com\"],\"subject\":\"Resumo\",\"body_text\":\"{{a1}}\"}. Ex.: e-mail novo → aviso: trigger_schedule(interval 30) → connector g1 gmail gmail_search_email campos {\"max_results\":1} → if_new key {{g1.data.results.messages.0.id}} → (port true) notify title \"Novo e-mail\", text {{g1.data.results.messages.0.subject}}, detail com remetente, assunto e corpo. Depois do test use os \"caminhos\" do relatório para acertar os {{id.campo}}; campo vazio = corrija e teste de novo.", buildJsonObject {
        put("action", str("list | get | create | update | validate | test | status | publish | run | disable | delete"))
        put("id", str("Id ou nome do fluxo"))
        put("workflow_json", str("JSON do fluxo para create/update"))
    }, listOf("action")))
    val term = agent?.useTerminal != false
    val web = agent?.useBrowser != false && allowMultiAgent
    // Pages: sempre numa seção (time compartilha quadro/relatórios) e quando o planner carrega o grupo "pages".
    val pagesEnabled = allowMultiAgent
    add(tool("update_plan", "Cria ou atualiza o plano da tarefa, dividido em fases. O usuário acompanha o progresso por ele.", buildJsonObject {
        put("goal", str("Objetivo em uma frase"))
        put("phases", strArray("Fases em ordem"))
        put("done_count", int("Quantas fases já estão concluídas"))
    }, listOf("goal", "phases")))
    if (term) {
        add(tool("shell", "Executa um comando bash no sandbox Linux (Alpine) e devolve stdout+stderr e o exit code. Sobre o exit code: numa sequência, ele é o do último comando; para que uma etapa com falha interrompa a sequência, encadeie com && em vez de ;. Em pipelines, use set -o pipefail quando a falha de qualquer etapa importar. Um cat/echo depois de um ; esconde a falha do comando anterior, então confira o exit code da operação principal. GUARDRAILS antes de executar: se o comando roda um script (bash/python/node com caminho), confirme com file_list/file_read que o arquivo existe, e antes de cd confirme que o diretório existe — nomes de script e caminhos precisam vir do que você viu, não de suposição. Se a execução anterior retornou ENOENT/No such file or directory, inspecione o workspace e corrija o caminho em vez de repetir o mesmo comando. Os comandos rodam SEM interação (stdin fechado; ninguém responde y/n, senha ou menu): passe sempre as opções por flag (npx --yes, apt-get -y, pip --no-input, npm create <pacote> <pasta> -- --template …, em pasta nova/vazia). Não esconda a saída de instalações com | tail/| head. Se o resultado trouxer um aviso [App] de pergunta interativa, corrija o comando como a dica indica em vez de repetir o mesmo.", buildJsonObject {
            put("command", str("Comando a executar"))
            put("timeout", int("Tempo limite em segundos (padrão 180)"))
            put("background", bool("true = roda em segundo plano (para servidores)"))
        }, listOf("command")))
    }
    add(tool("file_write", "Cria ou sobrescreve um arquivo de texto no workspace da tarefa, criando as pastas necessárias. Use caminho relativo ou /root/workspace/...; caminhos Android e outros absolutos são recusados.", buildJsonObject {
        put("path", str("Caminho relativo ao workspace (ou /root/workspace/arquivo); caminhos Android e outros absolutos não são aceitos")); put("content", str("Conteúdo do arquivo")); put("append", bool("true = acrescenta ao final em vez de sobrescrever"))
    }, listOf("path", "content")))
    add(tool("file_edit", "Substitui um trecho literal num arquivo existente do workspace da tarefa. old_text e new_text são texto cru; a ferramenta procura primeiro a correspondência exata e, como alternativa, aceita apenas diferenças de espaços em branco. A edição é recusada se o trecho não for encontrado, se houver mais de uma ocorrência (ambiguidade) ou se o arquivo passar de 8 MB. Copie old_text de uma leitura recente (file_read): depois de 2 falhas seguidas no mesmo arquivo, a ferramenta exige uma releitura antes de aceitar nova edição. Para acrescentar no fim do arquivo, use file_write com append=true. Por padrão allow_multiple=false; com true, todas as ocorrências são alteradas. A gravação é atômica.", buildJsonObject {
        put("path", str("Arquivo existente, relativo ao workspace ou em /root/workspace/...; caminhos Android e outros absolutos não são aceitos"))
        put("old_text", str("Trecho exato a localizar; use \\n para as quebras de linha no argumento JSON"))
        put("new_text", str("Texto de substituição; pode ser vazio para remover o trecho"))
        put("allow_multiple", bool("true apenas para substituir todas as ocorrências; padrão false"))
    }, listOf("path", "old_text", "new_text")))
    add(tool("complete_step", "Marca a próxima fase do plano como concluída. Chame ao terminar cada fase, antes de começar a seguinte.", buildJsonObject { put("note", str("O que foi feito")) }))
    add(tool("show_file", "Mostra ao usuário, direto no chat, um arquivo gerado (imagem, SVG, HTML, PDF ou código).", buildJsonObject {
        put("path", str("Caminho do arquivo")); put("caption", str("Legenda curta"))
    }, listOf("path")))
    add(tool("file_read", "Lê um arquivo de texto por partes. Aceita arquivos do workspace da tarefa (relativos ou /root/workspace/...) e, somente para leitura, de /root/connectors/<id>/workspace/.... Se o arquivo não existir, liste o diretório pai em vez de repetir o mesmo caminho.", buildJsonObject {
        put("path", str("Caminho do arquivo")); put("offset", int("Linha inicial (1 = início; padrão 1)")); put("limit", int("Quantidade de linhas (padrão 250, máx. 1000)")); put("stale_ok", bool("true apenas quando o usuário ou o coordenador pediu para reler um snapshot antigo do browser arquivado em .fluwy/out; por padrão snapshots antigos são recusados"))
    }, listOf("path")))
    if (pagesEnabled) {
        add(tool("page_create", "Cria A página Fluwy Pages desta conversa (aparece em Pages, mobile-first). Cada conversa tem UMA única página: se já existe, use page_update nela, nunca page_create. " +
            "A página é 100% sobre o pedido do usuário (ex.: sessão de marketing → página de marketing; minerador de ofertas → o minerador de ofertas). " +
            "kind: html (app/página feita para o pedido — o padrão para ferramentas e painéis), doc (relatório em markdown), table, checklist ou kanban. Só use kanban/checklist se o usuário pediu esse formato; nunca crie um kanban de tarefas por conta própria. " +
            "Páginas html podem conversar com o app pelo objeto window.Fluwy: Fluwy.getData() devolve o JSON salvo, Fluwy.saveData(jsonString) salva (o agente lê no page_read), Fluwy.ask(texto) manda uma mensagem a você. Use isso para formulários, listas e controles que o usuário preenche. Numa seção, todo o time vê e atualiza a mesma página.",
            buildJsonObject { put("title", str("Título")); put("kind", str("html | doc | table | checklist | kanban (kanban/checklist só se o usuário pediu)")); put("markdown", str("Conteúdo inicial (doc)")); put("html", str("HTML completo, mobile-first (html)"))
                put("columns", strArray("Colunas do kanban (padrão: A fazer, Fazendo, Revisão, Feito)")); put("headers", strArray("Cabeçalhos da tabela")) }, listOf("title", "kind")))
        add(tool("page_update", "Atualiza uma página com um lote de operações (tudo ou nada). Mude só o necessário em vez de reescrever. ops: " +
            "rename{title} · set_kind{kind} (mudar o formato desta mesma página, ex.: o usuário pediu um kanban) · set_markdown{markdown} · append_markdown{markdown} · replace_section{heading,markdown} · replace_text{find,replace} (vale para markdown e para HTML; copie o trecho do page_read) · set_html{html} · set_data{data} · add_column{name} · " +
            "add_card{title,column?,assignee?,note?} · move_card{card_id|title,column} · update_card{card_id,new_title?,note?,assignee?,column?} · remove_card{card_id} · " +
            "add_item{title} · check{card_id|title} · uncheck{card_id|title} · set_headers{headers[]} · add_row{cells[]} · update_row{index,cells[]} · remove_row{index}. " +
            "Exemplos: [{\"op\":\"move_card\",\"card_id\":\"c_ab12\",\"column\":\"Feito\"}] · [{\"op\":\"replace_section\",\"heading\":\"Preços\",\"markdown\":\"novo texto\"}] · " +
            "[{\"op\":\"replace_text\",\"find\":\"<h1>Título antigo</h1>\",\"replace\":\"<h1>Título novo</h1>\"}]. Antes de replace_text, copie o trecho do page_read.",
            buildJsonObject { put("page_id", str("ID da página (page_list)")); put("ops", buildJsonObject { put("type", "array"); put("description", "Lista de operações, cada uma um objeto com \"op\" e os campos dela"); put("items", buildJsonObject { put("type", "object") }) }) }, listOf("page_id", "ops")))
        add(tool("page_read", "Lê uma página em formato compacto (cartões com card_id, linhas numeradas).", buildJsonObject { put("page_id", str("ID da página")) }, listOf("page_id")))
        add(tool("page_list", "Lista as páginas (Pages) desta conversa ou seção, com id e título. Use antes de page_read ou page_update para achar a página certa.", buildJsonObject {}))
    }
    add(tool("file_search", "Procura um texto (ou regex) dentro dos arquivos do workspace e devolve caminho:linha: trecho. Use ANTES de ler arquivos grandes: ache a linha e leia só o trecho com file_read(offset).",
        buildJsonObject { put("pattern", str("Texto ou regex a procurar (sem diferenciar maiúsculas)")); put("path", str("Arquivo ou pasta (padrão: todo o workspace)")); put("regex", bool("true se pattern é regex")); put("max", int("Máximo de linhas (padrão 50)")) }, listOf("pattern")))
    add(tool("file_list", "Lista um diretório do workspace da tarefa ou, somente para leitura, de /root/connectors/<id>/workspace/.", buildJsonObject { put("path", str("Diretório relativo ao workspace, ou /root/workspace/, ou /root/connectors/<id>/workspace/")) }))
    if (web) {
        add(tool("browser_navigate", "Abre uma URL (ou termos de busca) no browser. Erros HTTP 4xx/5xx e falhas de rede na página principal são erros, não conteúdo válido. Para localhost, valide antes o processo, a porta e a saúde HTTP no mesmo ambiente de rede do WebView; CONNECTION_REFUSED pede correção do listener ou do endereço, não uma nova tentativa. PARTIAL significa que há texto mas o carregamento não foi confirmado — a aplicação pode não estar pronta, então verifique DOM, console e um sinal funcional. Use mode=desktop para sites que só funcionam no computador (ex.: web.whatsapp.com redireciona para /mobile/ no celular).",
            buildJsonObject { put("url", str("URL ou termos de busca")); put("mode", str("desktop ou mobile (opcional; vale para as próximas páginas desta conversa)")) }, listOf("url")))
        add(tool("browser_new_tab", "Cria uma aba no browser desta conversa e, opcionalmente, abre uma URL pública. O resultado informa o id da aba.", buildJsonObject { put("url", str("URL pública opcional")) }))
        add(tool("browser_select_tab", "Seleciona uma aba pelo id informado por browser_read ou browser_new_tab. As ferramentas seguintes passam a operar somente nessa aba.", buildJsonObject { put("tab_id", str("Id da aba")) }, listOf("tab_id")))
        add(tool("browser_close_tab", "Fecha a aba informada desta conversa. A última aba aberta não pode ser fechada.", buildJsonObject { put("tab_id", str("Id da aba")) }, listOf("tab_id")))
        add(tool("browser_snapshot", "Gera um snapshot semântico da página atual, com headings, conteúdo, links e controles nomeados. As refs e os índices deixam de valer depois de uma navegação.", buildJsonObject { put("max_chars", int("Limite de caracteres (padrão 9000)")) }))
        add(tool("browser_read", "Lê o snapshot e o texto da página atual, com os índices dos elementos para browser_click e browser_type.", buildJsonObject {}))
        add(tool("browser_click", "Clica no elemento [índice] listado por browser_read. Os índices deixam de valer quando o DOM muda, então leia a página imediatamente antes de clicar; se o elemento não existir mais, releia e use o índice novo em vez de repetir o antigo.", buildJsonObject { put("index", int("Índice do elemento")) }, listOf("index")))
        add(tool("browser_type", "Digita um texto no campo [índice] listado por browser_read; com submit=true, envia o formulário em seguida.", buildJsonObject {
            put("index", int("Índice do campo")); put("text", str("Texto a digitar")); put("submit", bool("true = envia o formulário depois de digitar"))
        }, listOf("index", "text")))
        add(tool("browser_scroll", "Rola a página, espera novos itens carregarem e informa quantas páginas ainda faltam.", buildJsonObject { put("direction", str("up ou down")) }))
        add(browserWaitForTool())
        add(tool("browser_back", "Volta para a página anterior da aba ativa (como o botão voltar). Use depois de seguir um link errado; confira o resultado com browser_read.", buildJsonObject {}))
        add(tool("browser_js", "Executa JavaScript na página atual e devolve o resultado da expressão.", buildJsonObject { put("code", str("Código JS (uma expressão)")) }, listOf("code")))
    }
    // Busca, leitura HTTP e Web APIs não usam o browser da sessão: valem também para workers e fluxos
    // (sem elas, os nós de fluxo tentavam raspar sites com curl e estouravam o limite de passos).
    if (agent?.useBrowser != false) {
        add(tool("web_search", "Busca na web (Google + Bing + DuckDuckGo ao mesmo tempo, em segundos, sem abrir o browser da sessão): devolve título, URL e trecho. Sempre use esta ferramenta para pesquisar — não abra buscadores com browser_navigate. Depois leia os relevantes com web_fetch. Use o browser só para sites com login, JavaScript pesado ou interação.",
            buildJsonObject { put("query", str("Termos da busca")); put("max", int("Quantidade de resultados (padrão 8)")) }, listOf("query")))
        add(tool("webapi_list", "Lista as Web APIs do hub (scrapers prontos sobre sites, criados pelo usuário) com argumentos. Prefira-as a navegar no browser quando houver uma que sirva.", buildJsonObject {}))
        add(tool("webapi_call", "Chama uma Web API do hub e recebe JSON estruturado (com paginação: passe page/next_page). Roda num WebView separado, com a sessão e o proxy do browser. " +
            "args = objeto JSON com EXATAMENTE os nomes de parâmetro da API (veja webapi_list ou o bloco 'Web APIs do hub' do prompt); obrigatórios marcados. Ações externas (enviar, apagar) exigem confirmed=true só quando o usuário pediu. Binários (imagem/áudio) voltam como arquivo em webapi_files/.",
            buildJsonObject { put("api", str("slug da API (veja webapi_list)")); put("args", str("Argumentos em JSON, ex.: {\"query\":\"notebook\",\"page\":1}")) }, listOf("api")))
        add(tool("web_fetch", "Lê uma página por HTTP e devolve só o texto principal (sem menus/rodapés), bem menor que um snapshot do browser. Se vier pouco texto (site com JS/login), use browser_navigate.",
            buildJsonObject { put("url", str("URL http(s) completa")); put("max_chars", int("Limite de caracteres (padrão 12000, máx. 40000)")) }, listOf("url")))
    }
    if (web) {
        add(tool("browser_network", "Mostra as requisições da página: método, URL, status, tempo, headers e os payloads fetch/XHR capturados (com limite de tamanho). Útil para descobrir de qual API um dado vem.", buildJsonObject { put("filter", str("Filtro opcional: texto contido na URL")) }))
        add(tool("browser_console", "Mostra as mensagens recentes do console JavaScript da página (erros, avisos e logs).", buildJsonObject {}))
    }
    add(tool("notes_write", "Escreve no NOTES.md da tarefa: a sua memória de trabalho, que volta ao seu contexto a cada passo. Registre achados, estado e próximos passos.", buildJsonObject {
        put("content", str("Texto da anotação")); put("append", bool("true = acrescenta; false = substitui tudo"))
    }, listOf("content")))
    if (allowMultiAgent) add(tool("memory_save", "Guarda um fato duradouro sobre o usuário ou um projeto para conversas futuras (preferências, decisões, contas, contexto). Um fato por chamada, até ${ContextManager.MEMORY_MAX_CHARS} caracteres. Fatos parecidos já salvos são fundidos automaticamente; não salve segredos nem detalhes da tarefa pontual.", buildJsonObject {
        put("fact", str("O fato, em uma frase autossuficiente. Pode citar notas relacionadas como [[Título]]")); put("scope", str("global (vale para todos os agentes) ou agent (só este)")); put("tags", strArray("Palavras-chave"))
        put("title", str("Título curto da nota (2 a 5 palavras), ex.: Tom · design")); put("kind", str("Categoria: ${MemoryGraph.KINDS.joinToString("/")}")); put("links", strArray("Notas relacionadas já existentes: título ou id (de memory_search)"))
    }, listOf("fact")))
    add(tool("notes_read", "Lê o NOTES.md desta tarefa (memória de trabalho escrita com notes_write). Use após uma compactação de contexto para recuperar achados, caminhos e próximos passos. Para fatos de longo prazo use memory_search.", buildJsonObject {}))
    add(tool("knowledge_search", "Busca nos documentos que o usuário cadastrou em Conhecimento (políticas, catálogos, manuais) e devolve os trechos mais relevantes, do mais ao menos relevante. Use quando a resposta depender desse material.", buildJsonObject {
        put("query", str("O que procurar, em poucas palavras (ex.: política de troca, preço do plano anual)"))
        put("limit", int("Quantos documentos devolver (1 a 8; padrão 4)"))
    }, listOf("query")))
    add(tool("memory_search", "Busca fatos na memória de longo prazo, do mais ao menos relevante. Cada linha traz id, título, categoria e notas ligadas (→ [[…]]). Use palavras-chave (nomes, projetos, temas), não frases longas.", buildJsonObject { put("query", str("O que procurar")) }, listOf("query")))
    add(tool("skill_load", "Carrega as instruções completas de uma skill da lista de skills disponíveis.", buildJsonObject { put("name", str("Nome da skill")) }, listOf("name")))
    if (builder) add(tool("connector_spec", "Devolve a especificação YAML completa do UI Connector SDK (campos, tipos, limites e guardrails), sem presets. Chame antes de escrever uma definição.", buildJsonObject {}))
    if (builder) add(tool("connector_validate", "Valida o parsing e a estrutura de uma definição YAML de UI Connector. Não executa o conector nem os guardrails estáticos do dry-run. Devolve OK ou erros acionáveis, com caminho e motivo.", buildJsonObject {
        put("yaml", str("Definição YAML completa do conector"))
    }, listOf("yaml")))
    if (builder) add(tool("connector_test", "Executa um dry-run offline da definição YAML e aplica os guardrails estáticos. Transporte HTTP/WebSocket, filesystem e browser são mocks, usados para testar schema, templates, renderização e políticas de saída. Nenhuma API ou página real é chamada, nenhuma ação é enviada e nenhum setup, shell ou código gerado é executado — por isso o resultado não comprova login, permissões nem disponibilidade real.", buildJsonObject {
        put("yaml", str("Definição YAML completa do conector"))
        put("settings_yaml", str("Opcional: mapa YAML com valores de exemplo, não secretos, para os settings"))
        put("args_yaml", str("Opcional: mapa YAML com argumentos de exemplo, não secretos, por ferramenta; sem ele, o teste usa valores sintéticos"))
    }, listOf("yaml")))
    if (builder) add(tool("connector_propose", "Mostra ao usuário o card 'Instalar conector'. Aceita apenas o mesmo YAML que passou no dry-run offline e tem atestado válido; o card revalida antes de salvar. O salvamento manual pelo editor não passa por esse controle, e o card não afirma que o serviço real foi conectado.", buildJsonObject {
        put("yaml", str("Definição YAML completa e válida do conector"))
    }, listOf("yaml")))
    if (allowMultiAgent) add(tool("sleep", "Modo contínuo: pausa agora e retoma sozinho depois. Escolha um único modo — minutes, seconds ou align_candle_seconds. Use seconds para esperas curtas e minutes para verificações recorrentes.", buildJsonObject {
        put("minutes", int("Minutos até a próxima verificação (1-1440)"))
        put("seconds", int("Segundos até a próxima verificação (1-86400), para esperas curtas e precisas"))
        put("align_candle_seconds", int("Tamanho do período, em segundos, para alinhar a próxima verificação à abertura dele (ex.: 300 = velas de 5 min)"))
        put("lead_seconds", int("Com align_candle_seconds: quantos segundos antes da abertura acordar (2-30; padrão 10)"))
        put("reason", str("O que fazer ao acordar"))
    }, listOf("reason")))
    if (allowMultiAgent && sectionHere != null && agent?.id != sectionHere.coordinatorAgentId) add(tool("team_peer_message",
        "Envia uma mensagem direta a outro agente do time, ou ao coordenador, sem criar uma nova tarefa: avisar que um arquivo ficou pronto, tirar uma dúvida sobre a entrega dele, combinar quem mexe em qual arquivo. O destinatário lê no próximo passo, se estiver trabalhando. Não substitui a sua entrega ao coordenador.",
        buildJsonObject { put("to", str("Nome do agente do time, ou \"coordenador\"")); put("message", str("Mensagem objetiva e autossuficiente (cite caminhos de arquivo exatos)")) }, listOf("to", "message")))
    if (allowMultiAgent) add(tool("skill_list", "Lista as skills da biblioteca do usuário (nome, descrição, se está ativa, se é embutida e quantas versões anteriores tem).", buildJsonObject {}))
    if (allowMultiAgent) add(tool("skill_revise",
        "Revisa uma skill existente da biblioteca: substitui as instruções (e, se informada, a descrição) e guarda a versão anterior no histórico. Use quando uma skill se mostrou incompleta ou errada na prática. Skills embutidas do app não podem ser revisadas; crie uma nova com create_skill.",
        buildJsonObject { put("name", str("Nome exato da skill")); put("instructions", str("Novas instruções completas, passo a passo")); put("description", str("Opcional: nova descrição curta (quando usar)")) }, listOf("name", "instructions")))
    if (allowMultiAgent) add(tool("skill_install",
        "Instala uma skill a partir de um link (como o OpenBot): repositório/pasta/arquivo do GitHub (fixa o commit e baixa a pasta inteira: SKILL.md + scripts/templates) " +
            "ou URL direta de um SKILL.md (baixa também os arquivos que ele cita). Valida o frontmatter, salva na biblioteca e copia os arquivos para /root/workspace/skills/<nome>/. " +
            "Devolve o SKILL.md: depois, siga as instruções dele para o que o usuário pediu (configurar, rodar, montar o dashboard…).",
        buildJsonObject { put("source", str("Link do GitHub (owner/repo, …/tree/<branch>/<pasta>, …/blob/…/SKILL.md) ou URL https do SKILL.md")); put("name", str("Nome local pedido pelo usuário (opcional)")) },
        listOf("source")))
    if (allowMultiAgent) add(tool("create_skill", "Cria uma skill nova na biblioteca do usuário, reutilizável em outras tarefas.", buildJsonObject {
        put("name", str("Nome da skill")); put("description", str("Descrição curta: quando usar")); put("instructions", str("Instruções detalhadas, passo a passo"))
    }, listOf("name", "description", "instructions")))
    if (allowMultiAgent) {
        add(tool("trigger_create", "Cria um GATILHO que acorda ESTA conversa quando algo novo acontecer (\"quando chegar e-mail…\", \"sempre que mudar o site…\"). Fonte 'connector': uma ação de LEITURA de um conector do hub (ex.: buscar e-mails recentes, listar mensagens), checada periodicamente; só itens novos acordam você. Fonte 'site': mudança no texto de uma página. Teste a ação com conector_executar antes.",
            buildJsonObject {
                put("nome", str("Nome curto do gatilho"))
                put("fonte", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("connector")); add(kotlinx.serialization.json.JsonPrimitive("site")) }) })
                put("conector", str("Conector (fonte connector), ex.: gmail")); put("acao", str("Ação de leitura do conector"))
                put("campos", buildJsonObject { put("type", "object"); put("description", "Campos da ação de leitura (ex.: filtro, limite 10)") })
                put("conexao", str("Id da conexão (opcional se houver só uma)")); put("url", str("Página a vigiar (fonte site)"))
                put("filtro", str("Palavras que o evento precisa conter, separadas por vírgula (opcional)"))
                put("instrucao", str("O que fazer quando disparar (ex.: classificar, resumir, preparar resposta e pedir aprovação)"))
                put("intervalo_min", int("De quanto em quanto tempo verificar (mínimo 15)"))
            }, listOf("nome", "fonte", "instrucao")))
        add(tool("trigger_list", "Lista os gatilhos desta conversa (estado, última verificação, eventos).", buildJsonObject {}))
        add(tool("trigger_delete", "Remove um gatilho pelo id (veja os ids com trigger_list). A remoção é imediata e o gatilho para de disparar.", buildJsonObject { put("id", str("Id do gatilho")) }, listOf("id")))
    }
    if (allowMultiAgent) add(tool("cron_add",
        "Agenda uma tarefa para rodar sozinha num horário exato, em uma conversa nova e isolada (não mexe nesta). Use quando o usuário pedir algo com hora marcada: \"todo dia às 8h\", \"amanhã às 15:30\", \"toda segunda\". Para repetir por intervalo dentro desta mesma conversa, use set_schedule.",
        buildJsonObject {
            put("title", str("Nome curto da tarefa"))
            put("prompt", str("Instrução completa e autossuficiente que será executada a cada disparo (a conversa nova não vê este chat)"))
            put("at", str("Primeiro disparo, no horário local do aparelho: \"HH:mm\" (próxima ocorrência) ou \"yyyy-MM-dd HH:mm\""))
            put("repeat", str("once | half_hour | hourly | daily | weekly (padrão: daily)"))
        }, listOf("title", "prompt", "at")))
    if (allowMultiAgent) add(tool("cron_list", "Lista as tarefas agendadas por horário (id, título, repetição, próximo/último disparo, ligada ou não).", buildJsonObject {}))
    if (allowMultiAgent) add(tool("cron_remove", "Remove uma tarefa agendada, pelo id devolvido por cron_list ou cron_add.", buildJsonObject { put("id", str("Id da tarefa agendada")) }, listOf("id")))
    if (allowMultiAgent) add(tool("set_schedule", "Faz este agente trabalhar de forma autônoma e recorrente: o Android o acorda no intervalo definido, mesmo com o app fechado. Chame quando o usuário pedir para monitorar, acompanhar ou repetir algo ao longo do tempo. minutes é o intervalo entre os ciclos (ex.: 20; 1440 = uma vez por dia); minutes=0 desliga a autonomia.", buildJsonObject {
        put("minutes", int("Minutos entre os ciclos; 0 para parar")); put("mission", str("O que verificar ou fazer a cada ciclo"))
    }, listOf("minutes")))
    // Wallet tools are exposed only to the selected real agent and only for
    // explicitly persisted read grants. No secrets or signing primitives enter LLM context.
    if (allowMultiAgent && agent != null) {
        val walletPolicies = WalletAgentPolicyStore(ctx.applicationContext)
        if (walletPolicies.canAgentReadAddress(agent.id)) {
            add(tool("wallet_get_address", "Consulta o endereço público da carteira Solana local. Não devolve chave privada nem frase de recuperação.", buildJsonObject {}))
        }
        if (walletPolicies.canAgentReadBalance(agent.id)) {
            add(tool("wallet_get_balance", "Consulta o saldo em SOL na rede selecionada na tela Carteira. Somente leitura.", buildJsonObject {}))
        }
        if (walletPolicies.canAgentRequestTransfer(agent.id)) {
            add(tool(
                "wallet_request_transfer",
                "Cria um pedido de transferência de SOL para aprovação humana. A ferramenta não assina nem transmite: a carteira valida a política local e exige aprovação explícita do usuário.",
                buildJsonObject {
                    put("recipient", str("Endereço público Solana do destinatário"))
                    put("amount_sol", str("Valor decimal em SOL, até 9 casas decimais"))
                },
                listOf("recipient", "amount_sol")
            ))
        }
        if (walletPolicies.canAgentDayTrade(agent.id)) {
            add(tool("market_price", "Consulta o preço em USD, real e ao vivo, de um ou mais tokens Solana pelo mint. Somente leitura.", buildJsonObject {
                put("mints", strArray("Lista de endereços de mint (ex.: SOL=So111...112, USDC=EPjF...Dt1v)"))
            }, listOf("mints")))
            add(tool("market_candles", "Histórico OHLCV real (velas) do token no pool de maior liquidez na Solana (GeckoTerminal). Use para analisar a tendência antes de operar.", buildJsonObject {
                put("mint", str("Endereço de mint do token"))
                put("timeframe", str("day, hour ou minute (padrão hour)"))
                put("aggregate", int("Agrupamento de unidades base, ex.: hour com 4 = velas de 4h (padrão 1)"))
                put("limit", int("Quantidade de velas (1-1000; padrão 60)"))
            }, listOf("mint")))
            add(tool("market_liquidity", "Mede a profundidade real de um par: cota tamanhos crescentes no agregador Jupiter e mostra o impacto de preço de cada um. Como os AMMs da Solana não têm order book central, esta é a medida fiel de liquidez.", buildJsonObject {
                put("input_mint", str("Mint de entrada")); put("output_mint", str("Mint de saída"))
                put("probe_amounts_raw", strArray("Tamanhos de entrada em unidades mínimas (lamports/unidades do token), ex.: [\"100000000\",\"1000000000\"]"))
            }, listOf("input_mint", "output_mint", "probe_amounts_raw")))
            add(tool("market_discover", "Descobre tokens de todo o ecossistema Solana (dados reais do GeckoTerminal, sem API key). sort=new lista os recém-lançados, do mais novo ao mais antigo; sort=trending lista os que estão em alta. Traz preço, liquidez (reserva) e volume de 24h, para encontrar oportunidades e conferir se há liquidez suficiente para operar.", buildJsonObject {
                put("sort", str("new (do mais novo ao mais antigo) ou trending (em alta). Padrão: new"))
                put("limit", int("Quantidade de tokens (1-100; padrão 30)"))
            }))
            add(tool("market_token_info", "Informações reais de um token pelo mint: preço em USD, liquidez total, volume de 24h e market cap. Use para conferir se dá para comprar e vender (liquidez) antes de operar.", buildJsonObject {
                put("mint", str("Endereço de mint do token"))
            }, listOf("mint")))
            add(tool("wallet_list_tokens", "Lista os tokens SPL que a carteira realmente possui (mint, saldo e decimais). Somente leitura.", buildJsonObject {}))
            add(tool("wallet_swap", "Executa uma troca real de token por token via Jupiter, assinada localmente (a chave não entra no seu contexto). Funciona apenas na Mainnet. Entradas em SOL respeitam o limite por operação autorizado pelo usuário. Relate 'enviado' somente com a assinatura devolvida em mãos.", buildJsonObject {
                put("input_mint", str("Mint de entrada (o que você vende)"))
                put("output_mint", str("Mint de saída (o que você compra)"))
                put("amount_raw", str("Valor de entrada em unidades mínimas (lamports para SOL)"))
                put("slippage_bps", int("Slippage em basis points (1-5000; padrão 100 = 1%)"))
            }, listOf("input_mint", "output_mint", "amount_raw")))
            add(tool("trade_set_rule", "Arma uma regra de day trade (stop-loss, take-profit ou ordem limite): quando o preço em USD do token observado cruzar o alvo, a troca é executada automaticamente, dentro dos limites autorizados. Os preços usados são reais.", buildJsonObject {
                put("label", str("Rótulo curto da estratégia"))
                put("input_mint", str("Mint de entrada (o que será vendido ao disparar)"))
                put("output_mint", str("Mint de saída (o que será comprado ao disparar)"))
                put("amount_raw", str("Valor de entrada em unidades mínimas"))
                put("watch_mint", str("Mint cujo preço USD é observado (normalmente o token da posição)"))
                put("trigger", str("price_below (dispara quando o preço cai até o alvo) ou price_above (quando sobe até o alvo)"))
                put("target_price_usd", str("Preço-alvo em USD (decimal)"))
                put("slippage_bps", int("Slippage em bps (padrão 100)"))
            }, listOf("label", "input_mint", "output_mint", "amount_raw", "watch_mint", "trigger", "target_price_usd")))
            add(tool("trade_list", "Lista as regras de day trade deste agente com o estado de cada uma (armada, executada, falhou, cancelada) e o último preço observado. A chamada também avalia agora as regras armadas contra o preço real e dispara as que atingiram o alvo.", buildJsonObject {
                put("evaluate", bool("true (padrão) = confere os preços agora e dispara os gatilhos atingidos; false = apenas lista"))
            }))
            add(tool("trade_cancel", "Cancela uma regra de day trade armada deste agente.", buildJsonObject {
                put("rule_id", str("ID da regra"))
            }, listOf("rule_id")))
            add(tool("trade_update_rule", "Ajusta uma regra de day trade armada deste agente. É a ferramenta do trailing stop: quando o preço sobe, mova o stop-loss para cima e trave o lucro. Ex.: entrada a 0,55 e preço em 0,58 → mova o stop-loss para 0,56; se o preço cair, a posição sai no lucro. Muda apenas o preço-alvo (e, se necessário, o gatilho); a regra segue monitorada e executa sozinha.", buildJsonObject {
                put("rule_id", str("ID da regra armada a ajustar"))
                put("target_price_usd", str("Novo preço-alvo em USD (decimal)"))
                put("trigger", str("Opcional: price_below ou price_above, se for preciso mudar o sentido do gatilho"))
            }, listOf("rule_id", "target_price_usd")))
        }
    }
    // Cada ferramenta exige opt-in do agente; leituras/recursos protegidos também exigem grant Android.
    val caps = if (allowMultiAgent) agent?.deviceCaps.orEmpty() else emptyList()
    val grantedCaps = caps.filter { dev.fluwy.app.device.DeviceCaps.granted(ctx, it) }
    val deviceTools = dev.fluwy.app.device.DeviceCapPolicy.availableTools(caps, grantedCaps)
    if ("device_notifications" in deviceTools)
        add(tool("device_notifications", "Lê as notificações recentes do aparelho que o usuário autorizou.", buildJsonObject {
            put("filter", str("Filtro opcional por app ou texto")); put("limit", int("Máximo de notificações (padrão 30)"))
        }))
    if ("device_notify" in deviceTools)
        add(tool("device_notify", "Mostra uma notificação na barra de status do aparelho.", buildJsonObject {
            put("title", str("Título")); put("text", str("Mensagem"))
        }, listOf("text")))
    if ("device_screenshot" in deviceTools)
        add(tool("device_screenshot", "Captura a tela inteira. Use somente quando o usuário pedir explicitamente um screenshot ou print. Para ler a imagem de um post, a captura ampla expõe mais do que o necessário: leia primeiro a árvore de acessibilidade e, se ainda precisar, use device_accessibility_inspect_image no recorte do post.", buildJsonObject {}))
    if ("device_overlay" in deviceTools)
        add(tool("device_overlay", "Mostra um balão flutuante por cima dos outros apps.", buildJsonObject { put("text", str("Texto do balão")) }, listOf("text")))
    if ("device_bluetooth" in deviceTools)
        add(tool("device_bluetooth", "Lista apenas os dispositivos Bluetooth já pareados. Exige a permissão de dispositivos próximos e não inicia varredura.", buildJsonObject {}))
    if ("device_bluetooth_pair" in deviceTools)
        add(tool("device_bluetooth_pair", "Abre as Configurações de Bluetooth para o usuário ativar ou parear manualmente. Não lê dispositivos e não exige BLUETOOTH_CONNECT.", buildJsonObject {}))
    if ("device_calendar_read" in deviceTools)
        add(tool("device_calendar_read", "Lê os eventos dos próximos dias, sem salvá-los no app.", buildJsonObject { put("days", int("Horizonte em dias (1–30; padrão 7)")) }))
    if ("device_calendar_create" in deviceTools)
        add(tool("device_calendar_create", "Abre um rascunho de evento no editor de calendário do Android, sem ler a agenda; o usuário revisa e salva.", buildJsonObject {
            put("title", str("Título do evento")); put("start_epoch_ms", str("Início como epoch em milissegundos")); put("end_epoch_ms", str("Fim como epoch em milissegundos"))
            put("description", str("Descrição opcional")); put("location", str("Local opcional"))
        }, listOf("title", "start_epoch_ms", "end_epoch_ms")))
    if ("device_contacts" in deviceTools)
        add(tool("device_contacts", "Procura contatos por nome ou número, a pedido do usuário. Devolve apenas nome e telefone, no máximo 10 resultados. Requer READ_CONTACTS e não exporta a agenda.", buildJsonObject { put("query", str("Nome ou número específico a procurar")) }, listOf("query")))
    if ("device_call_log" in deviceTools)
        add(tool("device_call_log", "Lê o histórico recente de chamadas, quando o Android e a política do dispositivo permitem.", buildJsonObject { put("limit", int("Máximo de registros (1–50; padrão 20)")) }))
    if ("device_location" in deviceTools)
        add(tool("device_location", "Devolve apenas a última localização conhecida; não inicia rastreamento contínuo.", buildJsonObject {}))
    if ("device_dial" in deviceTools)
        add(tool("device_dial", "Abre o discador do Android com o número preenchido; o usuário revisa e toca em Ligar. A chamada não é iniciada pela ferramenta.", buildJsonObject { put("phone", str("Número a discar")) }, listOf("phone")))
    if ("device_microphone" in deviceTools)
        add(tool("device_microphone", "Abre o reconhecimento de voz do Android, com a interface visível para o usuário. O áudio não é salvo pelo app.", buildJsonObject {}))
    if ("device_accessibility_open_app" in deviceTools)
        add(tool("device_accessibility_open_app", "Abre um aplicativo instalado, pelo nome exato ou pelo pacote. Use somente quando a tarefa do usuário pedir uma ação nesse app.", buildJsonObject {
            put("app", str("Nome exato do app ou pacote Android"))
        }, listOf("app")))
    if ("device_accessibility_read_screen" in deviceTools)
        add(tool("device_accessibility_read_screen", "Lê a árvore de texto e controles visíveis da janela atual, sem capturar screenshot. Senhas vêm ocultas. Os IDs são temporários: deixam de valer quando a tela muda.", buildJsonObject {}))
    if ("device_accessibility_inspect_image" in deviceTools)
        add(tool("device_accessibility_inspect_image", "Alternativa visual de uso único, para quando o usuário pediu para ler ou analisar um post de app social e a leitura acessível não revelou o conteúdo da imagem. Captura e envia ao modelo apenas o recorte do elemento atual, depois de revalidar snapshot e janela; o screenshot não é salvo no histórico. Requer Android 11+ e um modelo com visão.", buildJsonObject {
            put("snapshot_id", str("ID devolvido pela leitura de tela atual"))
            put("element_id", int("ID do elemento do post/imagem na leitura atual"))
        }, listOf("snapshot_id", "element_id")))
    if ("device_accessibility_click" in deviceTools)
        add(tool("device_accessibility_click", "Toca num controle visível do snapshot atual. Releia a tela depois da ação. Se vier erro de snapshot ausente ou invalidado, chame device_accessibility_read_screen e use somente os IDs novos, em vez de repetir o mesmo toque.", buildJsonObject {
            put("snapshot_id", str("ID devolvido por device_accessibility_read_screen"))
            put("element_id", int("ID do elemento no snapshot atual"))
        }, listOf("snapshot_id", "element_id")))
    if ("device_accessibility_type" in deviceTools)
        add(tool("device_accessibility_type", "Substitui o texto de um campo editável do snapshot atual. Para credenciais, use ask_secure e o marcador correspondente em vez de texto livre. O texto digitado não aparece no log da ação.", buildJsonObject {
            put("snapshot_id", str("ID devolvido por device_accessibility_read_screen"))
            put("element_id", int("ID do campo editável no snapshot atual"))
            put("text", str("Texto a definir; para segredos, use o marcador do ask_secure"))
        }, listOf("snapshot_id", "element_id", "text")))
    if ("device_accessibility_scroll" in deviceTools)
        add(tool("device_accessibility_scroll", "Rola um controle visível para cima ou para baixo. Releia a tela depois da ação.", buildJsonObject {
            put("snapshot_id", str("ID devolvido por device_accessibility_read_screen"))
            put("element_id", int("ID do contêiner ou item no snapshot atual"))
            put("direction", str("up ou down"))
        }, listOf("snapshot_id", "element_id", "direction")))
    if ("device_share_file" in deviceTools)
        add(tool("device_share_file", "Abre o WhatsApp, Telegram, Slack ou Discord com um arquivo do workspace pronto para envio (no WhatsApp, com phone, direto na conversa do número). O envio final é um toque em \"Enviar\".",
            buildJsonObject { put("path", str("Arquivo do workspace")); put("app", str("whatsapp | telegram | slack | discord")); put("phone", str("WhatsApp: número com DDI e DDD, ex.: 5511999998888 (opcional)")); put("text", str("Legenda opcional")) }, listOf("path", "app")))
    if (allowMultiAgent) add(tool("live_server",
            "Live Server: publica um site (pasta do workspace com index.html) ou um servidor/API já rodando (porta local) numa URL pública HTTPS de acesso direto (sem tela de aviso), sem conta. " +
                "Use SÓ quando o usuário pedir para publicar/colocar no ar/expor. Publicar de novo gera um link novo; para mudar só o conteúdo de um site já no ar, edite os arquivos da pasta (o link continua o mesmo). " +
                "action=publish (dir OU port), stop (despublicar: tira o link do ar e encerra o app; project=\"todos\" para todos), status. Mostre a URL ao usuário.",
            buildJsonObject {
                put("action", str("publish | stop (despublicar) | status"))
                put("project", str("Nome do projeto. Use sempre o mesmo nome para o mesmo site."))
                put("dir", str("Pasta do site no workspace, ex.: /root/workspace/site (para sites estáticos)"))
                put("port", int("Porta local de um servidor/API já em execução (em vez de dir)"))
            }, listOf("action")))
    if (allowMultiAgent && channelConfig().let { dev.fluwy.app.share.ChannelSender.configured(it).isNotEmpty() })
        add(tool("send_file", "Envia sozinho um arquivo ou imagem do workspace para um canal configurado pelo usuário, sem tocar no celular. Canais ativos: " +
            dev.fluwy.app.share.ChannelSender.configured(channelConfig()).joinToString { it.id } + ". Imagens chegam como foto; demais arquivos como documento.",
            buildJsonObject { put("channel", str("telegram | discord | slack (um dos ativos)")); put("path", str("Arquivo do workspace, ex.: relatorio.pdf")); put("caption", str("Legenda/mensagem opcional")) }, listOf("channel", "path")))
    if ("device_notification_reply" in deviceTools)
        add(tool("device_notification_reply", "Responde uma conversa pelo botão \"Responder\" da própria notificação (WhatsApp, Telegram, Instagram, Messenger…), sem abrir o app nem ler a tela. Use o id de uma conversa marcada [pode responder] em device_notifications.",
            buildJsonObject { put("id", str("Id da conversa em device_notifications, ex.: n3")); put("text", str("Texto da resposta")) }, listOf("id", "text")))
    if ("device_open_link" in deviceTools)
        add(tool("device_open_link", "Abre um link no app que o atende (link direto): https://wa.me/<número>?text=<texto> abre a conversa do WhatsApp com o texto pronto; também links https de sites/perfis, spotify:, geo:, mailto:, tel:. Não envia nada sozinho.",
            buildJsonObject { put("url", str("Link a abrir, ex.: https://wa.me/5511999998888?text=Ol%C3%A1")) }, listOf("url")))
    if ("device_accessibility_tap" in deviceTools)
        add(tool("device_accessibility_tap", "Toca no controle da tela atual que tem o texto/rótulo informado (ex.: \"Enviar\", \"Pesquisar\"), sem trazer a tela inteira para o contexto. Se houver vários, use occurrence.",
            buildJsonObject { put("text", str("Texto ou rótulo visível do controle")); put("occurrence", int("Qual correspondência tocar quando há várias (1 = primeira; padrão 1)")) }, listOf("text")))
    if ("device_accessibility_back" in deviceTools)
        add(tool("device_accessibility_back", "Aciona o Voltar do Android. O snapshot atual deixa de valer.", buildJsonObject {}))
    // Hub de conectores: sempre no chat normal; num Agent, só se ele estiver ativado (padrão: ligado).
    if (allowMultiAgent) connectorTools().forEach { add(it) }
    // Conectores do hub: 3 ferramentas genéricas que servem para QUALQUER conexão feita no hub (nada por app).
    if (allowMultiAgent && agent?.useConnectorHub != false && dev.fluwy.app.connectors.HubConnectors.available(store)) {
        add(tool("conectores_listar", "Lista os conectores (contas conectadas no hub: Gmail, Notion, GitHub…) disponíveis agora, com o id de cada conexão. Chame quando o pedido envolver um app/serviço externo.", buildJsonObject {}))
        add(tool("conector_acoes", "Mostra o que um conector sabe fazer. Sem 'acao': lista as ações (nome técnico + descrição). Com 'acao': mostra os campos exatos dessa ação (obrigatórios, tipos, opções).",
            buildJsonObject { put("conector", str("Nome do app/piece, ex.: gmail ou @activepieces/piece-gmail")); put("acao", str("Nome técnico da ação (opcional)")); put("busca", str("Filtro por palavra, ex.: enviar (opcional)")) }, listOf("conector")))
        add(tool("conector_executar", "Executa UMA ação de um conector com a conta conectada (sem criar automação). Use os nomes e campos exatos de conector_acoes. " +
            "Devolve JSON pronto para ler: status, summary (count e first_item_preview), data (itens com \"preview\", textos decodificados e cortados com <campo>_truncated). " +
            "Responda direto a partir dele, sem grep/python/file_read. Só use completo=true se precisar de um campo que veio cortado.",
            buildJsonObject {
                put("conector", str("Nome do app/piece")); put("acao", str("Nome técnico da ação"))
                put("campos", buildJsonObject { put("type", "object"); put("description", "Valores dos campos da ação (chave = nome do campo)") })
                put("conexao", str("Id da conexão (de conectores_listar). Obrigatório quando o app exige login."))
                put("completo", buildJsonObject { put("type", "boolean"); put("description", "true = payload completo, sem cortes (maior). Padrão: resumo enxuto (~2 KB).") })
            }, listOf("conector", "acao")))
    }
    // UI Connectors (ex.: IQ Option): só os que este agente pode usar (toggle por agente / "Agent required?").
    runCatching { dev.fluwy.app.FluwyApp.instance.uiConnectors.toolDefinitions(agent) }.getOrDefault(emptyList()).forEach { add(it) }
    add(tool("reply_audio", "Responde por ÁUDIO no chat (voz gerada no aparelho). Use quando o usuário pedir uma resposta falada: passe o texto exato a falar (curto, natural, sem markdown). Se ele pedir para responder SEMPRE/SÓ com áudio daqui para frente, passe always=true (e always=false quando pedir para voltar ao texto); com o modo ligado, as respostas finais viram áudio sozinhas — não chame de novo a cada resposta.",
        buildJsonObject { put("text", str("Texto a falar (pode ficar vazio se só ligar/desligar o modo)")); put("always", buildJsonObject { put("type", "boolean"); put("description", "true = responder sempre em áudio nesta conversa; false = voltar ao texto") }) }, emptyList()))
    add(tool("notify", "Avisa o usuário fora do chat. Use com critério: 'silent' só registra no histórico (rotina sem novidade), 'low' aviso discreto, 'normal' conclusão importante ou falha moderada, 'high' precisa da atenção dele, 'critical' falha grave que exige intervenção. Nunca repita o mesmo aviso.",
        buildJsonObject {
            put("title", str("Título curto")); put("text", str("Mensagem objetiva (o que houve e o que fazer)"))
            put("priority", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { listOf("silent", "low", "normal", "high", "critical").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
        }, listOf("title", "text", "priority")))
    add(tool("show_cards", "Mostra resultados como cards visuais no chat (produtos, notícias, sites, resultados de busca, serviços). Use depois de coletar os dados de verdade (browser, web, conector): cada campo precisa ter vindo da fonte — nunca invente preço, nota, imagem ou link. SEMPRE mande 'image' (URL da foto do item como aparece na página/resultado; no browser use browser_js para ler o src das imagens se precisar) e 'url' do item — sem image, o app tenta buscar a foto pela url. 'badge' (ex.: Menor preço, Melhor avaliação) só quando comprovado entre os itens mostrados. Em produtos, o usuário pode tocar num card para escolher; a escolha chega como mensagem [Seleção].",
        buildJsonObject {
            put("kind", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { listOf("product", "news", "website", "search", "service").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }); put("description", "Tipo dos cards") })
            put("title", str("Título curto acima dos cards (opcional)"))
            put("items", buildJsonObject {
                put("type", "array"); put("description", "De 1 a 12 itens")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        listOf("title" to "Nome/manchete", "subtitle" to "Descrição ou resumo curto", "image" to "URL https da imagem", "price" to "Preço atual (ex.: R$ 99,99)",
                            "oldPrice" to "Preço anterior", "source" to "Loja, site ou veículo", "rating" to "Avaliação (ex.: 4.8)", "badge" to "Selo comprovado", "url" to "Link https",
                            "date" to "Data de publicação", "ref" to "Identificador do item na loja").forEach { (k, d) -> put(k, str(d)) }
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("title")) })
                })
            })
        }, listOf("kind", "items")))
    // Cofre: o agente vê só apelidos e gera QR Pix que o APP desenha no chat — chave, código e cartões nunca chegam à IA.
    add(tool("cofre_listar", "Lista o Cofre do usuário: chaves Pix (id e apelido) e cartões (só apelido e bandeira). Nenhum dado sensível é mostrado a você.", buildJsonObject {}))
    add(tool("pix_qr", "Gera uma cobrança Pix (QR Code + código copia e cola) com uma chave do Cofre e mostra no chat com botões de copiar e enviar. " +
        "Valor fixo (o banco não deixa trocar) ou vazio para valor livre. Você não vê a chave nem o código: não tente escrevê-los.",
        buildJsonObject {
            put("chave", str("Id ou apelido da chave (de cofre_listar). Opcional se houver só uma."))
            put("valor", str("Valor em reais, ex.: 25,90. Vazio = valor livre (quem paga digita)."))
            put("descricao", str("Descrição curta (opcional, até 40 caracteres)"))
        }))
    add(tool("tools_load", "Carrega ferramentas que estão fora do contexto (listadas em 'Ferramentas sob demanda'): grupos (ex.: wallet, device, web_dev, agents, schedule), connector:<id> ou mcp:<servidor>. Elas ficam disponíveis a partir da sua próxima resposta e até o fim da tarefa.",
        buildJsonObject { put("names", strArray("Nomes a carregar, ex.: [\"wallet\", \"connector:iqoption\"]")) }, listOf("names")))
    if (allowMultiAgent) add(tool("ask_user", "Faz uma pergunta aberta ao usuário e pausa a tarefa até ele responder. Quando a resposta for uma escolha entre opções, prefira ask_question.", buildJsonObject { put("question", str("Pergunta clara e completa")) }, listOf("question")))
    fun objArr(desc: String, props: JsonObject, required: List<String>) = buildJsonObject {
        put("type", "array"); put("description", desc)
        put("items", buildJsonObject {
            put("type", "object"); put("properties", props)
            put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })
    }
    fun boolP(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
    if (allowMultiAgent) add(tool("ask_question",
        "Mostra ao usuário um card de perguntas de múltipla escolha (1–4 perguntas, uma por página, 2–4 opções cada, com 'Outra resposta' e 'Pular') e pausa até ele responder. " +
            "Use apenas quando o usuário pediu uma tarefa e existe uma dúvida real, que muda o resultado e que você não consegue resolver pesquisando nem com uma suposição razoável. No máximo uma vez por pedido.",
        buildJsonObject {
            put("questions", objArr("Perguntas (1 a 4)", buildJsonObject {
                put("question", str("Pergunta clara e completa, terminando com ?"))
                put("header", str("Rótulo curto (até 12 letras), ex.: Formato"))
                put("options", objArr("2 a 4 opções diferentes", buildJsonObject {
                    put("label", str("Texto da opção (1–5 palavras)"))
                    put("description", str("Opcional: o que essa escolha implica"))
                }, listOf("label")))
                put("multi_select", boolP("true se o usuário puder marcar várias opções"))
                put("allow_other", boolP("Mostra 'Outra resposta' com texto livre (padrão true)"))
            }, listOf("question", "options")))
        }, listOf("questions")))
    if (allowMultiAgent) add(tool("ask_secure",
        "Mostra um formulário seguro para o usuário digitar API key, token, e-mail/senha ou dados de formulário. Os valores ficam somente no aparelho e não chegam a você: " +
            "use depois {{secret:<name>}} nos argumentos das ferramentas, e o app troca o marcador pelo valor real na execução e o esconde das saídas. É o caminho para qualquer credencial; pedir segredos no chat os deixaria expostos no histórico.",
        buildJsonObject {
            put("title", str("Título do formulário, ex.: Chave da API da Eduzz"))
            put("reason", str("Por que o dado é necessário, em uma frase, ex.: para testar o conector com dados reais"))
            put("fields", objArr("Campos (1 a 8)", buildJsonObject {
                put("name", str("Nome snake_case usado no marcador {{secret:name}}, ex.: api_key"))
                put("label", str("Rótulo mostrado ao usuário"))
                put("type", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { AskTools.SECURE_TYPES.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }); put("description", "password/token/api_key ficam mascarados") })
                put("required", boolP("Obrigatório (padrão true)"))
                put("hint", str("Dica ou placeholder opcional, ex.: começa com sk_"))
            }, listOf("name", "label")))
        }, listOf("fields")))
    add(tool("finish", "Conclui a tarefa com um resumo do resultado e a lista de entregáveis. Chame depois de conferir que o resultado existe e funciona.", buildJsonObject {
        put("summary", str("Resumo do resultado: o que foi feito e o que o usuário recebe")); put("files", strArray("Caminhos dos arquivos entregues"))
    }, listOf("summary")))
}
