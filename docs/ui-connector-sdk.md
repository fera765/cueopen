# CueOpen UI Connector SDK

O SDK oferece conectores com interface própria: login, painéis, ações do usuário e ferramentas que o agente pode chamar. Há duas formas de implementação: conectores nativos em Kotlin e conectores personalizados declarativos em YAML.

## O que o app gerencia

| Responsabilidade | Componente |
|---|---|
| Formulário de login e tela do conector | `ui/UiConnectorScreen.kt` |
| Cofre local de credenciais lembradas (AES-GCM/Android Keystore) | `CredentialVault.kt` |
| Painéis genéricos | `UiBlock` e `UiBlockView` |
| Habilitação, configurações e seleção por agente | `UiConnectorHub.kt`, `AgentProfile.uiConnectorIds` |
| Ferramentas `uic_<id>_<ferramenta>` | `UiConnectorHub.toolDefinitions` |
| Redação literal de saídas de ferramenta | `Redactor` e `SecretVault` |
| Registro de erros e diagnóstico | `ErrorLog` e `ConnectorDebugLog` — não têm as mesmas garantias de redação |
| Validação de conectores nativos | `SpecValidator` |

### Exposição e login são controles separados

O schema das ferramentas é exposto quando o conector está habilitado e o agente tem o conector selecionado no perfil. Se **Agent required?** estiver desligado, qualquer agente pode usar um conector habilitado. O estado de login e a existência de credenciais salvas não são pré-requisitos para expor o schema.

Ao receber uma chamada `uic_*`, o Hub verifica novamente a habilitação/seleção e então tenta garantir o login. Se a sessão não estiver ativa, credenciais lembradas podem ser usadas para reconectar; sem credenciais salvas, a chamada falha pedindo login. `tools_load` e o planejador contextual do agente podem adiar quais schemas aparecem para economizar tokens, mas não concedem acesso nem substituem a checagem feita pelo Hub.

## Segurança e limites atuais

`risky` é uma marcação de operação sensível e, em alguns casos, um requisito de validação do YAML. **Não é uma confirmação humana automática, não ativa por si só um bloqueio geral de execução e não garante que exista um limite numérico.** Um `ToolSpec.risky` sem limites declarados e sem `opensOrder` não recebe um gate genérico de confirmação no Hub. Limites só existem quando a implementação do conector os aplica; no conector YAML, os limites declarados para ferramentas são verificados em `CustomConnector.callTool`.

Ações de UI (`onAction`) são chamadas reais do conector e não herdam os limites do agente. No renderizador genérico, a confirmação existe para valores de `UiBlock.Segmented.confirmValues`; `UiBlock.Button.risky` altera a apresentação do botão, mas não abre uma confirmação. Para ações HTTP, o parser exige `risky: true` quando o método não é GET, porém isso não acrescenta uma confirmação antes do envio.

O Hub redige o texto devolvido por chamadas de ferramenta substituindo valores literais retornados por `connector.secrets()` (somente valores com pelo menos quatro caracteres); o `SecretVault` também redige segredos resolvidos por `ask_secure` no fluxo normal do agente. Isso **não** é uma garantia universal para todos os logs e telas: `ErrorLog` pode receber a mensagem original de uma exceção antes da redação da saída, e falhas de login/ação podem ser exibidas pela UI conforme retornadas pelo conector. Não coloque credenciais em mensagens de erro ou resultados; os implementadores devem sanitizar cada rota e não presumir que `Redactor` seja um detector genérico de segredos.

Permissões de `agent`, `terminal`, `files` e `browser` são separadas e precisam ser autorizadas pelo usuário. O dry-run do criador não concede essas capacidades.

## Conectores nativos em Kotlin

Conectores nativos implementam `UiConnector`, declaram o contrato em `UiConnectorSpec` e são registrados em `UiConnectorHub.connectors`. A especificação inclui `id`, nome, descrição, versão, campos de login, configurações e ferramentas. `SpecValidator.validate(spec)` valida os specs nativos; se houver campos de login, exige ao menos um campo `PASSWORD`. Testes devem cobrir os handlers e as proteções implementadas por cada conector.

O diretório `uiconnector/iqoption/` implementa um cliente não oficial da IQ Option; o protocolo pode mudar sem aviso. O conector possui limites próprios. As ferramentas de abertura têm `opensOrder`, e o Hub valida o intent e coordena as chamadas por venue e ativo. A marcação `risky`, isoladamente, não significa confirmação humana.

O diretório `uiconnector/gmail/` usa a API oficial do Gmail, OAuth 2.0 com PKCE e redirect loopback. O usuário configura o próprio projeto Google Cloud e o app pode manter o refresh token no cofre cifrado. A marcação `risky` de uma ferramenta de envio não cria, por si só, uma confirmação humana antes de cada envio.

## Conectores personalizados em YAML

Em **Conectores → UI Conectores → Criar conector (SDK)**, a definição começa vazia. O usuário pode escrever YAML ou pedir ao agente que crie, valide, teste offline e proponha a instalação.

O formato-fonte da definição é YAML. O parser produz o modelo tipado interno usado pela execução e pela validação estrutural. Definições antigas em JSON continuam legíveis como formato legado; ao salvar/importar um legado, o app normaliza e persiste a definição em `.yaml`. Outros JSON do produto — payloads de APIs, mensagens de ferramentas, armazenamento e envelope de backup — continuam em seus formatos próprios. O backup das definições permanece um contêiner JSON, sem credenciais.

O parser limita a definição a 64 KB e a profundidade permitida, rejeita chaves duplicadas e tags customizadas, desativa aliases de coleções e exige um mapa na raiz. Evite âncoras e aliases. Coloque entre aspas strings que começam por `{{...}}`, parecem número/booleano/data ou contêm pontuação que possa ser interpretada pelo YAML.

A especificação apresentada no botão **Doc** e ao agente por `connector_spec` fica em [`SdkPrompt.kt`](../app/src/main/java/dev/cueopen/app/uiconnector/custom/SdkPrompt.kt). A implementação é a fonte final para as regras efetivamente aplicadas.

### Campos suportados

A raiz exige `id`, `name` e `description`; `baseUrl` é necessário para rede, mas pode faltar num conector só de cálculo. O `id` tem 2–20 caracteres, começa com letra minúscula e usa apenas letras minúsculas e dígitos. `baseUrl` e `allowedHosts` limitam destinos de rede.

Campos opcionais da raiz: `version`, `accent`, `headers`, `login`, `settings`, `panels`, `actions`, `tools`, `realtime`, `capabilities` e `setup`.

- **Login:** quando `login` é declarado, `fields` deve estar presente e o parser aceita até 8 campos dos tipos `text`, `email`, `password` e `number`. No código atual, uma lista vazia é aceita e não há validação que exija um campo `password`. A validação de campos `required` ocorre em `UiConnectorHub.login`; chamadas diretas a `CustomConnector.login`, como no painel manual **Testar**, não passam por essa validação do Hub. `request`, `extract` e `verify` declaram autenticação; chaves fixas de API também podem não fazer uma requisição de login.
- **Requests:** `method`, exatamente um de `path` ou `url`, e opcionalmente `query`, `headers`, `body` ou `form`. Só HTTPS e hosts declarados; `body` e `form` não coexistem. Respostas JSON, XML/RSS/Atom, JSONP, NDJSON, HTML e texto puro são normalizadas. CSV/TSV só é reconhecido quando o `Content-Type` contém `csv` ou `tab-separated`; sem esse header o corpo não é detectado automaticamente como tabela.
- **Settings:** até 20 opções do usuário do tipo `number`, `switch` ou `text`, com valor padrão e, quando necessário, teto de risco.
- **Panels:** até 10 blocos `header`, `stats`, `table` ou `note`. Tabelas têm até 8 colunas e 100 linhas, e leem uma resposta ou coleção realtime.
- **Actions:** até 10 botões explícitos do usuário. Método diferente de GET requer `risky: true` na validação do YAML. Isso não implementa uma confirmação genérica: a ação da tela instalada chama `onAction` e pode enviar a requisição real.
- **Tools:** até 30 ferramentas com nome snake_case, descrição e até 12 parâmetros tipados. Cada ferramenta usa no máximo uma fonte operacional: `request`, `send`, `source`, `shell`, `file`, `fetch` ou `agent`. `response` e `list` modelam a saída.
- **Limites e ordens:** `limits` vincula parâmetros numéricos a settings numéricas; os limites declarados são verificados na chamada da ferramenta YAML. O parser não exige `limits` para toda ferramenta `risky`, e `risky` não é uma confirmação. Ordens marcadas com `opensOrder` precisam de `orderIntent` válido; o Hub verifica ativo e lado e aplica serialização por venue e reserva temporária por ativo.

### Templates e cálculo

Modelos `{{...}}` acessam somente variáveis disponíveis no contexto da definição: campos de login, valores extraídos, settings, parâmetros, estado realtime, `rid`, `now` e resultados de `compute`. A resposta atual pode ser lida por caminhos como `$.campo[0]` em textos de exibição e respostas, não em host, headers ou URL. Os filtros incluem codificação de URL, conversões numéricas, texto, datas, remoção de HTML, truncamento e Base64.

`compute` executa expressões locais em respostas, settings, estado realtime e parâmetros. Não acessa rede, arquivos ou segredos. Inclui operações de listas e séries, estatística e indicadores técnicos, com limites de operações, cálculos por item e tamanho de lista. Use somente as funções efetivamente implementadas pelo motor.

### Tempo real e capacidades

`realtime` suporta WebSocket `wss://` em host autorizado, mensagens `onOpen`, heartbeat e regras `match`, `set` e `push`. As regras atualizam estado e coleções consultáveis por painéis e ferramentas. `rid` é disponibilizado aos templates, mas não é automaticamente inserido como campo de correlação em toda mensagem. Para aguardar uma resposta correlacionada, a definição deve enviar o valor de `{{rid}}` em um campo que o servidor devolva e configurar `await.match` para esse caminho. O runtime entrega a primeira mensagem que corresponder ao filtro declarado; não faz uma correlação implícita por ID.

`capabilities` declara recursos opcionais do conector. Cada recurso fica desabilitado até autorização do usuário:

- `terminal`: comando no ambiente Alpine/proot exclusivo do conector, com tempo/saída limitados; `setup` prepara dependências uma vez. O ambiente não monta o armazenamento Android nem outros chats/conectores, mas comandos podem ler o próprio sistema Alpine e acessar a internet: não é um sandbox de shell restrito a uma pasta. Argumentos templated são escapados, mas isso não torna seguro código arbitrário.
- `files`: operações `read`, `write`, `list`, `delete` e `replace` no workspace isolado. O parser exige `risky` para escrita, exclusão e substituição; esse flag não é uma confirmação. `replace` altera somente a correspondência esperada; a gravação é atômica. No DSL do conector a edição declarativa é `file.op: replace`; `file_edit` continua como ferramenta interna do agente no workspace da tarefa, fora do esquema do conector.
- `browser`: busca HTTPS de host autorizado e leitura do texto renderizado; não contorna autenticação, CAPTCHA ou controle de acesso.
- `agent`: `tools[].agent` delega `task` ao modelo do agente selecionado (ou ao modelo padrão configurado). Em geral, `maxSteps` é 1–12 e `timeoutSec` 15–900. No template integrado `fluwyopendev`, `desenvolver_ui`, `revisar_ui` e `verificar_projeto` não têm teto local de passos e mantêm o timeout configurado (600 s no template), permissões/revogação e limites nativos do provedor/modelo. O runtime interrompe apenas ciclos sem progresso observável, como repetir consecutivamente a mesma ferramenta com o mesmo erro ou a mesma resposta truncada sem continuação; não impõe limite de passos a esses fluxos. Outros conectores, inclusive cópias com ID diferente, mantêm o limite configurado ou padrão. Não há orçamento cumulativo nem teto interno de tokens; `budgetTokens` de YAML antigo é aceito por compatibilidade, mas ignorado. Quando disponível, o catálogo do provedor informa `context_length` e `max_completion_tokens`; o runtime usa o espaço nativo restante por chamada e compacta turnos antigos de ferramentas para caber na janela. Sem metadados, `max_tokens` é omitido e o próprio provedor/modelo decide. A janela e os limites nativos do modelo continuam sendo o limite real. A tarefa pode interpolar **somente parâmetros declarados na ferramenta**; credenciais de login e settings não são adicionadas ao prompt. O subagente recebe schemas internos `shell`, `file_list`, `file_read`, `file_write` e `file_edit` somente quando `terminal`/`files` também estiverem declarados e autorizados. Cada operação revalida a autorização e o filesystem limita os arquivos ao workspace. Não há browser, ferramentas Android, carteira, outros conectores, `multi_agents` ou herança de ferramentas do agente pai. A tarefa e os dados lidos são enviados ao provedor/modelo configurado e podem consumir cota/custo; não inclua segredos no parâmetro da tarefa.

`agent` precisa de `risky: true`, mas essa marca só sinaliza a operação — não abre confirmação por chamada. Autorize `agent`, terminal e arquivos separadamente; revogue qualquer uma dessas permissões para bloquear os próximos usos. O fluxo padrão serializa tarefas por conector para evitar duas edições simultâneas no mesmo workspace.

O botão **Carregar modelo Fluwy Open Dev** no editor inclui um gerador local de interfaces para criar, revisar e testar projetos frontend. Ele é uma definição SDK comum: não acessa serviço, conta, código ou assets proprietários de terceiros. A geração usa o modelo LLM configurado; arquivos ficam no workspace do conector. O modelo demonstra geração/iteração por arquivos e terminal, não uma prévia visual embutida no app. Já o chat de edição de um conector selecionado pode consultar páginas públicas no browser do próprio chat, em modo sem clique, formulário ou JavaScript; as propostas continuam vinculadas ao ID e fingerprint do conector aberto e só são aplicadas pelo card revisado pelo usuário.

## Validação, teste e instalação

O fluxo do criador de IA é `connector_spec` → `connector_validate` → `connector_test` → `connector_propose`, mas essas etapas têm escopos diferentes:

- `connector_validate` faz parsing e validação estrutural/DSL (campos, tipos, templates e regras do formato). Não executa o conector e não roda `ConnectorGuardrails.staticCheck`.
- `connector_test` executa o dry-run offline do SDK e aí sim aplica os guardrails estáticos. Usa fixtures HTTP/WebSocket, filesystem virtual e respostas simuladas de browser/shell/agent; login usa valores sintéticos. Não abre rede externa, não resolve credenciais reais, não abre páginas, não executa `setup`/shell/código gerado, não chama um modelo real e não envia mensagens, ordens, publicações ou pagamentos. Ferramentas que dependem de shell/setup falham fechadas; uma ferramenta `agent` só valida a estrutura e o contrato com uma resposta mock, sem validar qualidade da LLM nem build real.
- `connector_propose` só cria o cartão **Instalar conector** quando o mesmo YAML passou no dry-run e tem atestado válido. O cartão revalida o atestado/digest antes de permitir o salvamento; a proposta não instala sozinha.
- O botão **Salvar/Criar conector** do editor manual é outro caminho: aceita uma definição estruturalmente válida sem atestado de dry-run. O painel manual **Testar** também não é o `connector_test`: executa login real, consulta painéis e executa ferramentas contra integrações reais. Para uma ferramenta `risky`, a UI mostra um aviso, mas permite executar a chamada real. Esse painel chama `CustomConnector` diretamente, sem passar pela coordenação de ordens do Hub. O painel não executa `actions`; ações podem ser acionadas na tela normal do conector e também fazem chamadas reais.

O atestado do fluxo de IA é vinculado ao SHA-256 canônico do YAML e à versão da política. O mesmo YAML precisa passar no dry-run para ser proposto; alterar a definição exige novo teste. Ele comprova apenas os contratos exercitados localmente — não confirma endpoint ativo, autenticação, permissão ou disponibilidade real do serviço. Essa exigência não se aplica ao salvamento manual descrito acima.

A memoização de `connector_validate`, `connector_test` e `connector_propose` existe apenas em memória durante uma única execução contínua do loop do agente. A assinatura combina nome da ferramenta e argumentos normalizados; a mesma chamada pode reutilizar o resultado nesse loop. O guard de repetição estrita interrompe o ciclo ao atingir a segunda ocorrência. Uma nova execução do loop — por exemplo, uma nova mensagem, ciclo de heartbeat, retomada após cancelamento ou reinício — começa sem essa memoização.

A política de saída `outputPolicy` do tipo `vsl` pode conferir duração estimada, idioma por heurística, ordem de seções e termos proibidos. A contagem não garante duração de locução nem revisa semântica ou fatos.

## Limites de segurança que exigem mudança de código se a política pretendida for mais forte

Os pontos abaixo descrevem o comportamento atual; documentação não os transforma em proteções. Se o produto exigir qualquer proteção mais forte, ela precisa de implementação e testes específicos:

1. `risky` em ferramenta/ação não produz confirmação humana genérica; um botão `risky` só muda a aparência, e `limits` não é obrigatório para toda ferramenta sensível.
2. Ações da UI não passam pelos limites do agente nem por uma coordenação central de ordens.
3. O caminho manual **Testar** executa operações reais fora do Hub; o salvamento manual não exige atestado.
4. A redação não cobre universalmente logs e mensagens da UI: `ErrorLog` pode receber mensagens de exceção sem sanitização central.
5. O parser YAML de login não exige um campo senha nem pelo menos um campo, ao contrário da validação de specs nativos.
6. O terminal Alpine/proot é separado do armazenamento Android e de outros workspaces, mas pode acessar arquivos do próprio sistema Alpine e a internet; o prompt do subagente não é uma barreira de segurança. Use somente conectores/código confiáveis e não confunda a permissão existente de terminal com isolamento por diretório.

Não invente endpoints, não use scraping nem contorne login/CAPTCHA. Serviços sem API pública/autorizada estável devem ser explicados como indisponíveis para este formato. OAuth de consentimento/PKCE, WebSocket binário, mTLS, CAPTCHA e 2FA interativo podem exigir implementação nativa.

## Coordenação de ordens

Ferramentas marcadas com `opensOrder: true` declaram `orderIntent` com o ativo e o lado que a chamada abrirá, além de `risky: true`. O Hub recusa intents inválidos, serializa chamadas no mesmo venue e reserva temporariamente cada ativo para impedir que agentes diferentes operem simultaneamente o mesmo instrumento. Falhas ou recusas liberam as reservas adquiridas; após uma chamada bem-sucedida, elas expiram automaticamente. Isso é coordenação de execução, não confirmação humana, recomendação financeira ou garantia contra perdas.

Execuções da carteira Solana usam `walletMutex`; cada venue tem seu próprio `venueLock`, e Solana e IQ Option mantêm capital e posições independentes. Regras automáticas de saída, como SL/TP e trailing stop, seguem as proteções implementadas pelo respectivo conector.

Não há compatibilidade universal: OAuth com tela de consentimento/PKCE, WebSocket binário, mTLS, CAPTCHA e 2FA interativo podem exigir implementação nativa no SDK. Seja explícito sobre limites e falhas; nunca prometa ausência de bugs ou de perdas.
