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

    val RSS = Entry("Notícias por RSS (sem login)", """
{
  "id": "noticiasg1",
  "name": "Notícias g1",
  "description": "Últimas notícias do g1 via RSS.",
  "version": "1.0",
  "baseUrl": "https://g1.globo.com",
  "panels": [
    { "type": "table", "title": "Últimas notícias", "request": { "path": "/rss/g1/" },
      "rows": "${'$'}.rss.channel.item[*]",
      "columns": [ { "title": "Manchete", "value": "{{${'$'}.title|strip}}" } ],
      "empty": "Sem notícias agora." }
  ],
  "tools": [
    { "name": "ultimas_noticias", "description": "Lista as últimas notícias do g1 com título, link e data.",
      "request": { "path": "/rss/g1/" },
      "list": { "rows": "${'$'}.rss.channel.item[*]", "item": "{{${'$'}.title|strip}} — {{${'$'}.link}} ({{${'$'}.pubDate}})", "max": 30 } }
  ]
}
""".trimIndent())

    val SPOTIFY = Entry("OAuth client credentials (Spotify)", """
{
  "id": "spotify",
  "name": "Spotify",
  "description": "Busca artistas, faixas e álbuns no catálogo do Spotify.",
  "version": "1.0",
  "accent": "#1DB954",
  "baseUrl": "https://api.spotify.com/v1",
  "allowedHosts": [ "accounts.spotify.com" ],
  "headers": { "Authorization": "Bearer {{token}}" },
  "login": {
    "fields": [
      { "id": "clientId", "label": "Client ID", "type": "text", "hint": "developer.spotify.com/dashboard" },
      { "id": "clientSecret", "label": "Client Secret", "type": "password" }
    ],
    "request": { "method": "POST", "url": "https://accounts.spotify.com/api/token",
      "form": { "grant_type": "client_credentials", "client_id": "{{clientId}}", "client_secret": "{{clientSecret}}" } },
    "extract": { "token": "${'$'}.access_token" }
  },
  "panels": [
    { "type": "table", "title": "Novos lançamentos", "request": { "path": "/browse/new-releases", "query": { "limit": "10" } },
      "rows": "${'$'}.albums.items[*]",
      "columns": [ { "title": "Álbum", "value": "{{${'$'}.name}}" }, { "title": "Artista", "value": "{{${'$'}.artists[0].name}}" } ] }
  ],
  "tools": [
    { "name": "buscar", "description": "Busca no catálogo do Spotify e devolve nome e link.",
      "params": [
        { "name": "termo", "description": "Texto da busca" },
        { "name": "tipo", "description": "O que buscar", "enum": [ "artist", "track", "album" ] }
      ],
      "request": { "path": "/search", "query": { "q": "{{termo}}", "type": "{{tipo}}", "limit": "10" } },
      "list": { "rows": "${'$'}.*.items[*]", "item": "{{${'$'}.name}} — {{${'$'}.external_urls.spotify}}", "max": 10 } }
  ]
}
""".trimIndent())


    val MARKET_WS = Entry("Tempo real público (WebSocket: Binance)", """
{
  "id": "binancetempo",
  "name": "Binance tempo real",
  "description": "Preços de criptomoedas em tempo real pelo stream público da Binance.",
  "version": "1.0",
  "baseUrl": "https://api.binance.com",
  "allowedHosts": [ "stream.binance.com" ],
  "realtime": {
    "url": "wss://stream.binance.com:443/ws",
    "connect": "auto",
    "onOpen": [ { "method": "SUBSCRIBE", "params": [ "btcusdt@ticker", "ethusdt@ticker", "solusdt@ticker" ], "id": 1 } ],
    "rules": [
      { "match": { "${'$'}.e": "24hrTicker" }, "set": { "ultimo": "${'$'}.s" }, "push": { "to": "precos", "key": "${'$'}.s", "max": 50 } }
    ]
  },
  "panels": [
    { "type": "stats", "items": [
      { "label": "Mensagens", "value": "{{rt.messages}}" },
      { "label": "Último par", "value": "{{rt.ultimo|default:-}}", "tone": "good" } ] },
    { "type": "table", "title": "Preços ao vivo", "source": "precos", "rows": "${'$'}.items[*]",
      "columns": [
        { "title": "Par", "value": "{{${'$'}.s}}" },
        { "title": "Preço", "value": "{{${'$'}.c|num:4}}" },
        { "title": "24h", "value": "{{${'$'}.P|pct:2}}" } ],
      "empty": "Aguardando preços…" }
  ],
  "tools": [
    { "name": "precos_ao_vivo", "description": "Últimos preços recebidos em tempo real (par, preço, variação 24h).",
      "source": "precos", "list": { "rows": "${'$'}.items[*]", "item": "{{${'$'}.s}}: {{${'$'}.c}} ({{${'$'}.P|pct:2}})", "max": 50 } },
    { "name": "acompanhar_par", "description": "Passa a receber em tempo real o preço de um par (ex.: BNBUSDT).",
      "params": [ { "name": "par", "description": "Par da Binance, ex.: BNBUSDT" } ],
      "send": { "message": { "method": "SUBSCRIBE", "params": [ "{{par|lower}}@ticker" ], "id": "{{rid|int}}" },
        "await": { "match": { "${'$'}.id": "{{rid}}" }, "timeoutSec": 10 } },
      "response": "Acompanhando {{par|upper}} em tempo real." },
    { "name": "preco_rest", "description": "Preço atual de um par pela API REST.",
      "params": [ { "name": "par", "description": "Par, ex.: BTCUSDT" } ],
      "request": { "path": "/api/v3/ticker/price", "query": { "symbol": "{{par|upper}}" } },
      "response": "{{${'$'}.symbol}}: {{${'$'}.price}}" }
  ]
}
""".trimIndent())

    val BROKER_WS = Entry("Corretora em tempo real (login + WebSocket + ordens)", """
{
  "id": "corretorart",
  "name": "Corretora (tempo real)",
  "description": "Login, saldo e cotações em tempo real e envio de ordens validadas.",
  "version": "1.0",
  "baseUrl": "https://api.suacorretora.com",
  "allowedHosts": [ "ws.suacorretora.com" ],
  "headers": { "Authorization": "Bearer {{token}}" },
  "login": {
    "fields": [
      { "id": "email", "label": "E-mail", "type": "email" },
      { "id": "password", "label": "Senha", "type": "password" }
    ],
    "request": { "method": "POST", "path": "/auth/login", "body": { "email": "{{email}}", "password": "{{password}}" } },
    "extract": { "token": "${'$'}.token" }
  },
  "settings": [
    { "id": "maxValor", "label": "Valor máximo por ordem", "description": "Teto por ordem do agente.", "type": "number", "default": "10", "risky": true }
  ],
  "realtime": {
    "url": "wss://ws.suacorretora.com/stream",
    "onOpen": [
      { "type": "auth", "token": "{{token}}" },
      { "type": "subscribe", "channels": [ "balance", "quotes", "orders" ] }
    ],
    "heartbeat": { "everySec": 20, "message": { "type": "ping" } },
    "rules": [
      { "match": { "${'$'}.type": "balance" }, "set": { "saldo": "${'$'}.data.amount", "moeda": "${'$'}.data.currency" } },
      { "match": { "${'$'}.type": "quote" }, "push": { "to": "cotacoes", "item": "${'$'}.data", "key": "${'$'}.symbol", "max": 50 } },
      { "match": { "${'$'}.type": "order" }, "push": { "to": "ordens", "item": "${'$'}.data", "key": "${'$'}.id", "max": 100 } }
    ]
  },
  "panels": [
    { "type": "stats", "items": [
      { "label": "Saldo", "value": "{{rt.saldo|num:2}} {{rt.moeda}}", "tone": "good" },
      { "label": "Conectado", "value": "{{rt.connected}}" } ] },
    { "type": "table", "title": "Cotações", "source": "cotacoes", "rows": "${'$'}.items[*]",
      "columns": [ { "title": "Ativo", "value": "{{${'$'}.symbol}}" }, { "title": "Preço", "value": "{{${'$'}.price|num:5}}" } ],
      "empty": "Aguardando cotações…" },
    { "type": "table", "title": "Ordens", "source": "ordens", "rows": "${'$'}.items[*]",
      "columns": [ { "title": "Ativo", "value": "{{${'$'}.symbol}}" }, { "title": "Lado", "value": "{{${'$'}.side}}" },
        { "title": "Valor", "value": "{{${'$'}.amount|num:2}}" }, { "title": "Status", "value": "{{${'$'}.status}}" } ],
      "empty": "Nenhuma ordem." }
  ],
  "tools": [
    { "name": "saldo", "description": "Saldo atual (tempo real).", "response": "Saldo: {{rt.saldo|num:2}} {{rt.moeda}}" },
    { "name": "cotacoes", "description": "Últimas cotações recebidas em tempo real.",
      "source": "cotacoes", "list": { "rows": "${'$'}.items[*]", "item": "{{${'$'}.symbol}}: {{${'$'}.price}}", "max": 50 } },
    { "name": "velas", "description": "Velas (candles) recentes de um ativo.",
      "params": [ { "name": "ativo", "description": "Símbolo do ativo" }, { "name": "minutos", "description": "Tamanho da vela em minutos", "type": "integer" } ],
      "send": { "message": { "type": "candles", "symbol": "{{ativo}}", "size": "{{minutos|int}}", "request_id": "{{rid}}" },
        "await": { "match": { "${'$'}.request_id": "{{rid}}" }, "timeoutSec": 10 } },
      "list": { "rows": "${'$'}.data[*]", "item": "{{${'$'}.time|date}} abre {{${'$'}.open}} fecha {{${'$'}.close}}", "max": 60 } },
    { "name": "enviar_ordem", "description": "Envia uma ordem de compra ou venda (exige validação TypeSafe Jev).",
      "risky": true, "requiresSignal": true,
      "params": [
        { "name": "ativo", "description": "Símbolo do ativo" },
        { "name": "lado", "description": "buy ou sell", "enum": [ "buy", "sell" ] },
        { "name": "valor", "description": "Valor da ordem", "type": "number" }
      ],
      "limits": [ { "param": "valor", "maxSetting": "maxValor" } ],
      "orderIntent": { "asset": "{{ativo}}", "direction": "{{lado}}" },
      "send": { "message": { "type": "order", "symbol": "{{ativo}}", "side": "{{lado}}", "amount": "{{valor|num}}", "request_id": "{{rid}}" },
        "await": { "match": { "${'$'}.request_id": "{{rid}}" }, "timeoutSec": 15 } },
      "response": "Ordem {{${'$'}.data.id}}: {{${'$'}.data.status}}" }
  ]
}
""".trimIndent())

    val TV_INDICATORS = Entry("Indicadores técnicos (recebe velas, sem buscar dados)", """
{
 "id": "indicadores",
 "name": "Indicadores técnicos",
 "description": "Suíte de indicadores no padrão TradingView: recebe velas de qualquer fonte (ex.: get_candles da IQ Option) e devolve todos os indicadores com sinal de compra/venda e o resumo. Não busca dados.",
 "version": "2.0",
 "panels": [
  {
   "type": "note",
   "text": "Este conector não busca dados: o agente envia velas de outro conector (ex.: IQ Option get_candles) para a ferramenta analisar, que devolve mais de 30 indicadores — médias, osciladores e volatilidade — com sinal de compra/venda e o resumo técnico. Tudo calculado no aparelho, em milissegundos.",
   "tone": "neutral"
  }
 ],
 "tools": [
  {
   "name": "analisar",
   "description": "Calcula a suíte completa de indicadores técnicos (SMA/EMA 10–200, VWMA, Hull, Ichimoku, PSAR, Supertrend, RSI, Estocástico, Stoch RSI, MACD, CCI, ADX, Williams %R, AO, Momentum, UO, Bull/Bear, MFI, CMF, Bollinger %B, CMO, ROC, TRIX, Aroon, ATR, Choppiness) sobre as velas recebidas, com sinal de compra/venda e o resumo. Passe as velas de outro conector (ex.: a saída de get_candles da IQ Option).",
   "params": [
    {
     "name": "velas",
     "description": "Velas em qualquer formato: a saída de get_candles (texto), JSON [[t,o,h,l,c,v],…], lista de objetos {open,high,low,close,volume} ou colunas {open:[…],high:[…],low:[…],close:[…],volume:[…]}. Mínimo ~30 velas; 200+ para as médias longas."
    },
    {
     "name": "ativo",
     "description": "Nome do ativo/tempo gráfico (só para o título)",
     "required": false
    }
   ],
   "compute": {
    "r": "indicators(velas)",
    "linhas": "c.r.rows",
    "resumo": "c.r.resumo",
    "compras": "c.r.compras",
    "vendas": "c.r.vendas",
    "neutros": "c.r.neutros",
    "preco": "c.r.preco",
    "medias": "c.r.medias",
    "osciladores": "c.r.osciladores",
    "n": "c.r.velas"
   },
   "list": {
    "rows": "c.linhas",
    "item": "{{${'$'}.nome}}: {{${'$'}.valor}} → {{${'$'}.sinal}}",
    "max": 80
   },
   "response": "{{ativo|default:Ativo}} — {{c.n}} velas — preço {{c.preco}} — RESUMO: {{c.resumo}} ({{c.compras}} compra, {{c.vendas}} venda, {{c.neutros}} neutro) · médias {{c.medias}} · osciladores {{c.osciladores}}"
  }
 ]
}
""".trimIndent())

    val ALL = listOf(TOKEN_LOGIN, API_KEY, RSS, SPOTIFY, MARKET_WS, BROKER_WS, TV_INDICATORS, MINIMAL)

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
