# API Hub (Web APIs, WhatsApp e servidor local)

## Propósito

O API Hub transforma sites em "APIs" chamáveis pelo agente, por fluxos e por outros apps locais. Cada Web API é um par *template de URL + código JS* executado num WebView próprio do app, que reaproveita perfil (logins), proxy e palco off-screen do browser. O WhatsApp Web é integrado como caso especial, com webhook de mensagens recebidas.

## Arquivos principais

| Arquivo | Papel |
|---|---|
| `browser/WebApiHub.kt` | Catálogo: nativas (`assets/webapi/builtins.json`) + do usuário/IA (mesmo slug sobrepõe). `call`, `catalog`, `doc`, `relevant`, `dryOk`, estatísticas. |
| `browser/WebApiRunner.kt` | Motor: monta URL, navega, espera `wait_for`, injeta bibliotecas, roda o código e devolve envelope JSON. `probe` e `network` para descoberta. |
| `browser/WebApiFs.kt` | Ponte JS `__fluwyFs` (ler/escrever/listar arquivos e emitir chunks) restrita à área de trabalho da chamada. |
| `browser/WaLocalServer.kt` | Servidor HTTP mínimo em `127.0.0.1:8787` (até 8797) para webhook do WhatsApp e Web APIs. |
| `browser/WhatsAppHub.kt` | Config (bloqueados/permitidos/sem grupos), escutador WA-JS, disparo de fluxos. |
| `agent/AgentWebApiTools.kt` | Ferramentas `webapi_list/call/test/save/probe` e bloco de APIs relevantes no prompt. |
| `data/Models.kt` | `WebApi` (slug, urlTemplate, code, params, waitFor, desktop, inject, sensitive, estatísticas). |

## Modelo de dados

- Slug válido: `^[a-z][a-z0-9_]{2,39}$`.
- Cópia salva de uma nativa guarda só `enabled`/estatísticas; o código vem sempre do app. Versão editada (`builtin=false`) vale inteira.
- `sensitive = true`: ação externa (ex.: enviar mensagem); exige `confirmed=true`.
- Persistência via `Store` (`webApis`); preferência `webapi_hub/reset_v1` zerou o hub uma única vez.

## Fluxo de uma chamada

```mermaid
sequenceDiagram
  participant Ag as Agente/Fluxo/HTTP local
  participant Hub as WebApiHub.call
  participant Run as WebApiRunner.run
  participant WV as WebView __webapi__
  Ag->>Hub: slug + args
  Hub->>Hub: valida params obrigatórios; injeta __wa_policy em whatsapp_*
  Hub->>Run: run(timeout 100 s, workspace)
  Run->>WV: URL do template, wait_for, inject libs, código async(args, api)
  WV-->>Run: resultado / api.emit(chunks)
  Run-->>Hub: envelope JSON {ok, ...}
  Hub->>Hub: dryOk + runs/errors/lastError
```

- Dois WebViews: `__webapi__` (geral) e `__webapi_wa__` (só WhatsApp Web, para não recarregar a cada chamada), cada um com seu mutex.
- Sem chat, a área de trabalho é `sandbox.workspaceFor("api-hub")`.
- Bibliotecas `inject` (https) são baixadas e guardadas por 24 h.
- `__dry_run`: a API valida e para antes de agir; vira sucesso simulado.

## Criação pela IA

Modo `webApiBuilder`: `webapi_probe` (lista fetch/XHR e estados JSON embutidos como `__NEXT_DATA__`) → `webapi_test` → `webapi_save` (só após teste que passou com o mesmo código e template).

## WhatsApp

- `WhatsAppHub.start` liga o escutador; `tick` a cada ~3 s lê a fila do WA-JS (`chat.new_message`). Sem página pronta, chama `whatsapp_status` no máximo 1×/min.
- `dispatch` descarta: `from_me` (salvo `includeFromMe`), `status@broadcast`, tipos de protocolo (`NOISE`), mensagens terminadas em `BOT_MARK` (U+2063, evita eco do atendente) e chats não permitidos.
- Mensagens aceitas vão para "recentes" (30), para o `WaLocalServer` e disparam fluxos com gatilho "Mensagem no WhatsApp" (`nodeMatches`: conversa, tipo, palavra-chave). Mensagens que chegam com o fluxo rodando ficam pendentes e disparam em ordem.
- `policyJson` (regras do usuário) é sempre injetado pelo app, nunca pelo modelo.

## Servidor local (WaLocalServer)

| Rota | Função |
|---|---|
| `GET /whatsapp/<token>?since=&limit=&wait=` | Últimas mensagens (long-poll até 25 s). |
| `GET /whatsapp/<token>/schema` | Esquema do evento. |
| `POST /whatsapp/<token>` | Injeta evento e dispara fluxos. |
| `GET /webapi/<token>` | Catálogo das APIs. |
| `POST /webapi/<token>/<slug>` | Executa; resposta `text/event-stream` (`event: chunk` / `event: result`). |

Token errado → 403. Eventos só em memória (últimos 200). Token gerado como `wa_` + 20 hex.

## Segurança

- Servidor escuta só em `127.0.0.1`.
- `WebApiFs` exige token de uso único da chamada em andamento, caminho canônico dentro da raiz, bloqueia `.fluwy` e limita a 30 MB.
- APIs `sensitive` sem `confirmed=true` retornam `RECUSADO`.
- Regras de contatos do WhatsApp não podem ser sobrescritas pelo modelo (`__wa_policy` é substituído).

## Como estender

- Nova API nativa: adicione a `assets/webapi/builtins.json` (helpers comuns em `assets/webapi/helpers.js`).
- Nova rota local: `WaLocalServer.handle`.

## Limitações conhecidas

- O webhook depende do processo do app vivo (serviço em foreground) e do WhatsApp Web carregado.
- Erro/sucesso detectado por busca textual (`"ok":true`) no JSON.
- O servidor HTTP é minimalista (parsing manual, thread por conexão).
