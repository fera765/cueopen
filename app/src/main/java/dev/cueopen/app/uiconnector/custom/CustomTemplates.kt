package dev.cueopen.app.uiconnector.custom

/** Modelos iniciais do editor. Todos são validados nos testes (zero erros). Troque os hosts/caminhos pelos da sua API. */
object CustomTemplates {
    data class Entry(val title: String, val json: String)

    val TOKEN_LOGIN = Entry("API com login (e-mail/senha → token)", """
{
  "id": "minhaapi",
  "name": "Minha API",
  "description": "Conector REST com login por e-mail e senha.",
  "version": "1.0",
  "baseUrl": "https://api.exemplo.com",
  "headers": { "Authorization": "Bearer {{token}}" },
  "login": {
    "fields": [
      { "id": "email", "label": "E-mail", "type": "email" },
      { "id": "password", "label": "Senha", "type": "password" }
    ],
    "request": { "method": "POST", "path": "/auth/login", "body": { "email": "{{email}}", "password": "{{password}}" } },
    "extract": { "token": "${'$'}.token" },
    "verify": { "method": "GET", "path": "/me" }
  },
  "settings": [
    { "id": "maxValor", "label": "Valor máximo por envio", "description": "Teto que o agente pode enviar por chamada.", "type": "number", "default": "10", "risky": true }
  ],
  "panels": [
    { "type": "header", "title": "{{${'$'}.name}}", "subtitle": "{{${'$'}.email}}", "request": { "path": "/me" } },
    { "type": "table", "title": "Itens", "request": { "path": "/items" }, "rows": "${'$'}.items[*]",
      "columns": [ { "title": "Nome", "value": "{{${'$'}.name}}" }, { "title": "Valor", "value": "{{${'$'}.value|num:2}}" } ],
      "empty": "Nenhum item." }
  ],
  "actions": [],
  "tools": [
    { "name": "listar_itens", "description": "Lista os itens da conta.", "request": { "path": "/items" } },
    { "name": "enviar", "description": "Envia um valor para um destinatário.", "risky": true,
      "params": [
        { "name": "destino", "description": "Identificador do destinatário" },
        { "name": "valor", "description": "Valor a enviar", "type": "number" }
      ],
      "limits": [ { "param": "valor", "maxSetting": "maxValor" } ],
      "request": { "method": "POST", "path": "/send", "body": { "to": "{{destino}}", "amount": "{{valor|num}}" } },
      "response": "Enviado: {{${'$'}.id}}" }
  ]
}
""".trimIndent())

    val API_KEY = Entry("API com chave (sem login)", """
{
  "id": "minhachave",
  "name": "API com chave",
  "description": "Conector REST autenticado por chave de API.",
  "version": "1.0",
  "baseUrl": "https://api.exemplo.com/v1",
  "headers": { "X-API-Key": "{{apiKey}}" },
  "login": {
    "fields": [ { "id": "apiKey", "label": "Chave de API", "type": "password" } ],
    "verify": { "method": "GET", "path": "/ping" }
  },
  "panels": [
    { "type": "stats", "request": { "path": "/summary" },
      "items": [ { "label": "Total", "value": "{{${'$'}.total|num:2}}" }, { "label": "Status", "value": "{{${'$'}.status|default:-}}", "tone": "good" } ] }
  ],
  "tools": [
    { "name": "resumo", "description": "Consulta o resumo da conta.", "request": { "path": "/summary" } }
  ]
}
""".trimIndent())

    val MINIMAL = Entry("Mínimo (só leitura)", """
{
  "id": "meuconector",
  "name": "Meu conector",
  "description": "Consulta um endpoint público autenticado por token.",
  "version": "1.0",
  "baseUrl": "https://api.exemplo.com",
  "headers": { "Authorization": "Bearer {{token}}" },
  "login": { "fields": [ { "id": "token", "label": "Token", "type": "password" } ] },
  "panels": [ { "type": "note", "text": "Conectado.", "tone": "good" } ],
  "tools": [ { "name": "consultar", "description": "Consulta os dados.", "request": { "path": "/data" } } ]
}
""".trimIndent())

    val ALL = listOf(TOKEN_LOGIN, API_KEY, MINIMAL)

    /** Rascunho enviado ao chat para a IA escrever a definição (o usuário só descreve o serviço). */
    val AI_PROMPT = """Crie um UI Connector em JSON para o serviço: <DESCREVA AQUI: nome, URL base da API e o que quer ver/fazer>.
Responda SOMENTE com o JSON (um bloco de código), neste formato:
{"id":"[a-z0-9] 2-20","name":"","description":"","version":"1.0","baseUrl":"https://host (HTTPS, domínio, sem IP)",
"headers":{"Authorization":"Bearer {{token}}"},
"login":{"fields":[{"id":"email","label":"E-mail","type":"email"},{"id":"password","label":"Senha","type":"password"}],
 "request":{"method":"POST","path":"/login","body":{"email":"{{email}}","password":"{{password}}"}},
 "extract":{"token":"${'$'}.token"},"verify":{"path":"/me"}},
"settings":[{"id":"maxValor","label":"","description":"","type":"number|switch|text","default":"10","risky":true}],
"panels":[{"type":"header|stats|table|note","title":"{{${'$'}.name}}","request":{"path":"/me"},
 "items":[{"label":"","value":"{{${'$'}.x|num:2}}","tone":"good"}],"rows":"${'$'}.items[*]","columns":[{"title":"","value":"{{${'$'}.y}}"}]}],
"actions":[{"id":"refresh","label":"","request":{"path":"/x"}}],
"tools":[{"name":"snake_case","description":"","params":[{"name":"","description":"","type":"string|integer|number|boolean","required":true,"enum":[]}],
 "risky":false,"limits":[{"param":"valor","maxSetting":"maxValor"}],"request":{"method":"GET","path":"/x/{{param|url}}"},"response":"{{${'$'}.campo}}"}]}
Regras: {{var}} usa campos de login, valores extraídos, parâmetros e {{s.<setting>}}; {{${'$'}.caminho}} lê a resposta JSON (só . [n] [*]); filtros: url,json,num:N,int,upper,lower,trim,default:X,date,pct:N,bool. Método diferente de GET exige "risky":true (ferramentas/ações). Em ferramentas de valor use "limits". Nada de IP, localhost, HTTP ou outros hosts."""
}
