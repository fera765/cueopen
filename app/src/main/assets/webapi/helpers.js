const enc = encodeURIComponent;
const sleep = ms => new Promise(r => setTimeout(r, ms));
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
