// Testa as Web APIs nativas num Chrome real (mesmos helpers e código do app).
// Passa só com resultados na página 1 e, se houver paginação, página 2 com itens DIFERENTES.
import { chromium, devices } from 'playwright';
import fs from 'fs';
const root = process.env.ASSETS || 'app/src/main/assets/webapi';
const apis = JSON.parse(fs.readFileSync(`${root}/builtins.json`, 'utf8'));
const helpers = fs.readFileSync(`${root}/helpers.js`, 'utf8');
const only = process.argv.slice(2);
const Q = { wikipedia_search: 'inteligência artificial', github_search: 'kotlin android', google_search: 'notebook gamer', bing_news: 'tecnologia', google_news: 'tecnologia' };
const browser = await chromium.launch();
const mobile = await browser.newContext({ ...devices['Pixel 7'], locale: 'pt-BR', userAgent: 'Mozilla/5.0 (Linux; Android 12; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36' });
const desktop = await browser.newContext({ locale: 'pt-BR', viewport: { width: 1366, height: 900 }, userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36' });
for (const c of [mobile, desktop]) await c.route('**/*', r => ['image', 'font', 'media'].includes(r.request().resourceType()) ? r.abort() : r.continue());
async function call(a, args) {
  const page = await (a.desktop ? desktop : mobile).newPage(); let res;
  try {
    const url = await page.evaluate(`(function(){${helpers};const args=${JSON.stringify(args)};return \`${a.urlTemplate}\`})()`);
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    if (a.waitFor) await page.waitForSelector(a.waitFor, { timeout: 9000 }).catch(() => {});
    res = await page.evaluate(`(async()=>{${helpers};const args=${JSON.stringify(args)};const b=api.blocked();if(b)return{blocked:b,url:location.href};
      try{return {url:location.href,data:await (async function(args,api){\n${a.code}\n})(args,api)}}catch(e){return{error:String(e&&e.message||e),url:location.href}}})()`);
  } catch (e) { res = { error: String(e.message || e) }; }
  await page.close();
  return res;
}
const summary = [];
for (const a of apis) {
  if (only.length && !only.includes(a.slug)) continue;
  const base = { ...Object.fromEntries(a.params.filter(p => p.default).map(p => [p.name, p.default])), query: Q[a.slug] || 'notebook' };
  const r1 = await call(a, { ...base, page: 1 }); const n1 = ((r1.data || {}).results || []);
  let verdict = n1.length ? 'OK' : 'FAIL', note = r1.blocked ? 'BLOCKED:' + r1.blocked : (r1.error || '');
  if (n1.length && a.params.some(p => p.name === 'page')) {
    const r2 = await call(a, { ...base, page: 2 }); const n2 = ((r2.data || {}).results || []);
    const u1 = new Set(n1.map(x => x.url)); const overlap = n2.filter(x => u1.has(x.url)).length;
    if (!n2.length || overlap > n2.length / 2) { verdict = 'FAIL'; note = `pág2: ${n2.length} itens, ${overlap} repetidos da pág1 ${r2.blocked || r2.error || ''}`; }
    else note = `pág1 ${n1.length} · pág2 ${n2.length} (${overlap} repetidos) · has_more=${(r1.data || {}).has_more}`;
  }
  console.log(`${verdict.padEnd(4)} ${a.slug} ${note} ${r1.url || ''}`.slice(0, 400));
  if (n1.length) console.log('     1º: ' + JSON.stringify(n1[0]).slice(0, 300));
  summary.push(`${a.slug}=${verdict}`);
}
await browser.close();
console.log('RESUMO ' + summary.join(' '));
