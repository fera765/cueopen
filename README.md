# CueOpen

Agente autônomo para Android (Kotlin + Jetpack Compose) com **computador próprio**: terminal Linux
local (proot + Alpine, arquitetura do Acode) e **browser** (WebView) controlados pelo agente.
Sem login, sem nuvem: tudo fica no aparelho. LLM via OpenRouter com **thinking desligado por padrão**.

## Como compilar
1. Abra a pasta no **Android Studio** (Ladybug ou mais novo). Ele gera o Gradle wrapper e baixa as dependências.
2. Na primeira build, a task `downloadXterm` baixa o xterm.js do jsDelivr para `app/src/main/assets/terminal/`.
3. `Run ▶` num aparelho arm64 (ou emulador x86_64). APK: `./gradlew assembleDebug`.
4. No app: onboarding → cole a chave da OpenRouter → **Instalar** o sandbox (baixa o Alpine 3.21 minirootfs, ~3 MB + pacotes).

## Engenharia reversa do Cue 26.18.2 (`tech.butterfly.app`)
| Item | Achado |
|---|---|
| Pacote / dono | `tech.butterfly.app` (Butterfly Effect / Manus), app interno `next_agent_android` |
| Stack | Flutter (Dart AOT em `libapp.so`, 38 MB), UI `forui` + `material_ui`, ícones `flutter_lucide`, `flutter_math_fork` |
| Browser | `flutter_inappwebview` + Chrome Custom Tabs |
| Terminal | **remoto**: `/api/terminals/ticket` + WebSocket `/api/terminals/ws` (sandbox na nuvem da Manus) |
| Desktop | `/api/desktop/screen-capture`, `/api/desktop/execution-policy` (Manus Desktop / cloud computer) |
| Outros nativos | WebRTC (`libjingle_peerconnection`), PDFium, ML Kit barcode, Sentry, Firebase (FCM/Analytics/Remote Config), Play Billing, FingerprintJS Pro, Health Connect, gravação de áudio |
| Rotas | `/chat`, `/chat/attachment_preview`, `/chat/suggested_knowledge`, `/session_attachment_list`, `/session_attachment_viewer`, `/skill-creator`, `/webview`, `/settings`, `/feedback`, `/new_pricing_subscription`, `/login` |
| Funções (strings) | Agents (avatar, memória, skills, telefone virtual, e-mail, pagamentos), Skills + Skill creator, Scheduled tasks, Knowledge, Cloud computer (terminal/browser/arquivos), Get started (Slides, Website, Wide Research, Criar/Editar imagem, Playbook, Mobile app), Share/Replay, "Make same task", Gmail/Google Drive, convites, assinatura VIP |
| Idiomas | EN, PT, ES, FR, DE, IT, NL, ID, TR, PL, CS… |

O código Dart está compilado/ofuscado (AOT), então o mapeamento foi feito por manifest, assets, rotas e ~10 mil strings de UI.

## O que o CueOpen implementa (paridade de funcionamento)
| Cue | CueOpen |
|---|---|
| Lista de tarefas + drawer com busca | ✅ agrupado por Hoje/Ontem/7 dias |
| Composer "Assign a task or ask anything", anexos, voz | ✅ (voz = reconhecimento do Android) |
| Cards Get started | ✅ Slides, Website, Wide Research, Criar imagem, Playbook, Mobile app, Analisar dados |
| Linha do tempo com passos de ferramenta e plano | ✅ cards de passo expansíveis + plano com progresso |
| Card "computador do agente" ao vivo | ✅ abre o Computador e segue a ferramenta ativa |
| Cloud computer: Terminal / Browser / Arquivos | ✅ **local**: xterm.js + axs, WebView compartilhado, gerenciador + editor |
| Agents (nome, avatar/cor, instruções, skills, memória) | ✅ + modelo por Agent e ferramentas on/off |
| UI Connectors YAML | ✅ + delegação limitada ao Agent configurado com terminal/arquivos autorizados e exemplo local instalável Fluwy Open Dev |
| Skills + Skill creator | ✅ 7 nativas + criação manual ou conversando (`create_skill`) |
| Scheduled tasks | ✅ WorkManager (uma vez / hora / dia / semana) + notificação |
| Knowledge | ✅ injetado no contexto do agente |
| Share / Replay / Make same task | ✅ export Markdown + arquivos, replay animado 1x/2x/4x |
| Login, assinatura, convites | ❌ removidos de propósito (sem login) |
| Telefone virtual, e-mail do Agent, pagamentos, Manus Desktop | ❌ exigem a infraestrutura de nuvem da Manus |

Identidade visual própria (mascote "Pip", paleta papel/tinta/laranja). Nenhum asset, logo ou código da Manus foi copiado.

## Arquitetura do terminal (portada do Acode)
```
Kotlin ProcessBuilder ─► sh $PREFIX/init-sandbox.sh  (script do Acode, MIT)
   └─ $NATIVE_DIR/libproot-xed.so  (proot, empacotado em jniLibs, extractNativeLibs)
        -r $PREFIX/alpine  -b $PREFIX/public:/root  -b /sdcard …
        └─ init-alpine.sh
             ├─ "comando"         → ferramenta `shell` do agente (stdout ao vivo na aba Terminal)
             └─ sem args          → $PREFIX/axs -c "bash -i"   (libaxs.so, servidor PTY)
                                     POST /terminals {cols,rows} → pid
                                     WS   /terminals/{pid}
                                     POST /terminals/{pid}/resize
WebView: assets/terminal/index.html (xterm.js + fit) ⇄ ws://127.0.0.1:8767
```
`targetSdk 28` (como o build F-Droid do Acode) para permitir exec dentro do sandbox. Para publicar na Play Store
seria preciso targetSdk 34+, que bloqueia exec de binários fora de `nativeLibraryDir`.

## Ferramentas do agente
`update_plan`, `shell`, `file_write`, `file_read`, `file_list`, `browser_navigate`, `browser_read`
(texto + elementos numerados), `browser_click`, `browser_type`, `browser_scroll`, `browser_back`,
`browser_js`, `remember`, `create_skill`, `ask_user`, `finish`.

## Planejamento contextual do agente
`agent/ToolContextPlanner.kt` decide, a cada passo, quais ferramentas e conectores entram no contexto:
- **Ferramentas por grupos**: núcleo sempre; `wallet`, `device`, `web_dev`, `agents`, `schedule`, `skills_admin` e
  `connector_builder` entram quando o pedido indica, quando já foram usados ou quando o modelo chama `tools_load`.
- **Conectores (`uic_*`) e MCP (`mcp_*`)**: seleção por relevância e uso, com índice para carregar itens adicionais sob demanda.
- **Skills, knowledge e memória**: ordenação por relevância e orçamento ajustado conforme o histórico cresce.

## Estrutura
```
agent/AgentRunner.kt     loop do agente + ferramentas
llm/OpenRouter.kt        cliente OpenAI-compatível (reasoning.enabled=false)
sandbox/Sandbox.kt       instalação Alpine, exec, servidor axs
browser/BrowserHost.kt   WebView compartilhado + automação JS
scheduler/Scheduler.kt   tarefas agendadas
data/                    modelos + store JSON local
ui/                      Home, Chat, Computador, Agents, Skills, Agendadas, Knowledge, Configurações, Replay, Onboarding
```

## Licenças de terceiros
`init-sandbox.sh` e os binários `libproot*.so`, `libtalloc.so` e `libaxs.so` vêm do
[Acode](https://github.com/Acode-Foundation/Acode) (MIT) e do [acodex_server](https://github.com/bajrangCoder/acodex_server).
O proot é GPL-2.0 (binário separado, sem linkagem). O xterm.js é MIT.
