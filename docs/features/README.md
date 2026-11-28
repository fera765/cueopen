# Documentação de funcionalidades do Fluwy

Documentação técnica das principais funcionalidades do app Android Fluwy (pacote `dev.fluwy.app`, código em `app/src/main/java/dev/fluwy/app`). Cada documento descreve propósito, arquivos, fluxo, persistência, limites de segurança, extensão e limitações conhecidas, com base no código atual.

## Núcleo do agente

| Documento | Conteúdo |
|---|---|
| [agent-loop.md](agent-loop.md) | `AgentRunner`, `AgentLoop`, `ToolContextPlanner`, guardas de loop, compactação, dispatch e famílias de ferramentas. |
| [tools.md](tools.md) | Convenções de schema, famílias de ferramentas, prefixos `ERRO`/`GUARDRAIL`/`RECUSADO`, como adicionar ferramenta, `ToolSchemaQualityTest`. |
| [memory-and-knowledge.md](memory-and-knowledge.md) | `ContextManager`, memórias, knowledge, `NOTES.md`, `MemoryHygiene`, `MemoryGraph`, compactação. |
| [prompts.md](prompts.md) | Catálogo de prompts, sintaxe de template, Configurações > Prompts. |

## Ambientes de execução

| Documento | Conteúdo |
|---|---|
| [terminal.md](terminal.md) | Sandbox Alpine/proot, `TerminalManager.runAgentCommand`, captura de saída, prompts interativos, servidores em segundo plano. |
| [browser.md](browser.md) | `BrowserHost`, abas, navegação, `HostPacing`, captura de rede e console. |
| [api-hub.md](api-hub.md) | `WebApiHub`, `WebApiRunner`, `WebApiFs`, `WaLocalServer`, `WhatsAppHub`. |

## Automação e colaboração

| Documento | Conteúdo |
|---|---|
| [workflows.md](workflows.md) | Motor de fluxos, tipos de nó, gatilhos (agenda, webhook, WhatsApp), templates, testes e publicação. |
| [scheduling-and-triggers.md](scheduling-and-triggers.md) | Tarefas agendadas, gatilhos de eventos, WorkManager, heartbeat e autonomia. |
| [sections-and-teams.md](sections-and-teams.md) | Seções com coordenador, plano e revisão, delegação, `multi_agents`, build squad. |

## Segurança e operação

| Documento | Conteúdo |
|---|---|
| [vault-and-secrets.md](vault-and-secrets.md) | `SecretVault`, `CredentialVault`, Cofre (Pix/cartões), redação no `ErrorLog`. |
| [diagnostics.md](diagnostics.md) | `ErrorLog` JSONL, `ActivityLog`, tela de Logs, exportações. |

## Documentos relacionados (fora desta pasta)

- `docs/ui-connector-sdk.md` — SDK de UI Connectors.
- `docs/solana-wallet-security.md`, `docs/wallet-threat-model.md`, `docs/wallet-recovery.md` — carteira Solana.
- `docs/android-accessibility-research.md` — acessibilidade.
