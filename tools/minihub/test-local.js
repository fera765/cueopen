'use strict'
// Testes do mini-hub sem rede: monta pieces falsas com a mesma forma das reais (classe Piece).
// Uso: node tools/minihub/test-local.js
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const assert = require('node:assert/strict')
const http = require('node:http')

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'minihub-test-'))
process.env.MINIHUB_ROOT = root
process.env.MINIHUB_PORT = String(18000 + Math.floor(Math.random() * 2000))
process.env.MINIHUB_TOKEN = 'segredo-de-teste'
process.env.MINIHUB_NPM = '/bin/false' // nenhuma instalação real deve acontecer

const hub = require('../../app/src/main/assets/minihub/minihub.js')

function writePiece(name, version, source) {
    const dir = path.join(root, 'pkgs', 'node_modules', name)
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name, version, main: 'index.js' }))
    fs.writeFileSync(path.join(dir, 'index.js'), source)
}

const PIECE_CLASS = `
class Piece {
  constructor(o) { Object.assign(this, o); this._actions = {}; for (const a of o.actions) this._actions[a.name] = a }
  metadata() { return { displayName: this.displayName, logoUrl: this.logoUrl, description: this.description, actions: this._actions, triggers: {}, auth: this.auth, categories: [] } }
  getAction(n) { return this._actions[n] }
  actions() { return this._actions }
}`

writePiece('@activepieces/piece-fake', '1.2.3', `${PIECE_CLASS}
const auth = { type: 'SECRET_TEXT', displayName: 'API key', required: true, validate: async ({ auth }) => auth === 'boa' ? { valid: true } : { valid: false, error: 'chave ruim' } }
exports.fake = new Piece({
  displayName: 'Fake', description: 'piece de teste', logoUrl: 'x', auth,
  getContextInfo: () => ({ version: '2' }),
  actions: [
    { name: 'eco', displayName: 'Eco', description: 'devolve', requireAuth: true,
      props: {
        texto: { type: 'SHORT_TEXT', required: true, displayName: 'Texto' },
        n: { type: 'NUMBER', required: false, displayName: 'N' },
        flag: { type: 'CHECKBOX', required: false },
        obj: { type: 'JSON', required: false },
        tags: { type: 'STATIC_MULTI_SELECT_DROPDOWN', required: false, options: { options: [] } },
        quando: { type: 'DATE_TIME', required: false },
        padrao: { type: 'SHORT_TEXT', required: true, defaultValue: 'def' },
        itens: { type: 'ARRAY', required: false, properties: { a: { type: 'NUMBER', required: false } } },
        pasta: { type: 'DROPDOWN', required: false, refreshers: [], options: async (input, ctx) => ({ disabled: false, options: [{ label: 'A ' + (input.auth && input.auth.secret_text), value: 'a' }] }) },
      },
      run: async (ctx) => {
        await ctx.store.put('k', 41)
        const k = await ctx.store.get('k')
        const url = await ctx.files.write({ fileName: 'r.txt', data: Buffer.from('oi') })
        return { auth: ctx.auth, props: ctx.propsValue, k, url, buf: Buffer.from('ab'), date: new Date(0) }
      } },
    { name: 'falha', displayName: 'Falha', requireAuth: false, props: {}, run: async () => { throw new Error('quebrou de propósito') } },
    { name: 'lenta', displayName: 'Lenta', requireAuth: false, props: {}, run: () => new Promise(() => {}) },
  ],
})
`)

writePiece('@activepieces/piece-velha', '0.1.0', `${PIECE_CLASS}
exports.p = new Piece({
  displayName: 'Velha', auth: { type: 'CUSTOM_AUTH', props: { key: { type: 'SECRET_TEXT', required: true }, port: { type: 'NUMBER', required: false } } },
  actions: [{ name: 'quem', displayName: 'Quem', requireAuth: true, props: {}, run: async (ctx) => ({ auth: ctx.auth, serverUrl: ctx.serverUrl, hasResume: typeof ctx.generateResumeUrl }) }],
})
`)

writePiece('@activepieces/piece-oauth', '3.0.0', `${PIECE_CLASS}
exports.p = new Piece({
  displayName: 'OAuth', getContextInfo: () => ({ version: '2' }),
  auth: { type: 'OAUTH2', authUrl: 'https://ex.com/{tenant}/auth', tokenUrl: 'https://ex.com/{tenant}/token', scope: ['a', 'b'], pkce: true, pkceMethod: 'S256', extra: { x: '1' }, props: { tenant: { type: 'SHORT_TEXT', required: true } } },
  actions: [{ name: 'eu', displayName: 'Eu', requireAuth: true, props: {}, run: async (ctx) => ({ token: ctx.auth.access_token }) }],
})
`)

writePiece('@activepieces/piece-quebrada', '1.0.0', `module.exports = { naoEhPiece: 1 }`)

async function main() {
    // nomes
    assert.equal(hub.pieceName('Gmail'), '@activepieces/piece-gmail')
    assert.equal(hub.pieceName('piece-google-drive'), '@activepieces/piece-google-drive')
    assert.equal(hub.pieceName('@activepieces/piece-x'), '@activepieces/piece-x')

    // metadata
    const meta = await hub.opMetadata({ name: 'fake' })
    assert.equal(meta.version, '1.2.3')
    assert.equal(meta.displayName, 'Fake')
    assert.equal(meta.actions.eco.props.pasta.optionsDynamic, true)
    assert.equal(meta.auth.type, 'SECRET_TEXT')
    assert.equal(typeof meta.auth.validate, 'undefined')

    // run com processadores
    const conn = { type: 'SECRET_TEXT', secret_text: 'boa' }
    const r = await hub.opRun({ name: 'fake', action: 'eco', connection: conn, input: { texto: 5, n: '7', flag: 'TRUE', obj: '{"a":1}', tags: 'x', quando: '2024-01-02 10:00', itens: { a: ['1', '2'] } } })
    assert.equal(r.ok, true, r.error)
    assert.deepEqual(r.output.auth, conn) // V2 recebe o valor completo
    assert.equal(r.output.props.texto, '5')
    assert.equal(r.output.props.n, 7)
    assert.equal(r.output.props.flag, true)
    assert.deepEqual(r.output.props.obj, { a: 1 })
    assert.deepEqual(r.output.props.tags, ['x'])
    assert.equal(r.output.props.quando, '2024-01-02T10:00:00.000Z')
    assert.equal(r.output.props.padrao, 'def')
    assert.deepEqual(r.output.props.itens, [{ a: 1 }, { a: 2 }])
    assert.equal(r.output.k, 41)
    assert.match(r.output.url, /\/files\/[A-Za-z0-9_-]+$/)
    assert.equal(r.output.buf.base64, 'YWI=')
    assert.equal(r.output.date, '1970-01-01T00:00:00.000Z')
    assert.equal(r.connection, undefined)

    // obrigatório faltando
    const miss = await hub.opRun({ name: 'fake', action: 'eco', connection: conn, input: {} })
    assert.equal(miss.ok, false)
    assert.match(miss.error, /texto/)

    // sem conta
    await assert.rejects(hub.opRun({ name: 'fake', action: 'eco', input: { texto: 'a' } }), /conta conectada/)

    // ação que falha / ação inexistente / displayName
    const f = await hub.opRun({ name: 'fake', action: 'falha' })
    assert.equal(f.ok, false); assert.match(f.error, /de propósito/)
    await assert.rejects(hub.opRun({ name: 'fake', action: 'nada' }), /não existe/)
    const byDisplay = await hub.opRun({ name: 'fake', action: 'Falha' })
    assert.match(byDisplay.error, /de propósito/)

    // opções dinâmicas
    const o = await hub.opOptions({ name: 'fake', action: 'eco', prop: 'pasta', connection: conn, input: {} })
    assert.equal(o.ok, true); assert.equal(o.options.options[0].value, 'a')

    // validação de conta
    assert.deepEqual(await hub.opValidate({ name: 'fake', connection: conn }), { valid: true, error: undefined })
    const bad = await hub.opValidate({ name: 'fake', connection: { type: 'SECRET_TEXT', secret_text: 'x' } })
    assert.equal(bad.valid, false); assert.equal(bad.error, 'chave ruim')
    const wrong = await hub.opValidate({ name: 'fake', connection: { type: 'BASIC_AUTH', username: 'a', password: 'b' } })
    assert.equal(wrong.valid, false)

    // piece antiga (sem contextInfo) recebe o formato V0 e serverUrl
    const v = await hub.opRun({ name: 'velha', action: 'quem', connection: { type: 'CUSTOM_AUTH', props: { key: 'k', port: '8080' } } })
    assert.equal(v.ok, true, v.error)
    assert.deepEqual(v.output.auth, { key: 'k', port: 8080 })
    assert.match(v.output.serverUrl, /^http:\/\/127\.0\.0\.1:/)
    assert.equal(v.output.hasResume, 'function')

    // OAuth: URL com template, PKCE S256 e extra
    const u = await hub.opOAuthUrl({ name: 'oauth', clientId: 'cid', redirectUrl: 'https://r/x', props: { tenant: 'acme' } })
    const url = new URL(u.authorizationUrl)
    assert.equal(url.origin + url.pathname, 'https://ex.com/acme/auth')
    assert.equal(url.searchParams.get('client_id'), 'cid')
    assert.equal(url.searchParams.get('scope'), 'a b')
    assert.equal(url.searchParams.get('code_challenge_method'), 'S256')
    assert.equal(url.searchParams.get('x'), '1')
    assert.equal(u.tokenUrl, 'https://ex.com/acme/token')
    assert.equal(u.codeVerifier.length, 43)

    // token válido não é renovado; vencido sem refresh_token também não
    const fresh = { type: 'OAUTH2', access_token: 't', refresh_token: 'r', expires_in: 3600, claimed_at: Math.round(Date.now() / 1000) }
    assert.equal(hub.oauthExpired(fresh), false)
    assert.equal(hub.oauthExpired({ ...fresh, claimed_at: 1 }), true)
    assert.equal(hub.oauthExpired({ type: 'OAUTH2', access_token: 't', claimed_at: 1 }), false)
    const ro = await hub.opRun({ name: 'oauth', action: 'eu', connection: fresh })
    assert.equal(ro.output.token, 't')

    // renovação OAUTH2 própria contra um servidor de token local
    const tokenSrv = http.createServer((req, res) => {
        let b = ''; req.on('data', (c) => { b += c }); req.on('end', () => {
            const p = new URLSearchParams(b)
            assert.equal(p.get('grant_type'), 'refresh_token'); assert.equal(p.get('refresh_token'), 'r'); assert.equal(p.get('client_secret'), 'sec')
            res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ access_token: 'novo', expires_in: 3600 }))
        })
    })
    await new Promise((ok) => tokenSrv.listen(0, '127.0.0.1', ok))
    const expired = { type: 'OAUTH2', access_token: 'velho', refresh_token: 'r', expires_in: 3600, claimed_at: 1, client_id: 'cid', client_secret: 'sec', token_url: `http://127.0.0.1:${tokenSrv.address().port}/t` }
    const rr = await hub.opRun({ name: 'oauth', action: 'eu', connection: expired })
    assert.equal(rr.ok, true, rr.error)
    assert.equal(rr.output.token, 'novo')
    assert.equal(rr.connection.access_token, 'novo')
    assert.equal(rr.connection.refresh_token, 'r') // mantém o refresh_token antigo se o servidor não mandar outro
    tokenSrv.close()

    // piece que não exporta Piece
    await assert.rejects(hub.opMetadata({ name: 'quebrada' }), /não exporta uma piece|não carrega/)

    // nome/versão inválidos
    await assert.rejects(hub.ensureInstalled('lodash', '1.0.0'), /inválido/)
    await assert.rejects(hub.ensureInstalled('@activepieces/piece-fake', '1.0;rm'), /inválida/)

    // HTTP: token obrigatório, health livre, arquivos
    const server = hub.createServer()
    await new Promise((ok) => server.listen(0, '127.0.0.1', ok))
    const base = `http://127.0.0.1:${server.address().port}`
    assert.equal((await fetch(`${base}/health`)).status, 200)
    assert.equal((await fetch(`${base}/run`, { method: 'POST', body: '{}' })).status, 401)
    const hr = await fetch(`${base}/run`, { method: 'POST', headers: { 'x-minihub-token': 'segredo-de-teste' }, body: JSON.stringify({ name: 'fake', action: 'falha' }) })
    assert.equal(hr.status, 200); assert.equal((await hr.json()).ok, false)
    const nf = await fetch(`${base}/run`, { method: 'POST', headers: { 'x-minihub-token': 'segredo-de-teste' }, body: JSON.stringify({ name: 'fake', action: 'nada' }) })
    assert.equal(nf.status, 404)
    const fileId = r.output.url.split('/').pop()
    const fr = await fetch(`${base}/files/${fileId}`)
    assert.equal(await fr.text(), 'oi')
    server.close()

    console.log('minihub test-local: OK')
    fs.rmSync(root, { recursive: true, force: true })
    process.exit(0)
}

main().catch((e) => { console.error(e); process.exit(1) })
