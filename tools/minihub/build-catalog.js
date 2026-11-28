'use strict'
/*
 * Gera o catálogo do mini-hub verificando cada piece de verdade (roda no CI, com rede):
 *   1. lista todas as @activepieces/piece-* do npm;
 *   2. instala em lotes na mesma estrutura que o celular usa;
 *   3. carrega cada uma pelo próprio mini-hub (opMetadata) — só entra no catálogo o que carrega;
 *   4. executa ações sem conta e sem campos obrigatórios (smoke do contexto) e monta URLs OAuth;
 *   5. grava catalog.json (versão verificada de cada piece) + relatório.
 *
 * Uso: node tools/minihub/build-catalog.js <saida/catalog.json> [relatorio.json] [--limit N]
 */
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const out = process.argv[2]
const reportPath = process.argv[3] && !process.argv[3].startsWith('--') ? process.argv[3] : null
const limitArg = process.argv.indexOf('--limit')
const LIMIT = limitArg > 0 ? Number(process.argv[limitArg + 1]) : Infinity
if (!out) { console.error('uso: build-catalog.js <catalog.json> [report.json] [--limit N]'); process.exit(2) }

const root = process.env.MINIHUB_ROOT || fs.mkdtempSync(path.join(os.tmpdir(), 'minihub-catalog-'))
process.env.MINIHUB_ROOT = root
process.env.MINIHUB_PORT = process.env.MINIHUB_PORT || '18791'
process.env.MINIHUB_RUN_TIMEOUT_MS = '10000'
const hub = require('../../app/src/main/assets/minihub/minihub.js')
const REGISTRY = 'https://registry.npmjs.org'

/** Lista as pieces pelo código-fonte do Activepieces (o índice de busca do npm limita por IP). */
function listFromGit() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ap-src-'))
    const git = (args) => spawnSync('git', args, { cwd: dir, encoding: 'utf8', timeout: 10 * 60 * 1000 })
    let r = git(['clone', '--depth', '1', '--filter=blob:none', '--no-checkout', 'https://github.com/activepieces/activepieces', '.'])
    if (r.status !== 0) throw new Error(`git clone: ${r.stderr}`)
    git(['sparse-checkout', 'init', '--no-cone'])
    git(['sparse-checkout', 'set', '/packages/pieces/community/*/package.json', '/packages/pieces/core/*/package.json'])
    r = git(['checkout'])
    if (r.status !== 0) throw new Error(`git checkout: ${r.stderr}`)
    const names = new Map()
    for (const group of ['community', 'core']) {
        const base = path.join(dir, 'packages', 'pieces', group)
        if (!fs.existsSync(base)) continue
        for (const d of fs.readdirSync(base)) {
            try {
                const pj = JSON.parse(fs.readFileSync(path.join(base, d, 'package.json'), 'utf8'))
                if (pj.name && pj.name.startsWith('@activepieces/piece-') && !pj.private) names.set(pj.name, pj.version)
            } catch { /* pasta sem package.json */ }
        }
    }
    fs.rmSync(dir, { recursive: true, force: true })
    return names
}

async function fetchRetry(url, tries = 6) {
    for (let i = 0; ; i++) {
        const r = await fetch(url)
        if (r.status !== 429 && r.status < 500) return r
        if (i >= tries) return r
        await new Promise((ok) => setTimeout(ok, 1500 * 2 ** i))
    }
}

async function listPieces() {
    const names = listFromGit()
    if (names.size > 100) return names
    throw new Error(`código-fonte do Activepieces listou só ${names.size} pieces`)
}

/** Confirma a "latest" de cada uma (a busca às vezes atrasa) e descarta pacotes deprecados. */
async function latest(name) {
    const r = await fetchRetry(`${REGISTRY}/${name.replace('/', '%2f')}/latest`)
    if (!r.ok) return null
    const j = await r.json()
    if (j.deprecated) return null
    return j.version || null
}

async function pool(items, size, fn) {
    const results = new Array(items.length)
    let i = 0
    await Promise.all(Array.from({ length: size }, async () => { while (i < items.length) { const k = i++; results[k] = await fn(items[k], k) } }))
    return results
}

function npmInstall(specs) {
    const pkg = path.join(root, 'pkgs')
    fs.mkdirSync(pkg, { recursive: true })
    if (!fs.existsSync(path.join(pkg, 'package.json'))) fs.writeFileSync(path.join(pkg, 'package.json'), JSON.stringify({ name: 'fluwy-minihub-pieces', private: true, version: '1.0.0' }))
    const r = spawnSync('npm', ['install', ...specs, '--prefix', pkg, '--save-exact', '--omit=dev', '--no-audit', '--no-fund', '--legacy-peer-deps', '--loglevel=error'], { encoding: 'utf8', timeout: 30 * 60 * 1000, maxBuffer: 64 * 1024 * 1024 })
    return { ok: r.status === 0, out: `${r.stdout || ''}${r.stderr || ''}`.slice(-4000) }
}

function summary(meta) {
    const authTypes = (Array.isArray(meta.auth) ? meta.auth : meta.auth ? [meta.auth] : []).map((a) => a.type).filter(Boolean)
    const actions = Object.values(meta.actions || {}).map((a) => {
        const props = Object.entries(a.props || {})
        const required = props.filter(([, p]) => p && p.required && p.type !== 'MARKDOWN').map(([k]) => k)
        return [a.name, a.displayName || a.name, String(a.description || '').slice(0, 200), required]
    })
    return {
        name: meta.name,
        version: meta.version,
        displayName: meta.displayName,
        description: String(meta.description || '').slice(0, 300),
        logoUrl: meta.logoUrl || '',
        categories: Array.isArray(meta.categories) ? meta.categories : [],
        auth: authTypes,
        authOptional: !Array.isArray(meta.auth) && meta.auth ? meta.auth.required === false : false,
        actions,
        triggers: meta.triggers || 0,
    }
}

async function main() {
    const t0 = Date.now()
    const found = await listPieces()
    let names = [...found.keys()].sort()
    if (Number.isFinite(LIMIT)) {
        const must = ['@activepieces/piece-http', '@activepieces/piece-gmail', '@activepieces/piece-google-drive', '@activepieces/piece-slack', '@activepieces/piece-text-helper', '@activepieces/piece-date-helper', '@activepieces/piece-math-helper']
        names = [...new Set([...must.filter((m) => found.has(m)), ...names])].slice(0, LIMIT)
    }
    console.log(`Activepieces: ${found.size} pieces encontradas; verificando ${names.length}`)
    const versions = await pool(names, 6, async (n) => [n, await latest(n)])
    const targets = versions.filter(([, v]) => v)
    const skipped = versions.filter(([, v]) => !v).map(([n]) => ({ name: n, error: 'deprecado ou sem versão latest' }))

    // Instala em lotes; um lote que falha é refeito piece a piece para isolar o culpado.
    const installFail = new Map()
    const BATCH = 40
    for (let i = 0; i < targets.length; i += BATCH) {
        const batch = targets.slice(i, i + BATCH)
        const r = npmInstall(batch.map(([n, v]) => `${n}@${v}`))
        process.stdout.write(`lote ${i / BATCH + 1}/${Math.ceil(targets.length / BATCH)}: ${r.ok ? 'ok' : 'falhou, isolando'}\n`)
        if (!r.ok) {
            for (const [n, v] of batch) {
                const one = npmInstall([`${n}@${v}`])
                if (!one.ok) installFail.set(n, one.out.trim().split('\n').slice(-3).join(' | '))
            }
        }
    }

    const pieces = []
    const failures = [...skipped]
    const smoke = { ran: 0, ok: 0, failed: [] }
    const oauth = { checked: 0, failed: [] }
    let cloudApps = {}
    try { cloudApps = await (await fetch('https://secrets.activepieces.com/apps?edition=ce')).json() } catch (e) { console.log('aviso: sem lista de apps OAuth cloud:', e.message) }

    for (const [n, v] of targets) {
        if (installFail.has(n)) { failures.push({ name: n, version: v, error: `npm: ${installFail.get(n)}` }); continue }
        let meta
        try {
            meta = await hub.opMetadata({ name: n, version: v })
        } catch (e) {
            failures.push({ name: n, version: v, error: `não carrega: ${String(e.message || e).slice(0, 400)}` })
            continue
        }
        if (!meta.displayName || Object.keys(meta.actions || {}).length === 0) {
            failures.push({ name: n, version: v, error: meta.displayName ? 'sem ações (só gatilhos)' : 'sem displayName', triggersOnly: meta.triggers > 0 })
            continue
        }
        pieces.push(summary(meta))

        // Smoke: ações que não exigem conta nem campos — exercitam o contexto (store, files, server).
        const noAuth = !meta.auth
        for (const a of Object.values(meta.actions)) {
            const required = Object.values(a.props || {}).filter((p) => p && p.required && p.type !== 'MARKDOWN' && p.type !== 'CHECKBOX' && p.defaultValue === undefined)
            if ((!noAuth && a.requireAuth) || required.length > 0) continue
            smoke.ran++
            try {
                const r = await hub.opRun({ name: n, version: v, action: a.name, input: {} })
                if (r.ok) smoke.ok++
                else smoke.failed.push({ name: n, action: a.name, error: String(r.error).slice(0, 300) })
            } catch (e) {
                smoke.failed.push({ name: n, action: a.name, error: String(e.message || e).slice(0, 300) })
            }
        }

        // OAuth: a URL de autorização precisa ser montável com o client cloud do Activepieces.
        const oauthAuth = (Array.isArray(meta.auth) ? meta.auth : [meta.auth]).find((x) => x && x.type === 'OAUTH2')
        if (oauthAuth && cloudApps[n] && cloudApps[n].clientId) {
            oauth.checked++
            try { await hub.opOAuthUrl({ name: n, version: v, clientId: cloudApps[n].clientId, redirectUrl: 'https://secrets.activepieces.com/redirect', props: {} }) } catch (e) { oauth.failed.push({ name: n, error: String(e.message || e).slice(0, 300) }) }
        }
    }

    // Execução real ponta a ponta: piece HTTP faz um GET de verdade.
    const http = { ok: false, detail: '' }
    try {
        const meta = await hub.opMetadata({ name: '@activepieces/piece-http' })
        const action = Object.values(meta.actions).find((a) => a.props && a.props.url && a.props.method)
        if (action) {
            const input = { url: 'https://registry.npmjs.org/@activepieces%2fpieces-framework/latest', method: 'GET', headers: {}, queryParams: {} }
            const r = await hub.opRun({ name: '@activepieces/piece-http', action: action.name, input, raw: true })
            const body = r.output && (r.output.body || r.output)
            http.ok = r.ok && JSON.stringify(body).includes('pieces-framework')
            http.detail = r.ok ? `${action.name}: status ${r.output && r.output.status}` : String(r.error).slice(0, 500)
            // Mesma chamada no formato para agentes: JSON enxuto com status/summary/data.
            const shaped = await hub.opRun({ name: '@activepieces/piece-http', action: action.name, input })
            const size = shaped.result ? JSON.stringify(shaped.result).length : -1
            http.shaped = { ok: shaped.ok && shaped.result && shaped.result.status === 'ok' && size > 0 && size <= 4096, size }
            if (!http.shaped.ok) { http.ok = false; http.detail += ` · resumo para agentes inválido (${size} bytes)` }
        } else http.detail = 'ação de requisição não encontrada'
    } catch (e) { http.detail = String(e.message || e).slice(0, 500) }

    pieces.sort((a, b) => a.displayName.localeCompare(b.displayName))
    const catalog = { generatedAt: new Date().toISOString(), node: process.version, count: pieces.length, pieces }
    fs.mkdirSync(path.dirname(out), { recursive: true })
    fs.writeFileSync(out, JSON.stringify(catalog))
    const report = { tookSec: Math.round((Date.now() - t0) / 1000), listed: found.size, verified: pieces.length, failures, smoke, oauth, http }
    if (reportPath) fs.writeFileSync(reportPath, JSON.stringify(report, null, 2))

    console.log(`\ncatálogo: ${pieces.length} pieces verificadas de ${targets.length} (${failures.length} fora) em ${report.tookSec}s`)
    console.log(`smoke sem conta: ${smoke.ok}/${smoke.ran} ok; OAuth URLs: ${oauth.checked - oauth.failed.length}/${oauth.checked}; HTTP real: ${http.ok ? 'ok' : 'FALHOU'} (${http.detail})`)
    for (const f of failures.slice(0, 60)) console.log(`  fora: ${f.name}: ${f.error}`)
    for (const f of smoke.failed.slice(0, 40)) console.log(`  smoke: ${f.name}/${f.action}: ${f.error}`)
    for (const f of oauth.failed.slice(0, 40)) console.log(`  oauth: ${f.name}: ${f.error}`)

    const expected = Number.isFinite(LIMIT) ? Math.min(LIMIT, targets.length) : targets.length
    const ratio = pieces.length / Math.max(1, expected)
    const fatal = []
    if (!Number.isFinite(LIMIT) && pieces.length < 300) fatal.push(`só ${pieces.length} pieces verificadas`)
    if (ratio < 0.85) fatal.push(`só ${Math.round(ratio * 100)}% das pieces carregaram`)
    if (!http.ok) fatal.push('a execução real da piece HTTP falhou')
    if (oauth.checked > 0 && oauth.failed.length > oauth.checked * 0.1) fatal.push('muitas URLs OAuth falharam')
    if (fatal.length) { console.error(`\nFALHA: ${fatal.join('; ')}`); process.exit(1) }
    process.exit(0)
}

main().catch((e) => { console.error(e); process.exit(1) })
