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

## Criar conectores na própria app (definição JSON)

Em **Conectores → UI Conectores → Criar conector (SDK)** o usuário escreve (ou pede à IA) uma definição JSON.
O editor valida ao vivo, permite **testar** login/painéis/ferramentas e só então salva. Nada é executado no
aparelho: a definição só faz requisições **HTTPS** ao host de `baseUrl` (porta 443, sem IP/localhost/rede privada,
sem redirecionamentos, resposta ≤ 256 KB, 20 s). Protocolos que não são REST (WebSocket, SDKs) usam a API Kotlin
acima (como o conector IQ Option).

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

## Validação TypeSafe Jev e coordenação (imposto em código)

`typesafe_jev_check` grava aprovações (piso 70, uso único, 5 min, por agente/venue/ativo/lado). `wallet_swap` (compra e venda)
e as ferramentas `requiresSignal` consomem a aprovação; sem ela a ordem é recusada. Regras automáticas (`trade_set_rule`) são só de saída
(SL/TP) e dispensam validação, assim como o trailing stop. Execuções na carteira Solana são serializadas (`walletMutex`),
cada conector tem uma ordem por vez (`venueLock`) e um ativo não é operado por dois agentes ao mesmo tempo (`claim`).
Solana e IQ Option têm capital independente.
