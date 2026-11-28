// Valida api.watch / api.waitResponse / api.requests / api.replay num site real (busca da Wikipédia).
import { chromium } from 'playwright';
import fs from 'fs';
const helpers = fs.readFileSync(process.env.ASSETS + '/helpers.js', 'utf8');
const b = await chromium.launch();
const p = await (await b.newContext({ locale: 'pt-BR', viewport: { width: 1366, height: 900 } })).newPage();
await p.goto('https://pt.wikipedia.org/wiki/Brasil', { waitUntil: 'domcontentloaded' });
await p.waitForTimeout(2500);
const r = await p.evaluate(`(async()=>{${helpers};
  api.watch('search|opensearch|rest\\\\.php');
  const inp = document.querySelector('input[name=search]');
  inp.focus(); inp.value = 'futebol'; inp.dispatchEvent(new Event('input', {bubbles:true})); inp.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, key:'l'}));
  let got; try { got = await api.waitResponse('search|opensearch|rest\\\\.php', 10000); } catch(e) { got = {erro: e.message}; }
  const reqs = api.requests('search|opensearch|rest\\\\.php');
  let rep = null; if (reqs.length) { const u = new URL(reqs[reqs.length-1]); const key = u.searchParams.has('q') ? 'q' : (u.searchParams.has('search') ? 'search' : null);
    rep = await api.replay(reqs[reqs.length-1], key ? {params: {[key]: 'cinema'}} : {}); }
  return { capturada: got.url || got.erro, status: got.status, temJson: !!got.json, requests: reqs.slice(-3), replayUrl: rep && rep.url, replayStatus: rep && rep.status, replayTrecho: rep && JSON.stringify(rep.json || rep.text).slice(0, 200) };
})()`);
console.log('NET', JSON.stringify(r, null, 1));
await b.close();
