// Valida a WA-JS no WhatsApp Web real (sem login): injeção, prontidão e existência das funções usadas pelas APIs do hub.
import { chromium } from 'playwright';
import fs from 'fs';
const helpers = fs.readFileSync(process.env.ASSETS + '/helpers.js', 'utf8');
const lib = await (await fetch('https://cdn.jsdelivr.net/npm/@wppconnect/wa-js/dist/wppconnect-wa.js')).text();
console.log('wa-js bytes', lib.length);
const b = await chromium.launch();
const p = await (await b.newContext({ locale: 'pt-BR', viewport: { width: 1366, height: 900 }, userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36' })).newPage();
await p.goto('https://web.whatsapp.com/', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(8000);
// Injeção igual ao app (código cru, não via <script>, que a CSP bloquearia).
await p.evaluate(lib + '\n;window.__inj=true;1');
const t0 = Date.now(); let ready = false;
while (Date.now() - t0 < 60000) { ready = await p.evaluate(() => !!(window.WPP && WPP.isReady)); if (ready) break; await p.waitForTimeout(1000); }
console.log('WPP.isReady', ready, ((Date.now() - t0) / 1000) + 's', 'version', await p.evaluate(() => window.WPP && WPP.version));
const need = { conn: ['isAuthenticated', 'getMyUserId'], chat: ['list', 'get', 'getMessages', 'sendTextMessage', 'markIsRead', 'markIsComposing', 'markIsPaused'], contact: ['list', 'queryExists'], group: ['getAllGroups'] };
const have = await p.evaluate((need) => Object.fromEntries(Object.entries(need).map(([ns, fns]) => [ns, fns.map(f => f + '=' + (window.WPP && WPP[ns] && typeof WPP[ns][f]))])), need);
console.log('FUNCOES', JSON.stringify(have));
console.log('auth', await p.evaluate(() => { try { return WPP.conn.isAuthenticated() } catch (e) { return 'erro ' + e.message } }));
const r = await p.evaluate(`(async()=>{${helpers};try{await api.waReady(20000);return 'pronto'}catch(e){return 'waReady: '+e.message}})()`);
console.log('waReady sem login →', r);
await b.close();
