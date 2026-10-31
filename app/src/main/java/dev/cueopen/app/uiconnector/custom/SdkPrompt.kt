package dev.cueopen.app.uiconnector.custom

/**
 * Especificação do formato YAML de UI Connectors, usada no botão Doc e pela ferramenta connector_spec.
 * Distingue as regras do parser, os guardrails do dry-run e as rotas de execução real.
 */
object SdkPrompt {
    private const val D = "$"

    val MARKDOWN: String = """
# CueOpen UI Connector SDK — definições YAML

Você cria UI Connectors declarativos para o CueOpen. Produza uma única definição YAML com um mapa na raiz. Não inclua código executável fora dos campos declarativos permitidos, não invente endpoints e não insira segredos. O app valida a definição com o parser real; erros de sintaxe, campos ausentes ou valores fora das regras impedem a instalação.

O editor começa vazio e oferece o modelo local Fluwy Open Dev como exemplo. Esta especificação descreve o contrato, não substitui pesquisa na documentação oficial do serviço.

## YAML aceito

Use pares `chave: valor`, listas com `-` e indentação consistente. Duplicatas de chave e tags customizadas são rejeitadas; aliases de coleções estão desativados. Evite âncoras e aliases. O documento tem limite de 64 KB, profundidade limitada e deve ter um mapa na raiz. Coloque entre aspas valores que comecem com `{{`, contenham `: `, pareçam booleanos/números/datas ou precisem permanecer texto. Strings multilinha são aceitas pelo parser YAML; prefira aspas e blocos YAML adequados em vez de escapar como JSON.

Definições JSON já existentes também podem ser lidas como legado; ao salvar, o app grava YAML. Essa compatibilidade não transforma JSON em formato recomendado. O JSON usado pelo protocolo das APIs, backups e chamadas de ferramentas permanece JSON quando aplicável; só a definição declarativa do conector muda para YAML.

## Raiz

| Campo | Tipo | Regra |
|---|---|---|
| `id` | texto | Obrigatório; 2–20 caracteres, começa com letra minúscula e contém apenas `a-z` e `0-9`. Sem `_` ou `-`. |
| `name` | texto | Obrigatório; nome exibido. |
| `description` | texto | Obrigatório; descreva o propósito em uma frase. |
| `version` | texto | Opcional; padrão `1.0`. |
| `accent` | texto | Opcional; cor `#RRGGBB`. |
| `baseUrl` | URL | Obrigatória para rede; pode ser omitida em conector somente de cálculo. Exige `https://`, domínio público, porta 443 implícita e sem query/credenciais/IP/rede privada. Caminho prefixo é permitido. |
| `allowedHosts` | lista de domínios | Opcional; até 8 hosts HTTPS extras, declarados sem esquema, porta ou caminho. |
| `headers` | mapa texto→texto | Cabeçalhos padrão das requisições, exceto `login.request`. |
| `login` | mapa | Opcional; omita em fontes públicas. Veja Login. |
| `settings` | lista | Até 20 preferências do usuário. |
| `panels` | lista | Até 10 blocos de UI. |
| `actions` | lista | Até 10 ações que o usuário toca. |
| `tools` | lista | Até 30 ferramentas do agente. |
| `realtime` | mapa | Opcional; conexão WebSocket `wss://`. |
| `capabilities` | lista de texto | Opcional; `agent`, `terminal`, `files` e/ou `browser`. Cada capacidade fica desligada até autorização do usuário. |
| `setup` | mapa | Opcional; preparação única do terminal, com `run` e `timeoutSec` de 1–900; exige `terminal`. |

Toda chamada de rede é restrita ao host de `baseUrl` e aos de `allowedHosts`. HTTPS/WSS apenas, porta 443, sem redirecionamento, sem endereço privado. Limites de resposta: HTTP até 2 MB/20 s e mensagem realtime até 1 MB.

## Login e requisições

Quando `login` é declarado, `fields` precisa estar presente e o parser aceita até 8 campos com `id`, `label` e `type` (`text`, `email`, `password`, `number`); `required` e `hint` são opcionais. O parser atual aceita lista vazia e não exige um campo `password`. O Hub verifica `required` no login pela tela normal; o painel manual Testar chama o conector diretamente e não passa por essa checagem. O `id` segue `[a-z][a-zA-Z0-9_]{0,31}`.

`login.request` e `login.verify` usam o mesmo contrato de requisição. `request` aceita `method` (GET, POST, PUT, PATCH, DELETE; padrão GET), e exatamente um entre `path` e `url`. `path` começa com `/` e é relativo a `baseUrl`; `url` deve ser HTTPS, fixa e em host permitido. `query`, `headers`, `body` e `form` são opcionais; `body` e `form` não podem coexistir. `form` envia `application/x-www-form-urlencoded`. `body` é dado JSON estruturado e pode conter modelos de texto; em GET é ignorado. Valores de query são codificados automaticamente.

`login.extract` mapeia nomes para campos da resposta, `header:Nome` ou `cookie:Nome`, e só pode existir com `login.request`. `login.verify` é opcional e deve devolver 2xx. Chaves fixas de API podem usar um campo de senha sem `login.request`.

Respostas HTTP reconhecidas: JSON; XML/RSS/Atom convertido em árvore (atributos como `@nome`, texto como `#text`, elementos repetidos como listas); JSONP; NDJSON como lista; CSV/TSV em `rows` quando o `Content-Type` contém `csv` ou `tab-separated`; HTML com `body`, `title` e texto sem tags; texto puro em `body`. Sem esse header, CSV/TSV não é detectado pelo conteúdo e cai no tratamento de texto. Um índice `[*]` em lista com um único item devolve esse próprio item.

## Settings, painéis e ações

Uma setting exige `id` e `label`; aceita `description`, `type` (`number`, `switch`, `text`), `default` e `risky`. `default` é texto; para switch use `true` ou `false`. `enabled` e `agentrequired` são IDs reservados. Acesse uma setting com `{{s.id}}`.

Tipos de painel: `header`, `stats`, `table` e `note`. Textos leem resposta com modelos e estado realtime com `{{rt.nome}}`. `header` pode ter `title`, `subtitle`, `badge`, `tone` e requisição. `stats` exige até 20 itens com `label` e `value`. `table` exige `rows` e até 8 `columns`; limite de 100 linhas; a tabela precisa de `request` ou `source`, nunca os dois. `note` usa `text`. Tons aceitos: `neutral`, `good`, `bad`, `warn`.

Ação do usuário exige `id`, `label` e `request`; pode ter `risky`, `message` e os outros campos suportados pelo contrato de requisição. Qualquer método diferente de GET exige `risky: true` na validação, mas essa marca não pede confirmação. A ação acionada na tela normal chama `onAction` e pode fazer uma requisição real.

## Ferramentas do agente

Cada ferramenta exige `name` em snake_case (regex `^[a-z][a-z0-9_]{1,31}${'$'}`) e `description`. `params` aceita até 12 parâmetros; cada um requer `name` e `description`, com `type` opcional (`string`, `integer`, `number`, `boolean`), `required` padrão true e `enum` opcional.

Uma ferramenta usa no máximo uma fonte entre `request`, `send`, `source`, `shell`, `file`, `fetch` e `agent`. Sem fonte, só é válida com `realtime` e `response` para ler o estado. `response` modela o texto devolvido; `list` transforma linhas com `rows`, `item` e `max` de 1–200. `compute` pode acompanhar painéis e ferramentas.

`agent` delega uma tarefa à LLM configurada para o agente selecionado (ou ao modelo padrão do app). O bloco aceita `task`, `maxSteps` (1–12, padrão 6) e `timeoutSec` (15–900, padrão 300). Exceção exclusiva ao ID integrado `fluwyopendev`: os agentes `desenvolver_ui`, `revisar_ui` e `verificar_projeto` do template Fluwy Open Dev não têm teto local de passos; continuam sujeitos ao timeout configurado (600 s no template), permissões/revogação e limites nativos do provedor/modelo. Outros conectores, inclusive cópias com ID diferente, mantêm o limite configurado ou padrão. Não há orçamento cumulativo nem teto interno de tokens; não gere `budgetTokens` em novos conectores. O YAML legado pode conter esse campo, mas ele é ignorado. Quando o catálogo informa `context_length` e `max_completion_tokens`, o runtime usa o espaço nativo disponível por chamada e compacta turnos antigos de ferramentas para caber no contexto. Se não houver metadados do modelo, `max_tokens` é omitido para o provedor/modelo decidir; a janela nativa do modelo continua sendo o limite real. O prompt aceita somente os parâmetros declarados nessa ferramenta; não interpolar credenciais de login ou settings. A ferramenta precisa de `risky: true`, que é somente uma marcação e não uma confirmação humana.

A capability `agent` exige autorização separada. O subagente recebe apenas `shell` se `terminal` também foi declarado e autorizado, e `file_list`, `file_read`, `file_write`, `file_edit` se `files` também foi declarado e autorizado. O runtime revalida permissões antes de cada chamada; paths de arquivos são relativos e confinados ao workspace. Não há browser, ferramentas do aparelho, carteira, outros conectores, `multi_agents` ou herança das ferramentas gerais do agente pai. A tarefa e o conteúdo que o subagente ler são enviados ao provedor LLM configurado e podem consumir cota/custo. Não inclua segredos no pedido.

`limits` liga um parâmetro numérico a uma setting do tipo number; no conector YAML, valores acima do teto ou menores/iguais a zero são recusados quando a ferramenta é executada. O parser não exige `limits` para toda ferramenta `risky`, e `risky` não ativa confirmação genérica. Operações que enviam ordem, compra, venda, saque, transferência ou publicação devem ser marcadas `risky: true`. Ordens usam `requiresSignal: true`, `orderIntent.asset` e `orderIntent.direction` derivados somente dos parâmetros da ferramenta, além dos limites que a definição declarar; a direção final precisa resultar em `buy`, `sell`, `call` ou `put`. O Hub exige validação TypeSafe Jev se `require_jev` estiver ausente ou ligado; com `require_jev: false`, pula esse gate, mantendo a coordenação da ordem. Nunca descreva um mecanismo de proteção como garantia contra perdas.

## Realtime / WebSocket

`realtime.url` é `wss://` para host fixo permitido, porta 443. `headers`, `connect`, `onOpen`, `heartbeat` e `rules` são opcionais. `connect` aceita `login` (padrão) ou `auto` para fonte pública. `onOpen` aceita até 10 mensagens. Heartbeat aceita `everySec` entre 5 e 300 e uma `message`. Há até 30 regras.

Cada regra pode combinar até 6 condições em `match` e deve incluir `set` e/ou `push`. `set` atualiza estado lido como `{{rt.nome}}`; `connected` e `messages` são reservados. `push` atualiza uma coleção com `to`, `item`, `key` e `max` de 1–500; a coleção mantém os itens recentes primeiro e substitui a mesma chave. Painéis/ferramentas consomem coleção por `source` e `rows`. Mensagens recebidas podem ser JSON, XML ou texto. Ferramentas `send` enviam mensagem JSON estruturada ou texto e podem aguardar a primeira resposta que satisfaça `await.match`, em 1–60 s. `{{rid}}` é gerado e disponibilizado aos templates, mas não é injetado automaticamente como campo de correlação em toda mensagem. Para correlacionar, inclua `{{rid}}` no campo que o servidor ecoará e configure `await.match` para esse caminho. `{{now}}` é epoch em milissegundos.

## Templates `{{ }}`

Variáveis disponíveis dependem do lugar: login fields em `login.request`; credenciais e `extract` em headers/verify; também settings em painéis/ações; parâmetros em ferramentas; `rt.*` em realtime; `rid` e `now` nas mensagens de realtime; somente parâmetros em `orderIntent`. Variável desconhecida é erro.

`{{c.nome}}` acessa um resultado de `compute`. `{{${D}.a.b[0]}}` lê uma resposta em textos de painel, `message` ou `response`, não em `path`, `query`, `headers` ou `body`. Caminhos permitem `.nome`, `['nome com ponto']`, `[n]`, `[-1]`, `[*]` e `.*`; não aceitam `..` nem filtros `?()`.

Filtros encadeáveis: `url`, `json`, `num`/`num:N` (N 0–8), `int`, `upper`, `lower`, `trim`, `default:X`, `date`, `pct:N`, `bool`, `raw`, `strip`, `trunc:N` e `base64`. Em body, valores isolados com `num`, `int`, `bool` ou `raw` podem virar número, booleano ou JSON estruturado. Use `url` em valores inseridos em path.

## Compute

`compute` avalia expressões no app sobre resposta, settings, estado realtime e parâmetros; os cálculos são ordenados e os seguintes acessam os anteriores por `c.nome`. Não acessa rede, arquivos nem segredos. Suporta números, strings, booleanos, null, operadores aritméticos/lógicos, condicionais, listas, mapas, indexação, lambdas, operações de lista e até 80 cálculos por item, com teto de 3 milhões de operações e listas de até 200 mil itens.

Funções incluem matemática/conversão (`abs`, `round`, `sqrt`, `num`, `int`, `str`, `bool`, `coalesce`); texto (`upper`, `lower`, `trim`, `replace`, `split`, `join`, `regex`, `date`, `now`); listas/séries (`map`, `filter`, `reduce`, `sort_by`, `zip_rows`, `group_by`, `rolling`, `crossover`, `crossunder`, entre outras); e indicadores técnicos (`sma`, `ema`, `rsi`, `macd`, `bollinger`, `stoch`, `adx`, `ichimoku`, `supertrend`, `psar`, `vwap` e demais funções do motor do app). `indicators(...)` prepara um resumo técnico. Não invente funções: use somente as funções implementadas no SDK.

## Capacidades do conector

As capacidades exigem declaração na raiz e autorização do usuário na tela do conector; ficam desativadas por padrão. Cada conector tem workspace isolado. O dry-run não autoriza nem executa capacidade real.

- `terminal`: cada ferramenta usa `shell.run`, opcionalmente `timeoutSec` de 1–300 e `files` para gravar arquivos antes do comando. Argumentos `{{...}}` entram com escape/aspas; isso não torna scripts arbitrários seguros. O terminal dedicado não monta armazenamento Android nem outros chats/conectores, mas pode ler o próprio sistema Alpine e acessar a internet; não é um sandbox de shell restrito só ao workspace. Não use heredoc, nem echo/printf multilinha; para scripts use arquivos declarativos. `setup` roda uma vez ou após mudança de comando.
- `files`: `file.op` aceita `read`, `write`, `list`, `delete` e `replace`. Caminhos são relativos ao workspace, sem `/` inicial ou `..`. `write`, `delete` e `replace` precisam de `risky: true` na definição, mas esse flag não é uma confirmação. `replace` usa `oldText`, `newText` e `allowMultiple`; sem correspondência única, não altera. A atualização é atômica. No DSL do conector, a edição declarativa é somente `file.op: replace`; `file_edit` é uma ferramenta interna do agente no workspace da tarefa e não faz parte deste esquema.
- `browser`: `fetch` abre HTTPS de host permitido, espera 0–10 s e retorna texto renderizado. Não contorna login, captcha ou controles de acesso.
- `agent`: a tarefa passa pelo modelo configurado e pode consumir cota/custo; o dry-run simula a resposta, sem chamar a LLM real ou executar terminal/arquivos. O prompt não é uma barreira de segurança; autorize apenas projetos confiáveis.

Uma ferramenta usa apenas uma fonte operacional por vez. `shell`, `file` e `fetch` não rodam no teste offline do criador; `agent` usa resposta simulada e não valida qualidade do modelo ou a execução real do projeto.

## Política de saída VSL/copy

Ferramentas podem declarar `outputPolicy` do tipo `vsl`, com parâmetros de duração, idioma, instruções e termos proibidos. São exigidas 3–12 seções, `wordsPerMinute` de 80–240 e `tolerance` de 0.05–0.50. A validação verifica estimativa de palavras, idioma por heurística local (PT/EN/ES/FR), cabeçalhos na ordem indicada e termos proibidos. Isso não garante duração oral exata nem revisão semântica ou factual.

## Guardrails, validação e instalação

`connector_validate` faz parsing e validação estrutural/DSL (campos, tipos, templates e regras do formato). Não executa o conector nem roda `ConnectorGuardrails.staticCheck`. `connector_test` executa o dry-run offline e aplica os guardrails estáticos: usa respostas HTTP/WebSocket, filesystem e browser substitutos e valores sintéticos de login. Não abre rede, não resolve credenciais reais, não abre páginas, não envia ações/ordens/publicações/pagamentos e não executa comandos, setup ou código gerado. Ferramentas que dependem de shell/setup falham fechadas. O relatório comprova apenas os contratos exercitados localmente, não endpoint ativo, login, permissão ou comportamento ao vivo.

`connector_propose` só cria o cartão de instalação para o mesmo YAML aprovado no dry-run e com atestado válido; o cartão revalida esse atestado antes de permitir salvar. Isso vale para o fluxo do criador de IA. O botão Salvar/Criar do editor manual aceita YAML estruturalmente válido sem atestado. O painel manual Testar é diferente de `connector_test`: faz login real, consulta painéis e permite executar ferramentas em integrações reais; `risky` mostra aviso, mas não bloqueia a execução. Esse painel chama o conector diretamente, sem o gate TypeSafe Jev do Hub, e não executa as `actions` declaradas. Ações acionadas na tela instalada também podem fazer chamadas reais.

O atestado do fluxo de IA é vinculado ao SHA-256 canônico do YAML e à versão da política; alteração da definição exige novo teste. Não peça credenciais para validar, não passe segredos em `settings_yaml`/`args_yaml` e não afirme que o serviço foi conectado. O `Redactor` substitui valores literais listados pelo conector na saída de ferramenta, mas não sanitiza universalmente `ErrorLog` nem mensagens de login/ação da UI; nunca inclua segredos em erros. Se um serviço não oferecer API pública/autorizada estável, não invente endpoint nem proponha scraping ou contorno de captcha/login. Mocks não comprovam autorização da Meta Ads Library.

Não há compatibilidade universal: OAuth com tela de consentimento/PKCE, WebSocket binário, mTLS, captcha e 2FA interativo podem exigir implementação nativa no SDK. Seja explícito sobre limites e falhas; nunca prometa ausência de bugs ou de perdas.
""".trimIndent()
}
