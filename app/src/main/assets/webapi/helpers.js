const enc = encodeURIComponent;
// Espera que funciona mesmo se o WebView congelar timers: setTimeout OU um laço de MessageChannel (o que vier primeiro).
const sleep = ms => new Promise(r => {
  let done = false; const t0 = Date.now(); const fin = () => { if (!done) { done = true; r(); } };
  setTimeout(fin, ms);
  const ch = new MessageChannel();
  ch.port1.onmessage = () => { if (done) return; if (Date.now() - t0 >= ms) fin(); else ch.port2.postMessage(0); };
  ch.port2.postMessage(0);
});
const api = {
  sleep,
  wait: async (sel, ms = 8000) => { const t = Date.now(); while (Date.now() - t < ms) { const e = document.querySelector(sel); if (e) return e; await sleep(150); } return null; },
  all: (sel, root = document) => Array.from(root.querySelectorAll(sel)),
  one: (sel, root = document) => root.querySelector(sel),
  text: (el, sel) => { const e = sel ? (el && el.querySelector(sel)) : el; return e ? (e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim() : ''; },
  attr: (el, sel, a) => { const e = sel ? (el && el.querySelector(sel)) : el; return e ? (e.getAttribute(a) || '') : ''; },
  abs: u => { try { return new URL(u, location.href).href; } catch (e) { return u || ''; } },
  num: s => { const m = String(s || '').replace(/\./g, '').replace(',', '.').match(/-?\d+(\.\d+)?/); return m ? Number(m[0]) : null; },
  fetchText: async (u, opt) => (await fetch(u, Object.assign({ credentials: 'include' }, opt || {}))).text(),
  fetchJson: async (u, opt) => (await fetch(u, Object.assign({ credentials: 'include' }, opt || {}))).json(),
  scroll: async (times = 3) => { for (let i = 0; i < times; i++) { window.scrollTo(0, document.body.scrollHeight); await sleep(500); } },
};
/** Página de bloqueio (captcha/verificação anti-robô)? Devolve o motivo ou ''. */
/** Lê um feed RSS/Atom (da URL atual ou de outra) e devolve [{title, url, snippet, date, source}]. */
api.rss = async (u) => {
  const xml = await api.fetchText(u || location.href);
  const d = new DOMParser().parseFromString(xml, 'text/xml');
  const tx = (el, sel) => { const e = el.getElementsByTagName(sel)[0]; return e ? (e.textContent || '').replace(/\s+/g, ' ').trim() : ''; };
  return Array.from(d.getElementsByTagName('item')).map(it => ({ title: tx(it, 'title'), url: tx(it, 'link'), snippet: tx(it, 'description').replace(/<[^>]+>/g, ''), date: tx(it, 'pubDate'), source: tx(it, 'News:Source') || tx(it, 'source') }));
};
api.blocked = () => {
  const u = location.href, t = (document.title + ' ' + (document.body ? document.body.innerText.slice(0, 3000) : '')).toLowerCase();
  if (/google\.[a-z.]+\/sorry\//.test(u) || /tráfego incomum|unusual traffic/.test(t)) return 'google: verificação anti-robô';
  if (document.querySelector('iframe[src*="recaptcha"], iframe[src*="hcaptcha"], .g-recaptcha, .h-captcha, #captcha-form')) return 'captcha na página';
  if (/cf-chl|challenge-platform/.test(document.documentElement.innerHTML.slice(0, 20000)) || /just a moment|verificando se você é humano|verify you are human/.test(t)) return 'cloudflare: verificação';
  if (/amazon\./.test(u) && (/validatecaptcha|errors\/validatecaptcha/.test(u) || /digite os caracteres|type the characters|robot check/.test(t))) return 'amazon: captcha';
  if (/if this persists, please email us|blocked by network security|you've been blocked|acesso negado|access denied|request blocked/.test(t)) return 'site bloqueou o acesso automático';
  if (/amazon\./.test(u) && /algo deu errado|something went wrong/.test(t) && !document.querySelector("[data-component-type='s-search-result']")) return 'amazon: bloqueio temporário';
  if (/mercadolivre\.com\.br\/gz\/account-verification|mercadolibre\.com.*account-verification/.test(u)) return 'mercado livre: pede login/verificação da conta';
  if (/are you a robot|você é um robô|confirme que você é humano|account-verification|security check/.test(t)) return 'verificação anti-robô';
  return '';
};
/**
 * WhatsApp Web (WA-JS): espera a página e o WPP ficarem prontos, assume a janela se o WhatsApp estiver aberto em outra
 * ("Usar nesta janela") e confirma o login. Lança erro claro se não houver sessão (falta ler o QR no Browser).
 */
api.waReady = async (ms = 40000, args) => {
  api.__policy = (args && args.__wa_policy) || api.__policy || null;
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const b = Array.from(document.querySelectorAll('button, div[role=button]')).find(x => /usar nesta janela|use here|usar aqui/i.test(x.innerText || ''));
    if (b) b.click();
    if (document.querySelector('canvas[aria-label*="QR"], div[data-ref] canvas, [data-testid="qrcode"]')) throw new Error('WhatsApp sem login: abra o Browser, vá em web.whatsapp.com (modo desktop) e leia o QR code com o celular.');
    if (window.WPP && WPP.isReady) {
      if (WPP.conn && WPP.conn.isAuthenticated && !WPP.conn.isAuthenticated()) throw new Error('WhatsApp sem login: leia o QR code em web.whatsapp.com no Browser do app.');
      api.waQuiet();
      return true;
    }
    await sleep(300);
  }
  // O app recarrega a página e tenta de novo uma vez quando vê esta mensagem.
  throw new Error('WA_NOT_READY: WhatsApp Web não ficou pronto a tempo (página carregando, sem internet ou WA-JS incompatível).');
};
/** Só dígitos; número brasileiro sem DDI (10–11 dígitos) ganha 55. */
api.waDigits = (s) => { let d = String(s || '').replace(/\D/g, ''); if ((d.length === 10 || d.length === 11) && !d.startsWith('55')) d = '55' + d; return d; };
/** Regras do usuário (Configurações do WhatsApp no hub): contatos bloqueados / só permitidos. */
api.waAllowed = (id) => {
  const p = api.__policy; if (!p) return true;
  const u = String(id || '').split('@')[0].replace(/\D/g, '') || String(id || '');
  const m = (list) => (list || []).some(x => { const d = String(x).replace(/\D/g, ''); return d ? (u.endsWith(d) || d.endsWith(u)) : String(id).toLowerCase().includes(String(x).toLowerCase()); });
  if (m(p.blocked)) return false;
  if (p.allow_only && p.allow_only.length && !m(p.allow_only)) return false;
  if (p.no_groups && String(id).endsWith('@g.us')) return false;
  return true;
};
api.waCheck = (id) => { if (!api.waAllowed(id)) throw new Error('contato bloqueado nas configurações do WhatsApp do app: não posso interagir com ' + id); return id; };
/** Acha a conversa existente de um número, também quando ela está indexada por LID. */
api.waFindChat = async (digits) => {
  let list = []; try { list = await WPP.chat.list(); } catch (e) {}
  return list.find(c => c && c.id && (c.id.user === digits || (c.contact && c.contact.phoneNumber && String(c.contact.phoneNumber.user || c.contact.phoneNumber._serialized || '').startsWith(digits))
    || (c.contact && c.contact.id && c.contact.id.user === digits))) || null;
};
/** Id de conversa a partir de número (com ou sem DDI), id ou nome. Prefere a conversa existente (inclusive @lid). */
api.waChatId = async (to) => {
  const s = String(to || '').trim();
  if (!s) throw new Error('informe "to" (número com DDI, id do chat ou nome do contato)');
  if (s.includes('@')) return api.waCheck(s);
  const raw = s.replace(/[\s()+.-]/g, '');
  if (/^\d{10,15}$/.test(raw)) {
    const digits = api.waDigits(raw);
    let hit = await api.waFindChat(digits);
    // Celular BR com/sem o 9: tenta a outra forma.
    if (!hit && digits.startsWith('55') && digits.length === 13) hit = await api.waFindChat(digits.slice(0, 4) + digits.slice(5));
    if (!hit && digits.startsWith('55') && digits.length === 12) hit = await api.waFindChat(digits.slice(0, 4) + '9' + digits.slice(4));
    if (hit) return api.waCheck(hit.id._serialized);
    try { const ex = await WPP.contact.queryExists(digits + '@c.us'); if (ex && ex.wid) return api.waCheck(ex.wid._serialized || String(ex.wid)); } catch (e) {}
    return api.waCheck(digits + '@c.us');
  }
  const chats = await WPP.chat.list();
  const low = s.toLowerCase();
  const nm = c => ((c.name || c.formattedTitle || (c.contact && (c.contact.name || c.contact.pushname)) || '') + '').toLowerCase();
  const hit = chats.find(c => nm(c) === low) || chats.find(c => nm(c).includes(low));
  if (!hit) throw new Error('nenhuma conversa com "' + s + '" (use o número com DDI ou o nome exato)');
  return api.waCheck(hit.id._serialized);
};
api.waChat = (c) => {
  const msgs = c.msgs && c.msgs.getModelsArray ? c.msgs.getModelsArray() : [];
  const last = msgs[msgs.length - 1];
  return { id: c.id && c.id._serialized, name: c.name || c.formattedTitle || (c.contact && (c.contact.name || c.contact.pushname)) || (c.id && c.id.user) || '',
    is_group: !!c.isGroup, unread: c.unreadCount || 0, archived: !!c.archive, pinned: !!c.pin, muted: !!(c.mute && c.mute.expiration),
    last_time: c.t ? new Date(c.t * 1000).toISOString() : null, last_message: last ? api.waMsg(last) : null };
};
api.waMsg = (m) => ({ id: m.id && (m.id._serialized || m.id), from_me: !!((m.id && m.id.fromMe) || m.fromMe || (typeof m.id === 'string' && m.id.startsWith('true_')) || (m.id && typeof m.id._serialized === 'string' && m.id._serialized.startsWith('true_'))), author: (m.author && (m.author._serialized || m.author)) || (m.from && (m.from._serialized || m.from)) || null,
  sender_name: (m.senderObj && (m.senderObj.name || m.senderObj.pushname)) || m.notifyName || null,
  type: m.type, text: m.body && m.type === 'chat' ? m.body : (m.caption || (m.type !== 'chat' ? '[' + m.type + ']' : '')),
  time: m.t ? new Date(m.t * 1000).toISOString() : null, ack: m.ack });

/* ───────────── Requisições do próprio site (o site como caminho, nunca chamada direta de fora) ─────────────
 * api.requests(padrão)       → URLs de fetch/XHR que a página já fez (Resource Timing).
 * api.watch(padrão)          → passa a gravar respostas fetch/XHR cujo URL casa com o padrão (regex em texto).
 * api.waitResponse(padrão,ms)→ espera a próxima resposta gravada que casa e devolve {url,status,json|text}.
 * api.replay(urlOuPadrão,opt)→ refaz, DE DENTRO da página (mesmos cookies/sessão/origem), uma requisição que o site fez,
 *                              opcionalmente trocando parâmetros: opt.params = {q: "novo termo", page: 2}.
 */
api.requests = (pattern) => {
  const re = pattern ? new RegExp(pattern) : null;
  return performance.getEntriesByType('resource').filter(e => /fetch|xmlhttprequest/.test(e.initiatorType) && (!re || re.test(e.name))).map(e => e.name);
};
api.watch = (pattern) => {
  window.__fluwyNet = window.__fluwyNet || [];
  window.__fluwyPat = window.__fluwyPat || [];
  if (pattern && !window.__fluwyPat.includes(pattern)) window.__fluwyPat.push(pattern);
  if (window.__fluwyHooked) return true;
  window.__fluwyHooked = true;
  const match = u => window.__fluwyPat.some(p => new RegExp(p).test(u));
  const keep = (url, status, text) => { try { window.__fluwyNet.push({ url, status, text: String(text).slice(0, 2000000), t: Date.now() }); if (window.__fluwyNet.length > 60) window.__fluwyNet.shift(); } catch (e) {} };
  const of = window.fetch;
  window.fetch = async function (input, init) {
    const res = await of.apply(this, arguments);
    try { const u = typeof input === 'string' ? input : (input && input.url) || ''; if (match(u)) res.clone().text().then(t => keep(res.url || u, res.status, t)).catch(() => {}); } catch (e) {}
    return res;
  };
  const oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this.__fu = u; return oo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener('load', () => { try { const u = this.responseURL || this.__fu || ''; if (match(u)) keep(u, this.status, this.responseType === '' || this.responseType === 'text' ? this.responseText : JSON.stringify(this.response)); } catch (e) {} });
    return os.apply(this, arguments);
  };
  return true;
};
api.waitResponse = async (pattern, ms = 12000) => {
  api.watch(pattern);
  const re = new RegExp(pattern), t0 = Date.now(), since = t0 - 50;
  while (Date.now() - t0 < ms) {
    const hit = (window.__fluwyNet || []).filter(x => x.t >= since && re.test(x.url)).pop();
    if (hit) { let json = null; try { json = JSON.parse(hit.text.replace(/^\)\]\}',?\s*/, '')); } catch (e) {} return { url: hit.url, status: hit.status, json, text: json ? null : hit.text }; }
    await sleep(150);
  }
  throw new Error('a página não fez nenhuma requisição que case com ' + pattern + ' em ' + ms + 'ms');
};
api.replay = async (urlOrPattern, opt = {}) => {
  let u = /^https?:/.test(urlOrPattern) ? urlOrPattern : api.requests(urlOrPattern).pop();
  if (!u) throw new Error('a página ainda não fez requisição que case com ' + urlOrPattern);
  const url = new URL(u, location.href);
  Object.entries(opt.params || {}).forEach(([k, v]) => url.searchParams.set(k, v));
  const r = await fetch(url.href, Object.assign({ credentials: 'include' }, opt.init || {}));
  const text = await r.text();
  let json = null; try { json = JSON.parse(text.replace(/^\)\]\}',?\s*/, '')); } catch (e) {}
  return { url: url.href, status: r.status, json, text: json ? null : text };
};
/**
 * Proteção comum de TODOS os envios do WhatsApp (texto, mídia, localização, contato, enquete, encaminhar):
 * pedido explícito (confirmed=true), um destinatário, ritmo humano (12 s), 20/h, 120/dia, sem conteúdo repetido
 * para várias conversas e só conversas existentes (salvo allow_new). Registra o envio só depois de confirmado.
 */
api.waGuard = async (args, chatId, fingerprint) => {
  if (String(args.confirmed) !== 'true' && String(args.__dry_run) !== 'true') throw new Error('envio recusado: só envie quando o usuário pedir explicitamente esta ação para este contato (confirmed=true).');
  if (chatId.endsWith('@broadcast') || /[,;]/.test(String(args.to || ''))) throw new Error('um destinatário por vez: envio em massa não é permitido');
  const KEY = '__fluwy_wa_sends', now = Date.now();
  let log = []; try { log = JSON.parse(localStorage.getItem(KEY) || '[]').filter(x => now - x.t < 86400000); } catch (e) {}
  const last = log.length ? log[log.length - 1].t : 0;
  // Ritmo humano: em vez de falhar, espera o intervalo que falta (máx. 12 s).
  const dry = String(args.__dry_run) === 'true';
  if (!dry && now - last < 12000) await sleep(12000 - (now - last) + Math.floor(Math.random() * 1500));
  if (log.filter(x => now - x.t < 3600000).length >= 20) throw new Error('limite de 20 envios por hora atingido');
  if (log.length >= 120) throw new Error('limite de 120 envios por dia atingido');
  const h = String(fingerprint || '').toLowerCase().replace(/\s+/g, ' ').slice(0, 200);
  if (h && new Set(log.filter(x => now - x.t < 3600000 && x.h === h && x.to !== chatId).map(x => x.to)).size >= 2)
    throw new Error('mesmo conteúdo já enviado para outras conversas na última hora: repetição para várias pessoas é o principal motivo de bloqueio');
  let chat = null; try { chat = await WPP.chat.get(chatId); } catch (e) {}
  if (!chat) chat = await api.waFindChat(chatId.split('@')[0]);
  if (!chat && String(args.allow_new) !== 'true') throw new Error('não há conversa com este contato; para iniciar uma nova use allow_new=true (só para quem espera sua mensagem)');
  if (!chat && !chatId.endsWith('@g.us')) { const ex = await WPP.contact.queryExists(chatId); if (!ex) throw new Error('este número não tem WhatsApp'); }
  // Teste seguro (criador de APIs / teste de fluxo): tudo validado, nada enviado.
  if (dry) throw new Error('__DRYRUN_OK__ validado sem enviar: ' + chatId);
  return () => { log.push({ t: Date.now(), to: chatId, h }); try { localStorage.setItem(KEY, JSON.stringify(log)); } catch (e) {} };
};
/** "digitando…"/"gravando…" proporcional ao conteúdo, com variação. */
api.waTyping = async (chatId, size, recording) => {
  try { recording ? await WPP.chat.markIsRecording(chatId) : await WPP.chat.markIsComposing(chatId); } catch (e) {}
  await sleep(Math.min(500 + size * 15, 2500) + Math.floor(Math.random() * 400));
  try { await WPP.chat.markIsPaused(chatId); } catch (e) {}
  setTimeout(() => { try { WPP.conn.markAvailable(false); } catch (e) {} }, 1500);
};
/** Resultado do envio no formato do hub. */
api.waSent = (r, chatId, extra) => ({ chat: chatId, results: [Object.assign({ ok: true, id: r && (r.id && (r.id._serialized || r.id)) || null, chat: chatId, sent_at: new Date().toISOString() }, extra || {})] });
/** Opções comuns: responder a uma mensagem (reply_to = id da mensagem). */
api.waQuote = (args) => args.reply_to ? { quotedMsg: String(args.reply_to) } : {};
/** Blob → data URL (para baixar mídia). */
api.blobToDataUrl = (blob) => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = rej; r.readAsDataURL(blob); });

/** Envia com a função do WA-JS; se o WhatsApp exigir LID ("No LID for user"), resolve o LID e tenta de novo. */
api.waSend = async (fn, chatId, ...rest) => {
  try { return await fn(chatId, ...rest); }
  catch (e) {
    if (!/lid/i.test(String(e && e.message || e))) throw e;
    let ex = null; try { ex = await WPP.contact.queryExists(chatId); } catch (e2) {}
    const alt = ex && (ex.lid && (ex.lid._serialized || String(ex.lid)) || ex.wid && (ex.wid._serialized || String(ex.wid)));
    if (!alt || alt === chatId) throw new Error('o WhatsApp não abriu conversa com este número (sem LID). Abra a conversa uma vez no WhatsApp e tente de novo.');
    return await fn(alt, ...rest);
  }
};
/* ───────────── Webhook: mensagens recebidas em tempo real ───────────── */
api.waWatch = () => {
  if (window.__fluwyWaWatch) return 'ok';
  api.waQuiet();
  window.__fluwyInbox = window.__fluwyInbox || [];
  WPP.on('chat.new_message', (m) => {
    try {
      const chat = (m.id && m.id.remote && (m.id.remote._serialized || String(m.id.remote))) || (m.from && (m.from._serialized || String(m.from))) || '';
      const e = api.waMsg(m); e.chat = chat; e.is_group = chat.endsWith('@g.us');
      try { const c = WPP.chat.get(chat); const cc = c && (c.then ? null : c); if (cc) e.chat_name = cc.name || cc.formattedTitle || ''; } catch (x) {}
      e.has_media = !!(m.isMedia || m.mediaData || /image|video|audio|ptt|document|sticker/.test(m.type || ''));
      if (m.type === 'location') { e.lat = m.lat; e.lng = m.lng; }
      window.__fluwyInbox.push(e);
      if (window.__fluwyInbox.length > 500) window.__fluwyInbox.splice(0, window.__fluwyInbox.length - 500);
    } catch (x) {}
  });
  window.__fluwyWaWatch = true;
  return 'ok';
};
api.waDrain = () => { const q = window.__fluwyInbox || []; window.__fluwyInbox = []; return JSON.stringify(q); };

/**
 * Modo silencioso do WhatsApp Web (aparelho conectado "invisível"): sem som, sem notificação do navegador,
 * aba sempre "em segundo plano" e presença indisponível — assim o WhatsApp do celular continua recebendo
 * as notificações na hora (com o Web "online", o servidor segura o push do celular).
 */
api.waQuiet = () => {
  try { WPP.conn.markAvailable(false); } catch (e) {}
  if (window.__fluwyQuiet) return;
  window.__fluwyQuiet = true;
  try { const N = function () { return { close() {}, addEventListener() {} }; }; N.permission = 'denied'; N.requestPermission = () => Promise.resolve('denied');
    Object.defineProperty(window, 'Notification', { value: N, configurable: true, writable: true }); } catch (e) {}
  try { HTMLMediaElement.prototype.play = function () { try { this.muted = true; this.pause(); } catch (e) {} return Promise.resolve(); }; } catch (e) {}
  try { if (window.AudioContext) window.AudioContext.prototype.resume = function () { return Promise.resolve(); }; } catch (e) {}
  try { Object.defineProperty(document, 'hidden', { get: () => true, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: () => 'hidden', configurable: true });
    document.hasFocus = () => false; window.dispatchEvent(new Event('blur')); document.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
  // Reafirma "indisponível" (o WhatsApp volta a online ao digitar/enviar).
  setInterval(() => { try { WPP.conn.markAvailable(false); } catch (e) {} }, 45000);
};
/**
 * Descoberta de API (criador de Web APIs): lista as requisições fetch/XHR que a página fez, refaz de DENTRO da
 * página as que parecem dados (JSON) e mostra um trecho de cada resposta; também expõe estados JSON embutidos
 * (ytInitialPlayerResponse, __NEXT_DATA__, __NUXT__, __INITIAL_STATE__, application/ld+json…).
 */
api.probe = async (pattern, max = 8) => {
  const re = pattern ? new RegExp(pattern, 'i') : null;
  const skip = /\.(png|jpe?g|gif|webp|svg|woff2?|ttf|css|mp4|m4s|ts|webm|ico)(\?|$)|google-analytics|googletagmanager|doubleclick|facebook\.net\/tr|hotjar|sentry|beacon|\/log(ging)?\b|\/collect\b|\/ping\b|stats\?/i;
  const seen = new Set();
  const all = performance.getEntriesByType('resource').filter(e => /fetch|xmlhttprequest/.test(e.initiatorType)).map(e => e.name)
    .filter(u => !skip.test(u) && (!re || re.test(u))).filter(u => { const k = u.split('?')[0]; if (seen.has(k)) return false; seen.add(k); return true; });
  const requests = [];
  for (const u of all.slice(-max)) {
    try {
      const r = await fetch(u, { credentials: 'include' });
      const ct = r.headers.get('content-type') || '';
      const t = await r.text();
      requests.push({ url: u.slice(0, 400), status: r.status, type: ct.split(';')[0], size: t.length, sample: t.replace(/\s+/g, ' ').slice(0, 500) });
    } catch (e) { requests.push({ url: u.slice(0, 400), error: String(e.message || e).slice(0, 120) }); }
  }
  const embedded = {};
  for (const k of ['ytInitialPlayerResponse', 'ytInitialData', '__NEXT_DATA__', '__NUXT__', '__INITIAL_STATE__', '__APOLLO_STATE__', '__PRELOADED_STATE__', 'initialState']) {
    try { const v = window[k]; if (v && typeof v === 'object') embedded[k] = Object.keys(v).slice(0, 25); } catch (e) {}
  }
  const nd = document.getElementById('__NEXT_DATA__'); if (nd && !embedded.__NEXT_DATA__) { try { embedded.__NEXT_DATA__ = Object.keys(JSON.parse(nd.textContent).props || {}); } catch (e) {} }
  const ld = Array.from(document.querySelectorAll('script[type="application/ld+json"]')).map(s => s.textContent.replace(/\s+/g, ' ').slice(0, 300)).slice(0, 3);
  return { page: location.href, title: document.title, total_requests: all.length, requests, embedded, ld_json: ld };
};
