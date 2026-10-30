# CueOpen UI Connector SDK

SDK open source para criar **conectores com interface própria** dentro do CueOpen: tela de login,
painéis com dados ao vivo, ações do usuário e **ferramentas que o agente pode chamar**.
Exemplos de uso: IQ Option (incluído), Gmail, Binance, B3, Nubank…

Código: `app/src/main/java/dev/cueopen/app/uiconnector/`

## O que o app faz por você

| Responsabilidade | Onde |
|---|---|
| Formulário de login a partir de `loginFields` | `ui/UiConnectorScreen.kt` |
| Cofre cifrado das credenciais (AES-256-GCM, Android Keystore) | `CredentialVault.kt` |
| Painéis genéricos (cabeçalho, números, tabelas, seletores, botões, avisos) | `UiBlock` + `UiBlockView` |
| Toggle **"Agent required ?"**, **Habilitado** e configurações do conector | `UiConnectorHub.kt` |
| Toggle **por agente** ao criar/editar o agente ("Usar <conector>") | `AgentEditScreen` (`AgentProfile.uiConnectorIds`) |
| Ferramentas `uic_<id>_<ferramenta>` entregues ao LLM | `UiConnectorHub.toolDefinitions` |
| Redação de segredos em toda saída que vai ao LLM | `Redactor` |
| Erros de execução no log de erros (Configurações) | `ErrorLog` (origem `uiconnector`) |
| Validação do contrato (roda em testes e no registro) | `SpecValidator` |

## Regras de acesso do agente

Um agente só enxerga as ferramentas de um conector se **todas** valerem:

1. o conector está **habilitado** e **conectado** (ou tem credenciais no cofre para reconectar);
2. se **"Agent required ?"** estiver **ligado** (padrão): o agente ativou o conector no próprio perfil;
   se estiver **desligado**: qualquer conversa pode usar.

## Segurança (obrigatória para novos conectores)

- Credenciais e tokens **nunca** entram no contexto do LLM. Devolva-os em `secrets()` para o hub
  redigi-los de qualquer saída. Não os coloque em mensagens de erro.
- Ferramentas que movem dinheiro/dados (`risky = true`) devem aplicar **limites configuráveis**
  (`SettingSpec`) e responder `RECUSADO: <motivo>` quando violados — o prompt do agente instrui a
  respeitar essas recusas.
- `onAction` é o usuário tocando na UI; `callTool` é o agente. Só `callTool` passa pelos limites.
- Nada de dados sintéticos: se o serviço falhar, devolva o erro real.

## Criando um conector

1. **Descreva** o conector num `UiConnectorSpec` (id `[a-z0-9]`, campos de login, configurações, ferramentas).
2. **Implemente** `UiConnector`:

```kotlin
class MeuConector : UiConnector {
    override val spec = UiConnectorSpec(
        id = "meuservico", name = "Meu Serviço", description = "O que faz", version = "1.0.0",
        loginFields = listOf(
            LoginField("email", "E-mail", FieldType.EMAIL),
            LoginField("password", "Senha", FieldType.PASSWORD),   // obrigatório: ao menos um PASSWORD
        ),
        settings = listOf(SettingSpec("max_valor", "Valor máximo (agente)", "Teto por operação", SettingType.NUMBER, "10")),
        tools = listOf(ToolSpec("saldo", "Consulta o saldo")),
    )
    override val isLoggedIn get() = token != null
    override suspend fun login(credentials: Map<String, String>): Result<Unit> = TODO()
    override suspend fun logout() { token = null }
    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> = listOf(
        UiBlock.Header("Meu Serviço", "conta@exemplo.com", "ONLINE", Tone.GOOD),
        UiBlock.Stats(listOf(Stat("Saldo", "R$ 10,00"))),
    )
    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings) = Result.success("ok")
    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String = when (name) {
        "saldo" -> "R$ 10,00"
        else -> "Ferramenta desconhecida: $name"
    }
    override fun secrets() = listOfNotNull(token)
}
```

3. **Registre** em `UiConnectorHub.connectors`.
4. **Valide**: `SpecValidator.validate(spec)` precisa devolver lista vazia — adicione o teste
   (veja `SpecValidatorTest`, que também garante que toda ferramenta declarada tem handler).

## Blocos de UI disponíveis

`Header` · `Stats` · `Table` · `Segmented` (com confirmação opcional, ex.: trocar para conta real) ·
`Button` · `Note`. O painel é reconsultado a cada 5 s enquanto a tela está aberta.

## Conector IQ Option

`uiconnector/iqoption/` — a IQ Option **não tem API oficial**: o protocolo (login HTTP + WebSocket)
segue o que clientes open source documentam e pode mudar sem aviso. Por isso:
`IqProtocol.kt` é puro e testado (mensagens e regras), e qualquer resposta inesperada vai para o
log de erros com os nomes das últimas mensagens recebidas, para diagnóstico.

Limites do agente: conta **REAL** só se liberada nas configurações; valor máximo por operação;
máximo de operações abertas. "Operações em execução" lista as operações abertas por este app,
fechadas por eventos reais do servidor.

---

## Conector Gmail (OAuth)

`uiconnector/gmail/` — API oficial do Gmail com OAuth 2.0 + PKCE e redirect loopback (`127.0.0.1`).
O usuário cria, no **próprio** projeto do Google Cloud, a Gmail API e um *ID do cliente OAuth* do tipo
**App para computador**, e informa client ID e secret. O login abre a tela oficial do Google; o app guarda
só o **refresh token** no cofre cifrado (`persistedCredentials`). Escopos: `gmail.readonly` e `gmail.send`.
Ferramentas: `unread`, `search`, `read`, `labels` e `send` (sensível: só com "Agente pode ENVIAR" ligado,
confirmação e teto diário). Em projetos em modo *Teste* o Google expira a autorização em 7 dias.

---

## Criar conectores na própria app (definição JSON)

Em **Conectores → UI Conectores → Criar conector (SDK)** o usuário escreve (ou pede à IA) uma definição JSON.
O editor valida ao vivo, permite **testar** login/painéis/ferramentas e só então salva. Nada é executado no
aparelho: a definição só faz requisições **HTTPS** e conexões **WSS** aos hosts de `baseUrl`/`allowedHosts` (porta 443,
sem IP/localhost/rede privada, sem redirecionamentos, resposta HTTP ≤ 2 MB em 20 s, mensagem WebSocket ≤ 1 MB).
Conectores em **tempo real** (corretoras de ações/Forex/cripto, cotações e saldo ao vivo, ordens com resposta
por `request_id`) usam o bloco `realtime` — a mesma arquitetura do conector IQ Option, declarativa.
A especificação completa (a mesma do botão **Doc** do editor) está em `SdkPrompt.kt`.

```json
{
  "id": "minhaapi", "name": "Minha API", "description": "…", "version": "1.0", "accent": "#FF6B3D",
  "baseUrl": "https://api.exemplo.com",
  "headers": { "Authorization": "Bearer {{token}}" },
  "login": {
    "fields": [ { "id": "email", "type": "email", "label": "E-mail" }, { "id": "password", "type": "password", "label": "Senha" } ],
    "request": { "method": "POST", "path": "/auth/login", "body": { "email": "{{email}}", "password": "{{password}}" } },
    "extract": { "token": "$.token" },
    "verify": { "path": "/me" }
  },
  "settings": [ { "id": "maxValor", "label": "…", "description": "…", "type": "number", "default": "10", "risky": true } ],
  "panels": [
    { "type": "header", "title": "{{$.name}}", "request": { "path": "/me" } },
    { "type": "stats", "request": { "path": "/summary" }, "items": [ { "label": "Total", "value": "{{$.total|num:2}}", "tone": "good" } ] },
    { "type": "table", "title": "Itens", "request": { "path": "/items" }, "rows": "$.items[*]", "columns": [ { "title": "Nome", "value": "{{$.name}}" } ] },
    { "type": "note", "text": "Texto", "tone": "warn" }
  ],
  "actions": [ { "id": "refresh", "label": "Atualizar", "request": { "path": "/refresh" } } ],
  "tools": [
    { "name": "enviar", "description": "…", "risky": true,
      "params": [ { "name": "valor", "description": "…", "type": "number" } ],
      "limits": [ { "param": "valor", "maxSetting": "maxValor" } ],
      "request": { "method": "POST", "path": "/send", "body": { "amount": "{{valor|num}}" } },
      "response": "Enviado: {{$.id}}" }
  ]
}
```

* **Variáveis** `{{x}}`: campos de login, valores de `extract` (`$.caminho`, `header:Nome`, `cookie:Nome`),
  parâmetros da ferramenta e `{{s.<setting>}}`. `{{$.a.b[0]}}` lê a resposta JSON atual (só `.nome`, `['nome']`, `[n]`, `[-1]`, `[*]`).
* **Filtros**: `url`, `json`, `num[:N]`, `int`, `upper`, `lower`, `trim`, `default:X`, `date`, `pct:N`, `bool`, `raw`.
  Um valor único `"{{v|num}}"` no corpo vira número JSON.
* **Segurança**: senhas e valores extraídos nunca vão ao LLM (redigidos); métodos ≠ GET exigem `"risky": true`;
  `limits` impõe teto numérico vindo das configurações do usuário; `login.request` é opcional (API key); o agente só
  usa o conector se o usuário ativar (“Agent required ?” + perfil do agente).
* **Ordens**: ferramenta com `"requiresSignal": true` + `"orderIntent": {"asset": "{{par}}", "direction": "{{lado}}"}`
  só executa após validação TypeSafe Jev do ativo/lado (uso único, 5 min) — igual ao IQ Option.

### Computação (`compute`)

Painéis e ferramentas aceitam `"compute": {"nome": "expressão"}` — um motor de expressões puro (sem rede, arquivos
ou segredos; orçamento de 3 milhões de operações) com aritmética, lógica, listas, objetos, lambdas
(`map/filter/reduce/sort_by/zip_rows`) e uma biblioteca de análise técnica no padrão TradingView (SMA, EMA, WMA, HMA,
VWMA, ALMA, KAMA, RSI, Estocástico, Stoch RSI, MACD, PPO, CCI, Williams %R, MFI, ROC, Momentum, TRIX, CMO, DPO, TSI,
AO, UO, Fisher, ATR, Bollinger, Keltner, Donchian, ADX/DI, Aroon, PSAR, Supertrend, Ichimoku, Choppiness, Elder,
Pivôs, OBV, VWAP, A/D, CMF, Heikin Ashi…). Resultados: `{{c.nome}}` nos modelos e `"rows": "c.nome"` em tabelas/listas.
O modelo "Indicadores técnicos calculados" do editor monta mais de 30 indicadores com sinal e resumo técnico sobre
velas reais da Binance.

### Tempo real (`realtime`)

```json
"realtime": {
  "url": "wss://ws.suacorretora.com/stream",
  "connect": "login",
  "onOpen": [ { "type": "auth", "token": "{{token}}" }, { "type": "subscribe", "channels": ["balance", "quotes"] } ],
  "heartbeat": { "everySec": 20, "message": { "type": "ping" } },
  "rules": [
    { "match": { "$.type": "balance" }, "set": { "saldo": "$.data.amount" } },
    { "match": { "$.type": "quote" }, "push": { "to": "cotacoes", "item": "$.data", "key": "$.symbol", "max": 50 } }
  ]
}
```

- Conecta ao entrar (`connect: "login"`) ou no primeiro uso (`"auto"`, fontes públicas); envia `onOpen`, mantém
  `heartbeat` e reconecta sozinha (até 5 tentativas com espera crescente; depois, no próximo uso).
- Cada mensagem recebida passa pelas `rules`: `set` grava o **estado** (`{{rt.saldo}}`, além de `{{rt.connected}}` e
  `{{rt.messages}}`); `push` mantém **coleções** (mais recentes primeiro; `key` substitui o item com a mesma chave).
- Painéis e ferramentas leem coleções com `"source": "cotacoes"` (`"rows": "$.items[*]"`) e o estado com `{{rt.…}}`.
- Ferramenta com `send` envia uma mensagem e, com `await.match` (ex.: `{"$.request_id": "{{rid}}"}`), espera a resposta
  correspondente; `{{rid}}` é único por chamada. Ordens via `send` seguem as mesmas proteções: `risky` (confirmação),
  `limits` (teto do usuário) e `requiresSignal` + `orderIntent` (validação TypeSafe Jev + coordenação entre agentes).
- O criador com IA só libera a instalação depois de `connector_test` passar em um dry-run offline; esse teste não prova acesso, login ou disponibilidade do serviço real.

## Guardrails do criador com IA (validação antes da instalação)

Um conector criado pelo chat **Criar com a IA** só chega ao card de instalação depois de passar em `connector_test`
com o mesmo JSON. O resultado é vinculado por SHA-256 canônico à definição e guardado no próprio card; JSON alterado,
proposta antiga sem prova ou versão de política incompatível não mostra botão de instalação.

O pipeline usa parser e guardrails reais do SDK, mas substitui toda integração externa por fixtures locais:

1. **Schema e segurança estática:** parser, templates, hosts, HTTPS/WSS, declarações de capacidades e
   `ConnectorGuardrails.staticCheck` são verificados. URLs e hosts são conferidos sem abrir socket externo.
2. **Requests/painéis/ferramentas:** HTTP e WebSocket recebem respostas determinísticas de mocks; os templates,
   tabelas e saídas são renderizados. Login, quando declarado, usa valores sintéticos descartáveis — nunca pede nem
   resolve credenciais reais. Ações, ordens e mensagens de envio não são reproduzidas.
3. **Filesystem/capacidades:** operações `file` usam um filesystem virtual e gravação seguida de leitura é conferida.
   Browser é substituído por fixture; nenhuma página é aberta. Qualquer ferramenta com `shell` ou qualquer `setup`
   falha fechado, porque não há executor que comprove isolamento de rede/IO para código arbitrário. Nenhum comando,
   script ou código gerado é executado.
4. **Gate de instalação:** `connector_propose` exige o atestado atual para o mesmo JSON; o card revalida o SHA-256
   antes de exibir e novamente antes de salvar. O card avisa que serviço/login real e execução de capacidades ficam
   para depois da instalação e da autorização do usuário.

Não coloque segredos fixos no JSON. `connector_test` não precisa de `ask_secure`, `login_json` nem token real.
O teste offline não comprova que uma API aceita as credenciais, que um endpoint ainda existe, nem que uma permissão
foi concedida. Para fontes sem API pública e autorizada, não use scraping, CAPTCHA ou contorno de login; explique a
limitação e não proponha o conector. Em especial, o dry-run não pode provar autorização da Meta para a Biblioteca de
Anúncios, então essa fonte fica bloqueada até existir alternativa autorizada validável.

## Política de saída para copy/VSL

Uma ferramenta de geração textual pode declarar `outputPolicy` para o app recusar saídas fora do contrato. A requisição
precisa enviar duração, idioma e as instruções de guardrail; o texto final (`response`) é medido depois de renderizar:

```json
"outputPolicy": {
  "type": "vsl",
  "durationParam": "duracao_minutos",
  "languageParam": "idioma",
  "guardrailsParam": "guardrails",
  "forbiddenTermsParam": "termos_proibidos",
  "wordsPerMinute": 130,
  "tolerance": 0.20,
  "requiredSections": ["Abertura", "Problema", "Solução", "Oferta", "Chamada para ação"]
}
```

`durationParam` deve ser numérico (1–240 min), `languageParam` e `guardrailsParam` texto, e pelo menos 3 seções
são obrigatórias. A contagem estimada usa palavras/minuto e tolerância configurada; o idioma é uma heurística local
para português, inglês, espanhol ou francês; cabeçalhos explícitos são conferidos na ordem declarada e `forbiddenTermsParam` bloqueia termos separados
por vírgula/ponto e vírgula/linha. Isso não garante tempo exato de fala, não interpreta todas as instruções em linguagem
natural e não faz revisão factual/semântica. O serviço gerador ainda precisa ser autorizado e testado fora do dry-run.

Em produção cada comando de conector roda isolado: `cd /root/workspace; bash -c '<run>' < /dev/null` — processo
próprio (cd/export/exit não vazam para a próxima chamada nem matam a sessão do terminal) e sem stdin.

## Validação TypeSafe Jev e coordenação (imposto em código)

`typesafe_jev_check` grava aprovações (piso 70, uso único, 5 min, por agente/venue/ativo/lado). `wallet_swap` (compra e venda)
e as ferramentas `requiresSignal` consomem a aprovação; sem ela a ordem é recusada. Regras automáticas (`trade_set_rule`) são só de saída
(SL/TP) e dispensam validação, assim como o trailing stop. Execuções na carteira Solana são serializadas (`walletMutex`),
cada conector tem uma ordem por vez (`venueLock`) e um ativo não é operado por dois agentes ao mesmo tempo (`claim`).
Solana e IQ Option têm capital independente.
