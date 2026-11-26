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
api.waReady = async (ms = 90000) => {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const b = Array.from(document.querySelectorAll('button, div[role=button]')).find(x => /usar nesta janela|use here|usar aqui/i.test(x.innerText || ''));
    if (b) b.click();
    if (document.querySelector('canvas[aria-label*="QR"], div[data-ref] canvas, [data-testid="qrcode"]')) throw new Error('WhatsApp sem login: abra o Browser, vá em web.whatsapp.com (modo desktop) e leia o QR code com o celular.');
    if (window.WPP && WPP.isReady) {
      if (WPP.conn && WPP.conn.isAuthenticated && !WPP.conn.isAuthenticated()) throw new Error('WhatsApp sem login: leia o QR code em web.whatsapp.com no Browser do app.');
      return true;
    }
    await sleep(400);
  }
  throw new Error('WhatsApp Web não ficou pronto a tempo (página carregando, sem internet ou WA-JS incompatível com esta versão do WhatsApp).');
};
/** Id de conversa a partir de número, id ou nome. */
api.waChatId = async (to) => {
  const s = String(to || '').trim();
  if (!s) throw new Error('informe "to" (número com DDI, id do chat ou nome do contato)');
  if (s.includes('@')) return s;
  const digits = s.replace(/\D/g, '');
  if (digits.length >= 10 && digits.length === s.replace(/[\s()+-]/g, '').length) return digits + '@c.us';
  const chats = await WPP.chat.list();
  const low = s.toLowerCase();
  const hit = chats.find(c => ((c.name || c.formattedTitle || (c.contact && (c.contact.name || c.contact.pushname)) || '') + '').toLowerCase() === low)
    || chats.find(c => ((c.name || c.formattedTitle || (c.contact && (c.contact.name || c.contact.pushname)) || '') + '').toLowerCase().includes(low));
  if (!hit) throw new Error('nenhuma conversa com "' + s + '"');
  return hit.id._serialized;
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
