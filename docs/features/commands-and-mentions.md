# Comandos (/) e menções (@)

Caixa de mensagem do chat (`ui/Composer.kt`) com duas caixas de sugestão: `@` para recursos e `/` para comandos.

## Arquivos

| Arquivo | Papel |
|---|---|
| `agent/Mentions.kt` | Reconhece menções no texto (`Mentions.find`), o nome mais longo vence. |
| `agent/MentionSuggest.kt` | Lógica pura da caixa `@`: as duas opções iniciais, a segunda caixa e o formato do token. |
| `agent/SlashCommands.kt` | Catálogo dos comandos `/` (grupo, descrição, argumento), filtro, ajuda e `/skill`/`/agent`. |
| `ui/ComposerSuggestions.kt` | Monta as linhas e desenha a caixa de sugestões. |
| `ui/ChatScreen.kt` (`onSend`) | Handlers dos comandos. |
| `agent/AgentRunner.mentionTargets()` | Alvos conhecidos: conectores, Web APIs, skills e agents. |
| `agent/AgentPrompts.kt` + `prompts/DefaultsAgente.kt` | Trecho do prompt de sistema para menções (`agent_system.1`, `agent_system.connector_mention`). |

## `@` — Conectores e API Hub

1. Ao digitar `@`, a caixa mostra **só duas opções**: **Conectores** e **API Hub** (texto parcial filtra, ex.: `@con`).
2. Escolher uma insere `@CONECTOR:` ou `@APIHUB:` e abre a segunda caixa, filtrada pelo texto após os dois-pontos:
   - Conectores: conectores MCP conectados e UI Conectores, exibidos como `@conectores: Nome`.
   - API Hub: Web APIs ativas.
3. Tokens inseridos: `@CONECTOR:<chave>` (chave = id do conector com `:` e símbolos trocados por `_`, ex.: `mcp_gmail`, `connector_iqoption`) e `@APIHUB:<slug>`.

Como o agente usa:
- `AgentWebApiTools` fixa no contexto as ferramentas do conector marcado desde o 1º passo (`ToolContextPlanner.load`).
- O prompt recebe a linha `6a. O usuário marcou com @: …` com o texto de `agent_system.connector_mention` (usar direto as ferramentas `mcp_<id>*`) ou `agent_system.1` (chamar `webapi_call`). O bloco "Como trabalhar" também explica `@CONECTOR:` e `@APIHUB:`.
- `@Nome do conector` continua reconhecido por compatibilidade.

## `/` — Comandos

| Grupo | Comando | Ação |
|---|---|---|
| Conversa | `/new` | Novo chat (vai para a Home). |
| | `/stop` | Para o agente (ou a seção). |
| | `/undo` | Desfaz a última alteração de arquivo. |
| Contexto | `/compact` | Compacta o contexto agora. |
| | `/autocompact` | Liga/desliga a compactação automática. |
| | `/clear` | Reinicia a sessão do zero. |
| | `/usage` | Uso de tokens e contexto. |
| Skills e agentes | `/skill <skill> …` | Abre a lista de skills; escolher insere `@Skill`, que o agente carrega com `skill_load`. |
| | `/agent <agent> …` | Abre a lista de agents; escolher insere `@Agent` (passa a tarefa). |
| | `/create-agent <descrição>` | Cria um agent a partir da descrição (`@create-agent` ainda funciona). |
| Abrir | `/skills`, `/agents`, `/connectors`, `/memory` | Abre a tela. |
| | `/help` | Ajuda agrupada. Comando desconhecido também mostra a ajuda. |

Digitar `/` lista tudo agrupado; o filtro prioriza prefixo e depois procura no nome e na descrição. Se a mensagem for enviada como `/skill Nome resto` sem escolher na lista, `SlashCommands.resolveArgCommand` converte para `@Nome resto`.

## Testes

`MentionSuggestTest`, `SlashCommandsTest` e `AgentConfigRulesTest` em `app/src/test/java/dev/fluwy/app/agent/`.

## Configuração do agent

`ui/AgentEditScreen.kt` organiza tudo em seções recolhíveis com resumo (`agent/AgentConfigRules.kt`): Identidade (aparência, nome, missão), Comportamento (instruções), Modelo, Ferramentas e permissões (`ui/AgentToolsPanel.kt`: ferramentas, UI conectores, acessos ao Android, confirmações, carteira), Conhecimento e skills, Autonomia e agenda (horário ativo, orçamento, notificação) e Avançado (estatísticas, zerar falhas, excluir com confirmação). Salvar fica bloqueado com nome vazio, repetido ou janela de horário inválida.

## Limitações

- O token mostra a chave técnica no campo de texto; o nome legível aparece na caixa de sugestões.
- `/new` só navega para a Home; a sessão nova é criada ao enviar a primeira mensagem.
