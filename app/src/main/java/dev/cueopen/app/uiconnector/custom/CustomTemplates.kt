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

    val TV_INDICATORS = Entry("Indicadores técnicos calculados (compute: estilo TradingView)", """
{
 "id": "indicadores",
 "name": "Indicadores técnicos",
 "description": "Mais de 30 indicadores no padrão TradingView calculados no app (motor compute) sobre velas reais da Binance.",
 "version": "1.0",
 "baseUrl": "https://api.binance.com",
 "settings": [
  {
   "id": "par",
   "label": "Par",
   "description": "Par da Binance, ex.: BTCUSDT",
   "type": "text",
   "default": "BTCUSDT"
  },
  {
   "id": "intervalo",
   "label": "Tempo gráfico",
   "description": "1m, 5m, 15m, 1h, 4h ou 1d",
   "type": "text",
   "default": "1h"
  }
 ],
 "panels": [
  {
   "type": "header",
   "title": "{{s.par|upper}} · {{s.intervalo}}",
   "subtitle": "Preço {{c.preco}} · Resumo técnico: {{c.resumo}}",
   "badge": "{{c.compras}} compra · {{c.vendas}} venda",
   "request": {
    "path": "/api/v3/klines",
    "query": {
     "symbol": "{{s.par|upper}}",
     "interval": "{{s.intervalo}}",
     "limit": "300"
    }
   },
   "compute": {
    "op": "map(${'$'}, k -> num(k[1]))",
    "hi": "map(${'$'}, k -> num(k[2]))",
    "lo": "map(${'$'}, k -> num(k[3]))",
    "cl": "map(${'$'}, k -> num(k[4]))",
    "vol": "map(${'$'}, k -> num(k[5]))",
    "tm": "map(${'$'}, k -> k[0])",
    "preco": "last(c.cl)",
    "sma10": "last(sma(c.cl, 10))",
    "ema10": "last(ema(c.cl, 10))",
    "sma20": "last(sma(c.cl, 20))",
    "ema20": "last(ema(c.cl, 20))",
    "sma50": "last(sma(c.cl, 50))",
    "ema50": "last(ema(c.cl, 50))",
    "sma100": "last(sma(c.cl, 100))",
    "ema100": "last(ema(c.cl, 100))",
    "sma200": "last(sma(c.cl, 200))",
    "ema200": "last(ema(c.cl, 200))",
    "vwma20": "last(vwma(c.cl, c.vol, 20))",
    "hma9": "last(hma(c.cl, 9))",
    "rsi": "last(rsi(c.cl, 14))",
    "st": "stoch(c.hi, c.lo, c.cl, 14, 3, 3)",
    "stk": "last(c.st.k)",
    "std": "last(c.st.d)",
    "srsi": "last(stochrsi(c.cl).k)",
    "mc": "macd(c.cl)",
    "mcl": "last(c.mc.macd)",
    "mcs": "last(c.mc.signal)",
    "cci": "last(cci(c.hi, c.lo, c.cl, 20))",
    "ax": "adx(c.hi, c.lo, c.cl, 14)",
    "adx": "last(c.ax.adx)",
    "pdi": "last(c.ax.pdi)",
    "mdi": "last(c.ax.mdi)",
    "wr": "last(willr(c.hi, c.lo, c.cl, 14))",
    "aos": "compact(ao(c.hi, c.lo))",
    "mom": "last(mom(c.cl, 10))",
    "uo": "last(uo(c.hi, c.lo, c.cl))",
    "el": "elder(c.hi, c.lo, c.cl)",
    "bbp": "last(c.el.bull) + last(c.el.bear)",
    "mfi": "last(mfi(c.hi, c.lo, c.cl, c.vol, 14))",
    "cmf": "last(cmf(c.hi, c.lo, c.cl, c.vol, 20))",
    "bb": "last(bollinger(c.cl, 20, 2).pctb)",
    "kij": "last(ichimoku(c.hi, c.lo).kijun)",
    "supd": "last(supertrend(c.hi, c.lo, c.cl, 10, 3).dir)",
    "sar": "last(psar(c.hi, c.lo))",
    "atr": "last(atr(c.hi, c.lo, c.cl, 14))",
    "masig": "m -> c.preco > m ? 'compra' : (c.preco < m ? 'venda' : 'neutro')",
    "osc": "(cb, cv) -> cb ? 'compra' : (cv ? 'venda' : 'neutro')",
    "indicadores": "[{nome: 'SMA(10)', valor: round(c.sma10, 4), sinal: call(c.masig, c.sma10)}, {nome: 'EMA(10)', valor: round(c.ema10, 4), sinal: call(c.masig, c.ema10)}, {nome: 'SMA(20)', valor: round(c.sma20, 4), sinal: call(c.masig, c.sma20)}, {nome: 'EMA(20)', valor: round(c.ema20, 4), sinal: call(c.masig, c.ema20)}, {nome: 'SMA(50)', valor: round(c.sma50, 4), sinal: call(c.masig, c.sma50)}, {nome: 'EMA(50)', valor: round(c.ema50, 4), sinal: call(c.masig, c.ema50)}, {nome: 'SMA(100)', valor: round(c.sma100, 4), sinal: call(c.masig, c.sma100)}, {nome: 'EMA(100)', valor: round(c.ema100, 4), sinal: call(c.masig, c.ema100)}, {nome: 'SMA(200)', valor: round(c.sma200, 4), sinal: call(c.masig, c.sma200)}, {nome: 'EMA(200)', valor: round(c.ema200, 4), sinal: call(c.masig, c.ema200)}, {nome: 'VWMA(20)', valor: round(c.vwma20, 4), sinal: call(c.masig, c.vwma20)}, {nome: 'Hull MA(9)', valor: round(c.hma9, 4), sinal: call(c.masig, c.hma9)}, {nome: 'Ichimoku (base)', valor: round(c.kij, 4), sinal: call(c.masig, c.kij)}, {nome: 'Parabolic SAR', valor: round(c.sar, 4), sinal: call(c.masig, c.sar)}, {nome: 'Supertrend(10,3)', valor: c.supd, sinal: call(c.osc, c.supd > 0, c.supd < 0)}, {nome: 'RSI(14)', valor: round(c.rsi, 2), sinal: call(c.osc, c.rsi < 30, c.rsi > 70)}, {nome: 'Estocástico %K(14,3,3)', valor: round(c.stk, 2), sinal: call(c.osc, c.stk < 20 && c.stk > c.std, c.stk > 80 && c.stk < c.std)}, {nome: 'Stoch RSI', valor: round(c.srsi, 2), sinal: call(c.osc, c.srsi < 20, c.srsi > 80)}, {nome: 'MACD(12,26)', valor: round(c.mcl, 4), sinal: call(c.osc, c.mcl > c.mcs, c.mcl < c.mcs)}, {nome: 'CCI(20)', valor: round(c.cci, 2), sinal: call(c.osc, c.cci < -100, c.cci > 100)}, {nome: 'ADX(14)', valor: round(c.adx, 2), sinal: call(c.osc, c.adx > 20 && c.pdi > c.mdi, c.adx > 20 && c.pdi < c.mdi)}, {nome: 'Williams %R(14)', valor: round(c.wr, 2), sinal: call(c.osc, c.wr < -80, c.wr > -20)}, {nome: 'Awesome Oscillator', valor: round(last(c.aos), 4), sinal: call(c.osc, last(c.aos) > 0 && last(c.aos) > at(c.aos, -2), last(c.aos) < 0 && last(c.aos) < at(c.aos, -2))}, {nome: 'Momentum(10)', valor: round(c.mom, 4), sinal: call(c.osc, c.mom > 0, c.mom < 0)}, {nome: 'Ultimate Oscillator', valor: round(c.uo, 2), sinal: call(c.osc, c.uo > 70, c.uo < 30)}, {nome: 'Bull/Bear Power', valor: round(c.bbp, 4), sinal: call(c.osc, c.bbp > 0, c.bbp < 0)}, {nome: 'MFI(14)', valor: round(c.mfi, 2), sinal: call(c.osc, c.mfi < 20, c.mfi > 80)}, {nome: 'Chaikin MF(20)', valor: round(c.cmf, 3), sinal: call(c.osc, c.cmf > 0, c.cmf < 0)}, {nome: 'Bollinger %B(20,2)', valor: round(c.bb, 3), sinal: call(c.osc, c.bb < 0, c.bb > 1)}, {nome: 'ATR(14)', valor: round(c.atr, 4), sinal: 'neutro'}]",
    "compras": "count(c.indicadores, x -> x.sinal == 'compra')",
    "vendas": "count(c.indicadores, x -> x.sinal == 'venda')",
    "neutros": "count(c.indicadores, x -> x.sinal == 'neutro')",
    "resumo": "c.compras - c.vendas >= 8 ? 'Forte compra' : (c.compras - c.vendas >= 3 ? 'Compra' : (c.vendas - c.compras >= 8 ? 'Forte venda' : (c.vendas - c.compras >= 3 ? 'Venda' : 'Neutro')))"
   }
  },
  {
   "type": "table",
   "title": "Painel de indicadores",
   "request": {
    "path": "/api/v3/klines",
    "query": {
     "symbol": "{{s.par|upper}}",
     "interval": "{{s.intervalo}}",
     "limit": "300"
    }
   },
   "compute": {
    "op": "map(${'$'}, k -> num(k[1]))",
    "hi": "map(${'$'}, k -> num(k[2]))",
    "lo": "map(${'$'}, k -> num(k[3]))",
    "cl": "map(${'$'}, k -> num(k[4]))",
    "vol": "map(${'$'}, k -> num(k[5]))",
    "tm": "map(${'$'}, k -> k[0])",
    "preco": "last(c.cl)",
    "sma10": "last(sma(c.cl, 10))",
    "ema10": "last(ema(c.cl, 10))",
    "sma20": "last(sma(c.cl, 20))",
    "ema20": "last(ema(c.cl, 20))",
    "sma50": "last(sma(c.cl, 50))",
    "ema50": "last(ema(c.cl, 50))",
    "sma100": "last(sma(c.cl, 100))",
    "ema100": "last(ema(c.cl, 100))",
    "sma200": "last(sma(c.cl, 200))",
    "ema200": "last(ema(c.cl, 200))",
    "vwma20": "last(vwma(c.cl, c.vol, 20))",
    "hma9": "last(hma(c.cl, 9))",
    "rsi": "last(rsi(c.cl, 14))",
    "st": "stoch(c.hi, c.lo, c.cl, 14, 3, 3)",
    "stk": "last(c.st.k)",
    "std": "last(c.st.d)",
    "srsi": "last(stochrsi(c.cl).k)",
    "mc": "macd(c.cl)",
    "mcl": "last(c.mc.macd)",
    "mcs": "last(c.mc.signal)",
    "cci": "last(cci(c.hi, c.lo, c.cl, 20))",
    "ax": "adx(c.hi, c.lo, c.cl, 14)",
    "adx": "last(c.ax.adx)",
    "pdi": "last(c.ax.pdi)",
    "mdi": "last(c.ax.mdi)",
    "wr": "last(willr(c.hi, c.lo, c.cl, 14))",
    "aos": "compact(ao(c.hi, c.lo))",
    "mom": "last(mom(c.cl, 10))",
    "uo": "last(uo(c.hi, c.lo, c.cl))",
    "el": "elder(c.hi, c.lo, c.cl)",
    "bbp": "last(c.el.bull) + last(c.el.bear)",
    "mfi": "last(mfi(c.hi, c.lo, c.cl, c.vol, 14))",
    "cmf": "last(cmf(c.hi, c.lo, c.cl, c.vol, 20))",
    "bb": "last(bollinger(c.cl, 20, 2).pctb)",
    "kij": "last(ichimoku(c.hi, c.lo).kijun)",
    "supd": "last(supertrend(c.hi, c.lo, c.cl, 10, 3).dir)",
    "sar": "last(psar(c.hi, c.lo))",
    "atr": "last(atr(c.hi, c.lo, c.cl, 14))",
    "masig": "m -> c.preco > m ? 'compra' : (c.preco < m ? 'venda' : 'neutro')",
    "osc": "(cb, cv) -> cb ? 'compra' : (cv ? 'venda' : 'neutro')",
    "indicadores": "[{nome: 'SMA(10)', valor: round(c.sma10, 4), sinal: call(c.masig, c.sma10)}, {nome: 'EMA(10)', valor: round(c.ema10, 4), sinal: call(c.masig, c.ema10)}, {nome: 'SMA(20)', valor: round(c.sma20, 4), sinal: call(c.masig, c.sma20)}, {nome: 'EMA(20)', valor: round(c.ema20, 4), sinal: call(c.masig, c.ema20)}, {nome: 'SMA(50)', valor: round(c.sma50, 4), sinal: call(c.masig, c.sma50)}, {nome: 'EMA(50)', valor: round(c.ema50, 4), sinal: call(c.masig, c.ema50)}, {nome: 'SMA(100)', valor: round(c.sma100, 4), sinal: call(c.masig, c.sma100)}, {nome: 'EMA(100)', valor: round(c.ema100, 4), sinal: call(c.masig, c.ema100)}, {nome: 'SMA(200)', valor: round(c.sma200, 4), sinal: call(c.masig, c.sma200)}, {nome: 'EMA(200)', valor: round(c.ema200, 4), sinal: call(c.masig, c.ema200)}, {nome: 'VWMA(20)', valor: round(c.vwma20, 4), sinal: call(c.masig, c.vwma20)}, {nome: 'Hull MA(9)', valor: round(c.hma9, 4), sinal: call(c.masig, c.hma9)}, {nome: 'Ichimoku (base)', valor: round(c.kij, 4), sinal: call(c.masig, c.kij)}, {nome: 'Parabolic SAR', valor: round(c.sar, 4), sinal: call(c.masig, c.sar)}, {nome: 'Supertrend(10,3)', valor: c.supd, sinal: call(c.osc, c.supd > 0, c.supd < 0)}, {nome: 'RSI(14)', valor: round(c.rsi, 2), sinal: call(c.osc, c.rsi < 30, c.rsi > 70)}, {nome: 'Estocástico %K(14,3,3)', valor: round(c.stk, 2), sinal: call(c.osc, c.stk < 20 && c.stk > c.std, c.stk > 80 && c.stk < c.std)}, {nome: 'Stoch RSI', valor: round(c.srsi, 2), sinal: call(c.osc, c.srsi < 20, c.srsi > 80)}, {nome: 'MACD(12,26)', valor: round(c.mcl, 4), sinal: call(c.osc, c.mcl > c.mcs, c.mcl < c.mcs)}, {nome: 'CCI(20)', valor: round(c.cci, 2), sinal: call(c.osc, c.cci < -100, c.cci > 100)}, {nome: 'ADX(14)', valor: round(c.adx, 2), sinal: call(c.osc, c.adx > 20 && c.pdi > c.mdi, c.adx > 20 && c.pdi < c.mdi)}, {nome: 'Williams %R(14)', valor: round(c.wr, 2), sinal: call(c.osc, c.wr < -80, c.wr > -20)}, {nome: 'Awesome Oscillator', valor: round(last(c.aos), 4), sinal: call(c.osc, last(c.aos) > 0 && last(c.aos) > at(c.aos, -2), last(c.aos) < 0 && last(c.aos) < at(c.aos, -2))}, {nome: 'Momentum(10)', valor: round(c.mom, 4), sinal: call(c.osc, c.mom > 0, c.mom < 0)}, {nome: 'Ultimate Oscillator', valor: round(c.uo, 2), sinal: call(c.osc, c.uo > 70, c.uo < 30)}, {nome: 'Bull/Bear Power', valor: round(c.bbp, 4), sinal: call(c.osc, c.bbp > 0, c.bbp < 0)}, {nome: 'MFI(14)', valor: round(c.mfi, 2), sinal: call(c.osc, c.mfi < 20, c.mfi > 80)}, {nome: 'Chaikin MF(20)', valor: round(c.cmf, 3), sinal: call(c.osc, c.cmf > 0, c.cmf < 0)}, {nome: 'Bollinger %B(20,2)', valor: round(c.bb, 3), sinal: call(c.osc, c.bb < 0, c.bb > 1)}, {nome: 'ATR(14)', valor: round(c.atr, 4), sinal: 'neutro'}]",
    "compras": "count(c.indicadores, x -> x.sinal == 'compra')",
    "vendas": "count(c.indicadores, x -> x.sinal == 'venda')",
    "neutros": "count(c.indicadores, x -> x.sinal == 'neutro')",
    "resumo": "c.compras - c.vendas >= 8 ? 'Forte compra' : (c.compras - c.vendas >= 3 ? 'Compra' : (c.vendas - c.compras >= 8 ? 'Forte venda' : (c.vendas - c.compras >= 3 ? 'Venda' : 'Neutro')))"
   },
   "rows": "c.indicadores",
   "columns": [
    {
     "title": "Indicador",
     "value": "{{${'$'}.nome}}"
    },
    {
     "title": "Valor",
     "value": "{{${'$'}.valor}}"
    },
    {
     "title": "Sinal",
     "value": "{{${'$'}.sinal}}"
    }
   ],
   "empty": "Sem dados."
  },
  {
   "type": "table",
   "title": "Últimas velas",
   "request": {
    "path": "/api/v3/klines",
    "query": {
     "symbol": "{{s.par|upper}}",
     "interval": "{{s.intervalo}}",
     "limit": "300"
    }
   },
   "compute": {
    "op": "map(${'$'}, k -> num(k[1]))",
    "hi": "map(${'$'}, k -> num(k[2]))",
    "lo": "map(${'$'}, k -> num(k[3]))",
    "cl": "map(${'$'}, k -> num(k[4]))",
    "vol": "map(${'$'}, k -> num(k[5]))",
    "tm": "map(${'$'}, k -> k[0])",
    "preco": "last(c.cl)",
    "rsiS": "rsi(c.cl, 14)",
    "e20": "ema(c.cl, 20)",
    "linhas": "reverse(zip_rows({hora: map(tail(c.tm, 20), t -> date(t)), fechamento: tail(c.cl, 20), rsi: map(tail(c.rsiS, 20), x -> round(x, 1)), ema20: map(tail(c.e20, 20), x -> round(x, 4)), volume: map(tail(c.vol, 20), x -> round(x, 2))}))"
   },
   "rows": "c.linhas",
   "columns": [
    {
     "title": "Hora",
     "value": "{{${'$'}.hora}}"
    },
    {
     "title": "Fech.",
     "value": "{{${'$'}.fechamento}}"
    },
    {
     "title": "RSI",
     "value": "{{${'$'}.rsi}}"
    },
    {
     "title": "EMA20",
     "value": "{{${'$'}.ema20}}"
    }
   ],
   "empty": "Sem dados."
  }
 ],
 "tools": [
  {
   "name": "indicadores",
   "description": "Calcula mais de 30 indicadores técnicos (médias, RSI, MACD, Estocástico, ADX, Bollinger, Ichimoku, Supertrend, PSAR…) com sinal de compra/venda e o resumo técnico, sobre velas reais.",
   "params": [
    {
     "name": "par",
     "description": "Par da Binance, ex.: BTCUSDT"
    },
    {
     "name": "intervalo",
     "description": "Tempo gráfico",
     "enum": [
      "1m",
      "5m",
      "15m",
      "1h",
      "4h",
      "1d"
     ]
    }
   ],
   "request": {
    "path": "/api/v3/klines",
    "query": {
     "symbol": "{{par|upper}}",
     "interval": "{{intervalo}}",
     "limit": "300"
    }
   },
   "compute": {
    "op": "map(${'$'}, k -> num(k[1]))",
    "hi": "map(${'$'}, k -> num(k[2]))",
    "lo": "map(${'$'}, k -> num(k[3]))",
    "cl": "map(${'$'}, k -> num(k[4]))",
    "vol": "map(${'$'}, k -> num(k[5]))",
    "tm": "map(${'$'}, k -> k[0])",
    "preco": "last(c.cl)",
    "sma10": "last(sma(c.cl, 10))",
    "ema10": "last(ema(c.cl, 10))",
    "sma20": "last(sma(c.cl, 20))",
    "ema20": "last(ema(c.cl, 20))",
    "sma50": "last(sma(c.cl, 50))",
    "ema50": "last(ema(c.cl, 50))",
    "sma100": "last(sma(c.cl, 100))",
    "ema100": "last(ema(c.cl, 100))",
    "sma200": "last(sma(c.cl, 200))",
    "ema200": "last(ema(c.cl, 200))",
    "vwma20": "last(vwma(c.cl, c.vol, 20))",
    "hma9": "last(hma(c.cl, 9))",
    "rsi": "last(rsi(c.cl, 14))",
    "st": "stoch(c.hi, c.lo, c.cl, 14, 3, 3)",
    "stk": "last(c.st.k)",
    "std": "last(c.st.d)",
    "srsi": "last(stochrsi(c.cl).k)",
    "mc": "macd(c.cl)",
    "mcl": "last(c.mc.macd)",
    "mcs": "last(c.mc.signal)",
    "cci": "last(cci(c.hi, c.lo, c.cl, 20))",
    "ax": "adx(c.hi, c.lo, c.cl, 14)",
    "adx": "last(c.ax.adx)",
    "pdi": "last(c.ax.pdi)",
    "mdi": "last(c.ax.mdi)",
    "wr": "last(willr(c.hi, c.lo, c.cl, 14))",
    "aos": "compact(ao(c.hi, c.lo))",
    "mom": "last(mom(c.cl, 10))",
    "uo": "last(uo(c.hi, c.lo, c.cl))",
    "el": "elder(c.hi, c.lo, c.cl)",
    "bbp": "last(c.el.bull) + last(c.el.bear)",
    "mfi": "last(mfi(c.hi, c.lo, c.cl, c.vol, 14))",
    "cmf": "last(cmf(c.hi, c.lo, c.cl, c.vol, 20))",
    "bb": "last(bollinger(c.cl, 20, 2).pctb)",
    "kij": "last(ichimoku(c.hi, c.lo).kijun)",
    "supd": "last(supertrend(c.hi, c.lo, c.cl, 10, 3).dir)",
    "sar": "last(psar(c.hi, c.lo))",
    "atr": "last(atr(c.hi, c.lo, c.cl, 14))",
    "masig": "m -> c.preco > m ? 'compra' : (c.preco < m ? 'venda' : 'neutro')",
    "osc": "(cb, cv) -> cb ? 'compra' : (cv ? 'venda' : 'neutro')",
    "indicadores": "[{nome: 'SMA(10)', valor: round(c.sma10, 4), sinal: call(c.masig, c.sma10)}, {nome: 'EMA(10)', valor: round(c.ema10, 4), sinal: call(c.masig, c.ema10)}, {nome: 'SMA(20)', valor: round(c.sma20, 4), sinal: call(c.masig, c.sma20)}, {nome: 'EMA(20)', valor: round(c.ema20, 4), sinal: call(c.masig, c.ema20)}, {nome: 'SMA(50)', valor: round(c.sma50, 4), sinal: call(c.masig, c.sma50)}, {nome: 'EMA(50)', valor: round(c.ema50, 4), sinal: call(c.masig, c.ema50)}, {nome: 'SMA(100)', valor: round(c.sma100, 4), sinal: call(c.masig, c.sma100)}, {nome: 'EMA(100)', valor: round(c.ema100, 4), sinal: call(c.masig, c.ema100)}, {nome: 'SMA(200)', valor: round(c.sma200, 4), sinal: call(c.masig, c.sma200)}, {nome: 'EMA(200)', valor: round(c.ema200, 4), sinal: call(c.masig, c.ema200)}, {nome: 'VWMA(20)', valor: round(c.vwma20, 4), sinal: call(c.masig, c.vwma20)}, {nome: 'Hull MA(9)', valor: round(c.hma9, 4), sinal: call(c.masig, c.hma9)}, {nome: 'Ichimoku (base)', valor: round(c.kij, 4), sinal: call(c.masig, c.kij)}, {nome: 'Parabolic SAR', valor: round(c.sar, 4), sinal: call(c.masig, c.sar)}, {nome: 'Supertrend(10,3)', valor: c.supd, sinal: call(c.osc, c.supd > 0, c.supd < 0)}, {nome: 'RSI(14)', valor: round(c.rsi, 2), sinal: call(c.osc, c.rsi < 30, c.rsi > 70)}, {nome: 'Estocástico %K(14,3,3)', valor: round(c.stk, 2), sinal: call(c.osc, c.stk < 20 && c.stk > c.std, c.stk > 80 && c.stk < c.std)}, {nome: 'Stoch RSI', valor: round(c.srsi, 2), sinal: call(c.osc, c.srsi < 20, c.srsi > 80)}, {nome: 'MACD(12,26)', valor: round(c.mcl, 4), sinal: call(c.osc, c.mcl > c.mcs, c.mcl < c.mcs)}, {nome: 'CCI(20)', valor: round(c.cci, 2), sinal: call(c.osc, c.cci < -100, c.cci > 100)}, {nome: 'ADX(14)', valor: round(c.adx, 2), sinal: call(c.osc, c.adx > 20 && c.pdi > c.mdi, c.adx > 20 && c.pdi < c.mdi)}, {nome: 'Williams %R(14)', valor: round(c.wr, 2), sinal: call(c.osc, c.wr < -80, c.wr > -20)}, {nome: 'Awesome Oscillator', valor: round(last(c.aos), 4), sinal: call(c.osc, last(c.aos) > 0 && last(c.aos) > at(c.aos, -2), last(c.aos) < 0 && last(c.aos) < at(c.aos, -2))}, {nome: 'Momentum(10)', valor: round(c.mom, 4), sinal: call(c.osc, c.mom > 0, c.mom < 0)}, {nome: 'Ultimate Oscillator', valor: round(c.uo, 2), sinal: call(c.osc, c.uo > 70, c.uo < 30)}, {nome: 'Bull/Bear Power', valor: round(c.bbp, 4), sinal: call(c.osc, c.bbp > 0, c.bbp < 0)}, {nome: 'MFI(14)', valor: round(c.mfi, 2), sinal: call(c.osc, c.mfi < 20, c.mfi > 80)}, {nome: 'Chaikin MF(20)', valor: round(c.cmf, 3), sinal: call(c.osc, c.cmf > 0, c.cmf < 0)}, {nome: 'Bollinger %B(20,2)', valor: round(c.bb, 3), sinal: call(c.osc, c.bb < 0, c.bb > 1)}, {nome: 'ATR(14)', valor: round(c.atr, 4), sinal: 'neutro'}]",
    "compras": "count(c.indicadores, x -> x.sinal == 'compra')",
    "vendas": "count(c.indicadores, x -> x.sinal == 'venda')",
    "neutros": "count(c.indicadores, x -> x.sinal == 'neutro')",
    "resumo": "c.compras - c.vendas >= 8 ? 'Forte compra' : (c.compras - c.vendas >= 3 ? 'Compra' : (c.vendas - c.compras >= 8 ? 'Forte venda' : (c.vendas - c.compras >= 3 ? 'Venda' : 'Neutro')))"
   },
   "list": {
    "rows": "c.indicadores",
    "item": "{{${'$'}.nome}}: {{${'$'}.valor}} → {{${'$'}.sinal}}",
    "max": 60
   },
   "response": "{{par|upper}} {{intervalo}} — preço {{c.preco}} — resumo: {{c.resumo}} ({{c.compras}} compra, {{c.vendas}} venda, {{c.neutros}} neutro)"
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
