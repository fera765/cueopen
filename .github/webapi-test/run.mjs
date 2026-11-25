// Testa as Web APIs nativas num Chrome real (mesmos helpers e código do app). Uso: node run.mjs [slug...]
import { chromium, devices } from 'playwright';
import fs from 'fs';
const root = process.env.ASSETS || 'app/src/main/assets/webapi';
const apis = JSON.parse(fs.readFileSync(`${root}/builtins.json`, 'utf8'));
const helpers = fs.readFileSync(`${root}/helpers.js`, 'utf8');
const only = process.argv.slice(2);
const cases = { google_search: [{ query: 'openai noticias' }, { query: 'openai noticias', page: 2 }], wikipedia_search: [{ query: 'inteligência artificial' }],
  github_search: [{ query: 'kotlin android' }, { query: 'kotlin android', page: 2 }] };
const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Pixel 7'], locale: 'pt-BR',
  userAgent: 'Mozilla/5.0 (Linux; Android 12; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36' });
await ctx.route('**/*', r => ['image', 'font', 'media'].includes(r.request().resourceType()) ? r.abort() : r.continue());
fs.mkdirSync('out', { recursive: true });
let fails = 0;
for (const a of apis) {
  if (only.length && !only.includes(a.slug)) continue;
  const list = cases[a.slug] || [{ query: 'notebook' }, ...(a.params.some(p => p.name === 'page') ? [{ query: 'notebook', page: 2 }] : [])];
  for (const args0 of list) {
    const args = { ...Object.fromEntries(a.params.filter(p => p.default).map(p => [p.name, p.default])), ...args0 };
    const page = await ctx.newPage(); const t0 = Date.now(); let res;
    try {
      const url = await page.evaluate(`(function(){${helpers};const args=${JSON.stringify(args)};return \`${a.urlTemplate}\`})()`);
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
      if (a.waitFor) await page.waitForSelector(a.waitFor, { timeout: 9000 }).catch(() => {});
      res = await page.evaluate(`(async()=>{${helpers};const args=${JSON.stringify(args)};const b=api.blocked();if(b)return{blocked:b,url:location.href};
        try{return {url:location.href,data:await (async function(args,api){\n${a.code}\n})(args,api)}}catch(e){return{error:String(e&&e.message||e),url:location.href}}})()`);
    } catch (e) { res = { error: String(e.message || e) }; }
    const d = res.data || {}; const n = (d.results || []).length;
    const ok = n > 0 && !res.error && !res.blocked;
    if (!ok) { fails++; fs.writeFileSync(`out/${a.slug}-${args.page || 1}.html`, await page.content().catch(() => ''));
      // Raio-x: título, texto inicial e os links de resultado prováveis com a cadeia de ancestrais (tag.classe#id[data-*]).
      const xr = await page.evaluate(() => {
        const sig = e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (typeof e.className === 'string' && e.className.trim() ? '.' + e.className.trim().split(/\s+/).slice(0, 3).join('.') : '') +
          Array.from(e.attributes).filter(x => /^data-|^role$|^aria-label$/.test(x.name)).slice(0, 3).map(x => `[${x.name}=${String(x.value).slice(0, 25)}]`).join('');
        const chain = e => { const out = []; for (let n = e, k = 0; n && n !== document.body && k < 7; n = n.parentElement, k++) out.push(sig(n)); return out.join(' < '); };
        const links = Array.from(document.querySelectorAll('h2 a, a h2, h3 a, a h3, a[href*="/dp/"], a[href*="uddg"], a.result__a')).slice(0, 4);
        return { title: document.title, text: document.body ? document.body.innerText.replace(/\s+/g, ' ').slice(0, 300) : '', n: document.querySelectorAll('*').length,
          links: links.map(l => (l.innerText || '').slice(0, 50) + ' || ' + chain(l)) };
      }).catch(e => ({ err: String(e) }));
      console.log('     XR: ' + JSON.stringify(xr).slice(0, 2500)); }
    console.log(`${ok ? 'OK  ' : 'FAIL'} ${a.slug} ${JSON.stringify(args0)} n=${n} has_more=${d.has_more} ${Date.now() - t0}ms ${res.blocked ? 'BLOCKED:' + res.blocked : ''} ${res.error || ''} ${res.url || ''}`);
    if (n) console.log('     1º: ' + JSON.stringify(d.results[0]).slice(0, 400));
    await page.close();
  }
}
await browser.close();
console.log(`falhas: ${fails}`);
