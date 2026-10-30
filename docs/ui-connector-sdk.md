# CueOpen UI Connector SDK

O SDK oferece conectores com interface própria: login, painéis, ações do usuário e ferramentas que o agente pode chamar. Há duas formas de implementação: conectores nativos em Kotlin e conectores personalizados declarativos, escritos em YAML.

## O que o app gerencia

| Responsabilidade | Componente |
|---|---|
| Formulário de login | `ui/UiConnectorScreen.kt` |
| Cofre de credenciais cifrado com AES-256-GCM e Android Keystore | `CredentialVault.kt` |
| Painéis genéricos | `UiBlock` e `UiBlockView` |
| Habilitação, configurações e seleção por agente | `UiConnectorHub.kt`, `AgentProfile.uiConnectorIds` |
| Ferramentas do agente `uic_<id>_<ferramenta>` | `UiConnectorHub.toolDefinitions` |
| Redação de segredos | `Redactor` |
| Erros de execução | `ErrorLog`, origem `uiconnector` |
| Validação de conectores nativos | `SpecValidator` |

Um agente só recebe as ferramentas de um conector quando ele está habilitado e conectado (ou tem credenciais para reconectar). Se **Agent required ?** estiver ligado, o agente também precisa ter o conector selecionado no perfil.

## Segurança

- Senhas, tokens e valores extraídos ficam fora do contexto do modelo. Não os inclua em mensagens de erro nem em definições.
- Operações sensíveis devem ser `risky`, aplicar limites configuráveis e responder `RECUSADO: <motivo>` quando forem negadas.
- `onAction` representa uma ação explícita do usuário; `callTool` representa uma chamada do agente.
- Credenciais nunca devem ser substituídas por valores sintéticos durante uma execução real. O dry-run do criador é outra coisa: ele usa mocks locais e não comprova acesso real.

## Conectores nativos em Kotlin

Conectores nativos implementam `UiConnector`, declaram o contrato em `UiConnectorSpec` e são registrados em `UiConnectorHub.connectors`. A especificação inclui `id`, nome, descrição, versão, campos de login, configurações e ferramentas. Use `SpecValidator.validate(spec)` e um teste para garantir que todas as ferramentas têm handler. Os blocos de UI disponíveis incluem `Header`, `Stats`, `Table`, `Segmented`, `Button` e `Note`; a tela atualiza os dados periodicamente enquanto está aberta.

O diretório `uiconnector/iqoption/` implementa o cliente não oficial da IQ Option. Seu protocolo pode mudar sem aviso; mensagens inesperadas são registradas para diagnóstico. Os limites do agente e as aprovações de ordens continuam impostos pelo app.

O diretório `uiconnector/gmail/` usa a API oficial do Gmail, OAuth 2.0 com PKCE e redirect loopback. O usuário configura o próprio projeto Google Cloud e o app mantém o refresh token no cofre cifrado. Envio de mensagens continua sujeito à autorização e aos limites configurados.

## Conectores personalizados em YAML

Em **Conectores → UI Conectores → Criar conector (SDK)**, a definição começa vazia. O editor não oferece presets ou exemplos preenchidos. O usuário pode escrever YAML ou pedir ao agente que crie, valide, teste offline e proponha a instalação.

O formato-fonte da definição é YAML. O parser produz o modelo tipado interno usado pela execução, pelo validador e pelos guardrails. Definições antigas em JSON continuam legíveis como formato legado; ao salvar/importar um legado, o app normaliza e persiste a definição em `.yaml`. O app não converte para YAML outros JSON do produto: payloads de APIs, mensagens de ferramentas, armazenamento do app e o envelope de backup continuam usando seus formatos próprios. O backup das definições permanece um contêiner JSON, sem credenciais.

O parser limita a definição a 64 KB e a profundidade permitida, rejeita chaves duplicadas e tags customizadas, desativa aliases de coleções e exige um mapa na raiz. Evite âncoras e aliases. Coloque entre aspas strings que começam por `{{...}}`, parecem número/booleano/data ou contêm pontuação que possa ser interpretada pelo YAML. Valores de data que devam permanecer texto também precisam de aspas.

A especificação completa do contrato YAML fica em [`SdkPrompt.kt`](../app/src/main/java/dev/cueopen/app/uiconnector/custom/SdkPrompt.kt) e é a mesma apresentada no botão **Doc** e ao agente por `connector_spec`.

### Campos suportados

A raiz exige `id`, `name` e `description`; `baseUrl` é necessário para rede, mas pode faltar num conector só de cálculo. O `id` tem 2–20 caracteres, começa com letra minúscula e usa apenas letras minúsculas e dígitos. `baseUrl` e `allowedHosts` limitam destinos de rede.

Campos opcionais da raiz: `version`, `accent`, `headers`, `login`, `settings`, `panels`, `actions`, `tools`, `realtime`, `capabilities` e `setup`.

- **Login:** `fields` declara de 1 a 8 campos (`text`, `email`, `password`, `number`) e exige ao menos um campo de senha. `request`, `extract` e `verify` declaram a autenticação; chaves de API podem não fazer uma requisição de login.
- **Requests:** `method`, exatamente um de `path` ou `url`, e opcionalmente `query`, `headers`, `body` ou `form`. Só HTTPS e hosts declarados; `body` e `form` não coexistem. Respostas JSON, XML/RSS/Atom, JSONP, NDJSON, CSV/TSV, HTML e texto puro são normalizadas.
- **Settings:** até 20 opções do usuário do tipo `number`, `switch` ou `text`, com valor padrão e, quando necessário, teto de risco.
- **Panels:** até 10 blocos `header`, `stats`, `table` ou `note`. Tabelas têm até 8 colunas e 100 linhas, e leem uma resposta ou coleção realtime.
- **Actions:** até 10 botões explícitos do usuário. Método diferente de GET requer `risky: true`.
- **Tools:** até 30 ferramentas com nome snake_case, descrição e até 12 parâmetros tipados. Cada ferramenta usa no máximo uma fonte operacional: `request`, `send`, `source`, `shell`, `file` ou `fetch`. `response` e `list` modelam a saída.
- **Limites e ordens:** `limits` vincula parâmetros numéricos a settings numéricas. Operações sensíveis exigem `risky`; ordens exigem `requiresSignal`, `orderIntent`, limites e a validação TypeSafe Jev imposta pelo app.

### Templates e cálculo

Modelos `{{...}}` acessam somente variáveis disponíveis no contexto da definição: campos de login, valores extraídos, settings, parâmetros, estado realtime, `rid`, `now` e resultados de `compute`. A resposta atual pode ser lida por caminhos como `$.campo[0]` em textos de exibição e respostas, não em host, headers ou URL. Os filtros incluem codificação de URL, conversões numéricas, texto, datas, remoção de HTML, truncamento e Base64.

`compute` executa expressões locais em respostas, settings, estado realtime e parâmetros. Não acessa rede, arquivos ou segredos. Inclui operações de listas e séries, estatística e indicadores técnicos, com limites de operações, cálculos por item e tamanho de lista. Use somente as funções efetivamente implementadas pelo motor.

### Tempo real e capacidades

`realtime` suporta WebSocket `wss://` em host autorizado, mensagens `onOpen`, heartbeat e regras `match`, `set` e `push`. As regras atualizam estado e coleções consultáveis por painéis e ferramentas. Ferramentas podem enviar mensagens e aguardar resposta correlacionada por `rid`.

`capabilities` declara recursos opcionais do aparelho. Cada recurso fica desabilitado até autorização do usuário:

- `terminal`: comando isolado e limitado por tempo; `setup` prepara dependências uma vez. Argumentos templated são escapados, mas isso não torna seguro código arbitrário.
- `files`: operações `read`, `write`, `list`, `delete` e `replace` no workspace isolado. Escrita, exclusão e substituição exigem `risky`. `replace` altera somente a correspondência esperada; a gravação é atômica. No DSL do conector a edição declarativa é `file.op: replace`; `file_edit` continua como ferramenta interna do agente no workspace da tarefa, fora do esquema do conector.
- `browser`: busca HTTPS de host autorizado e leitura do texto renderizado; não contorna autenticação, CAPTCHA ou controle de acesso.

## Validação e dry-run do criador com IA

O fluxo de criação é `connector_spec` → `connector_validate` → `connector_test` → `connector_propose`. O agente não deve repetir uma chamada idêntica após receber o mesmo erro; corrija o YAML ou encerre explicando o bloqueio. Durante uma sessão, a mesma assinatura de `connector_validate`, `connector_test` ou `connector_propose` reutiliza o resultado em memória, evitando executar o mesmo dry-run ou criar um segundo cartão de proposta. Se o agente continuar repetindo sem progresso, o guard interrompe o ciclo após a segunda ocorrência.

`connector_test` usa o parser e os guardrails reais do SDK, mas substitui as integrações por fixtures HTTP/WebSocket, filesystem virtual e respostas simuladas de browser/shell. Login usa valores sintéticos. O teste não abre rede externa, não resolve credenciais reais, não abre páginas, não executa `setup`/shell/código gerado e não envia mensagens, ordens, publicações ou pagamentos. Ferramentas que dependem de shell/setup falham fechadas no dry-run.

O atestado é vinculado ao SHA-256 canônico do YAML e à versão da política. O mesmo YAML precisa passar no dry-run para ser proposto; o app revalida antes de exibir o botão e novamente antes de salvar. Alterar a definição exige um novo teste. O resultado comprova apenas os contratos exercitados localmente — não confirma endpoint ativo, autenticação, permissão ou disponibilidade real do serviço.

A política de saída `outputPolicy` do tipo `vsl` pode conferir duração estimada, idioma por heurística, ordem de seções e termos proibidos. A contagem não garante duração de locução nem revisa semântica ou fatos.

Não invente endpoints, não use scraping ou contorne login/CAPTCHA. Serviços sem API pública/autorizada estável devem ser explicados como indisponíveis para este formato. OAuth de consentimento/PKCE, WebSocket binário, mTLS, CAPTCHA e 2FA interativo podem exigir implementação nativa.

## TypeSafe Jev e coordenação de ordens

`typesafe_jev_check` grava aprovações com piso 70, uso único e validade de 5 minutos, por agente/venue/ativo/lado. `wallet_swap` e ferramentas declaradas com `requiresSignal` consomem a aprovação; sem ela a ordem é recusada. Regras automáticas de saída, como SL/TP e trailing stop, seguem as próprias proteções. Execuções na carteira Solana são serializadas por `walletMutex`, cada venue usa `venueLock` e um ativo não é operado por dois agentes ao mesmo tempo. Solana e IQ Option têm capital independente.
