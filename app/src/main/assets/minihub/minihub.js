'use strict'
/*
 * Fluwy mini-hub — executa conectores ("pieces") do Activepieces sem o servidor completo.
 *
 * Um processo Node, sem banco, sem Redis, sem painel. Cada piece é um pacote npm
 * (@activepieces/piece-*) instalado sob demanda numa pasta compartilhada; o mini-hub carrega o
 * pacote e chama a ação direto, montando o mesmo "context" que o motor do Activepieces monta
 * (packages/server/engine: piece-executor, props-processor, connection-resolver, oauth2).
 *
 * Segredos: as contas conectadas NÃO ficam aqui. O app (Kotlin) guarda cada conexão criptografada
 * e manda o valor junto de cada pedido; se um token OAuth for renovado, ele volta na resposta para o
 * app salvar. Escuta só em 127.0.0.1 e exige o token do app (cabeçalho x-minihub-token).
 *
 * Rotas (JSON): GET /health · POST /install · /metadata · /options · /run · /validate · /update ·
 * /oauth/url · /oauth/claim · GET /files/<id>
 */
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const { Readable } = require('node:stream')

const ROOT = process.env.MINIHUB_ROOT || '/opt/fluwy-minihub'
const PORT = Number(process.env.MINIHUB_PORT || 8791)
const TOKEN = process.env.MINIHUB_TOKEN || (() => { try { return fs.readFileSync(process.env.MINIHUB_TOKEN_FILE || '/nonexistent', 'utf8').trim() } catch { return '' } })()
const NPM = process.env.MINIHUB_NPM || 'npm'
const REGISTRY = (process.env.MINIHUB_REGISTRY || 'https://registry.npmjs.org').replace(/\/+$/, '')
const SECRETS = (process.env.MINIHUB_SECRETS_URL || 'https://secrets.activepieces.com').replace(/\/+$/, '')
const RUN_TIMEOUT_MS = Number(process.env.MINIHUB_RUN_TIMEOUT_MS || 180000)
const PKG = path.join(ROOT, 'pkgs')
const STORE = path.join(ROOT, 'store')
const FILES = path.join(ROOT, 'files')
const STATE = path.join(ROOT, 'state.json')
const PUBLIC_URL = `http://127.0.0.1:${PORT}/`

const PIECE_PREFIX = '@activepieces/piece-'
/** Hash do próprio script: o app compara com o do APK e reinicia um processo antigo que tenha sobrado. */
const SCRIPT_SHA = (() => { try { return crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex').slice(0, 16) } catch { return 'unknown' } })()

// ---------------------------------------------------------------- utilidades
const isNil = (v) => v === null || v === undefined
const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v)

function mkdirs() { for (const d of [ROOT, PKG, STORE, FILES]) fs.mkdirSync(d, { recursive: true }) }
try { mkdirs() } catch { /* main() tenta de novo e mostra o erro */ }

function readJson(file, fallback) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return fallback }
}
function writeJsonAtomic(file, value) {
    const tmp = `${file}.${process.pid}.tmp`
    fs.writeFileSync(tmp, JSON.stringify(value))
    fs.renameSync(tmp, file)
}

/** "gmail", "Gmail", "piece-gmail", "@activepieces/piece-gmail" → "@activepieces/piece-gmail". */
function pieceName(raw) {
    const s = String(raw || '').trim()
    if (s.startsWith('@')) return s
    const slug = s.toLowerCase().replace(/^piece-/, '').replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '')
    if (!slug) throw new UserError('pieceName vazio')
    return PIECE_PREFIX + slug
}

function validName(name) { return /^@activepieces\/piece-[a-z0-9][a-z0-9._-]*$/.test(name) }
function validVersion(v) { return /^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$/.test(v) }

class UserError extends Error { constructor(m, status = 400) { super(m); this.status = status } }

function errorText(e) {
    if (isNil(e)) return 'erro desconhecido'
    if (typeof e === 'string') return e
    const resp = e.response // axios
    if (resp && !isNil(resp.status)) {
        let body = resp.data
        try { if (typeof body !== 'string') body = JSON.stringify(body) } catch { body = String(body) }
        return `HTTP ${resp.status}: ${String(body).slice(0, 1500)}`
    }
    let msg = e.message || String(e)
    if (e.cause && e.cause.message && !msg.includes(e.cause.message)) msg += ` (${e.cause.message})`
    return msg.slice(0, 3000)
}

// ---------------------------------------------------------------- estado de versões
/** { "@activepieces/piece-x": { version, previous? } } — a versão "boa" conhecida de cada piece. */
function state() { return readJson(STATE, {}) }
function setState(name, entry) { const s = state(); s[name] = entry; writeJsonAtomic(STATE, s) }

function installedVersion(name) {
    const pj = readJson(path.join(PKG, 'node_modules', name, 'package.json'), null)
    return pj && pj.version ? String(pj.version) : null
}

// ---------------------------------------------------------------- npm
let npmQueue = Promise.resolve()
/** Uma instalação por vez (a pasta node_modules é compartilhada). */
function serialized(fn) {
    const run = npmQueue.then(fn, fn)
    npmQueue = run.catch(() => undefined)
    return run
}

function ensurePkgJson() {
    const f = path.join(PKG, 'package.json')
    if (!fs.existsSync(f)) writeJsonAtomic(f, { name: 'fluwy-minihub-pieces', private: true, version: '1.0.0' })
}

function npm(args, timeoutMs = 20 * 60 * 1000) {
    return new Promise((resolve) => {
        const p = spawn(NPM, args, { cwd: PKG, env: { ...process.env, npm_config_update_notifier: 'false', npm_config_fund: 'false', npm_config_audit: 'false' } })
        let out = ''
        const add = (b) => { out += b.toString(); if (out.length > 60000) out = out.slice(-30000) }
        p.stdout.on('data', add); p.stderr.on('data', add)
        const t = setTimeout(() => { out += '\n[timeout]'; p.kill('SIGKILL') }, timeoutMs)
        p.on('error', (e) => { clearTimeout(t); resolve({ code: -1, out: out + String(e) }) })
        p.on('close', (code) => { clearTimeout(t); resolve({ code, out }) })
    })
}

async function latestVersion(name) {
    const r = await fetch(`${REGISTRY}/${name.replace('/', '%2f')}/latest`, { headers: { accept: 'application/json' } })
    if (r.status === 404) throw new UserError(`conector ${name} não existe no catálogo do Activepieces`, 404)
    if (!r.ok) throw new Error(`registro npm respondeu HTTP ${r.status}`)
    const j = await r.json()
    if (!j.version) throw new Error('registro npm sem versão')
    return String(j.version)
}

async function npmInstall(name, version) {
    ensurePkgJson()
    const r = await npm(['install', `${name}@${version}`, '--prefix', PKG, '--save-exact', '--omit=dev', '--no-audit', '--no-fund', '--legacy-peer-deps', '--loglevel=error'])
    if (r.code !== 0 || installedVersion(name) !== version) {
        throw new Error(`falha ao instalar ${name}@${version}: ${r.out.trim().split('\n').slice(-12).join('\n')}`)
    }
    forget(name)
}

/**
 * Garante a piece instalada. [preferred] (a versão verificada do catálogo do app) só é usada quando a piece
 * ainda não está instalada: uma versão já instalada (talvez atualizada e testada por /update) é mantida.
 * Com [exact], instala exatamente [preferred].
 */
function ensureInstalled(name, preferred, exact = false) {
    return serialized(async () => {
        if (!validName(name)) throw new UserError(`nome de conector inválido: ${name}`)
        if (preferred && !validVersion(preferred)) throw new UserError(`versão inválida: ${preferred}`)
        const current = installedVersion(name)
        if (current && (!exact || !preferred || current === preferred)) {
            if (!state()[name]) setState(name, { version: current })
            return current
        }
        const want = preferred || await latestVersion(name)
        await npmInstall(name, want)
        try {
            selfTest(name)
        } catch (e) {
            // Versão pedida não carrega: volta para a anterior (se havia) e falha com o motivo.
            if (current && current !== want) { try { await npmInstall(name, current) } catch { /* mantém o erro original */ } }
            throw new Error(`${name}@${want} instalou mas não carrega: ${errorText(e)}`)
        }
        setState(name, { version: want, previous: current || undefined })
        return want
    })
}

/**
 * Atualiza para a versão mais nova do npm só se ela carregar e expuser as mesmas ações; senão volta
 * para a versão anterior. É o que protege o app de uma atualização do Activepieces que quebre algo.
 */
function updatePiece(name) {
    return serialized(async () => {
        if (!validName(name)) throw new UserError(`nome de conector inválido: ${name}`)
        const current = installedVersion(name)
        const latest = await latestVersion(name)
        if (current === latest) return { name, version: current, updated: false }
        const before = current ? actionNames(name) : []
        await npmInstall(name, latest)
        try {
            selfTest(name)
            const after = new Set(actionNames(name))
            const lost = before.filter((a) => !after.has(a))
            if (lost.length > 0) throw new Error(`a versão nova removeu ações usadas: ${lost.join(', ')}`)
        } catch (e) {
            if (current) await npmInstall(name, current)
            return { name, version: current, updated: false, error: `atualização para ${latest} recusada: ${errorText(e)}` }
        }
        setState(name, { version: latest, previous: current || undefined })
        return { name, version: latest, updated: true, previous: current }
    })
}

// ---------------------------------------------------------------- carregamento
const loaded = new Map() // name → { version, piece }

function forget(name) {
    loaded.delete(name)
    const marker = `${path.sep}node_modules${path.sep}${name.split('/').join(path.sep)}${path.sep}`
    for (const k of Object.keys(require.cache)) if (k.includes(marker)) delete require.cache[k]
}

/** Mesmo critério do motor (shared/extractPieceFromModule): o export cujo construtor se chama Piece. */
function extractPiece(mod, name) {
    const found = []
    for (const e of Object.values(mod || {})) {
        if (!isNil(e) && e.constructor && e.constructor.name === 'Piece') return e
        found.push(e && e.constructor ? e.constructor.name : typeof e)
    }
    // Alguns bundles exportam tudo em "default".
    if (mod && mod.default && mod.default !== mod) {
        for (const e of Object.values(mod.default)) if (!isNil(e) && e.constructor && e.constructor.name === 'Piece') return e
    }
    throw new Error(`o pacote ${name} não exporta uma piece (encontrado: ${found.slice(0, 8).join(', ')})`)
}

function loadPiece(name) {
    const version = installedVersion(name)
    if (!version) throw new UserError(`conector ${name} não está instalado`, 404)
    const hit = loaded.get(name)
    if (hit && hit.version === version) return hit.piece
    forget(name)
    const dir = path.join(PKG, 'node_modules', name)
    const piece = extractPiece(require(dir), name)
    loaded.set(name, { version, piece })
    return piece
}

function actionsOf(piece) {
    if (typeof piece.actions === 'function') return piece.actions() || {}
    return piece._actions || {}
}
function actionNames(name) { return Object.keys(actionsOf(loadPiece(name))) }

function getAction(piece, actionName) {
    const a = typeof piece.getAction === 'function' ? piece.getAction(actionName) : actionsOf(piece)[actionName]
    if (a) return a
    // Tolerância: o agente às vezes manda o displayName.
    const all = actionsOf(piece)
    const key = Object.keys(all).find((k) => (all[k].displayName || '').toLowerCase() === String(actionName).toLowerCase())
    return key ? all[key] : undefined
}

function contextVersion(piece) {
    try { return typeof piece.getContextInfo === 'function' ? piece.getContextInfo()?.version : undefined } catch { return undefined }
}

/** Carrega e serializa: se a piece não carrega ou o metadata quebra, a instalação é rejeitada. */
function selfTest(name) {
    forget(name)
    const piece = loadPiece(name)
    const meta = metadataOf(name, piece)
    if (!meta.displayName) throw new Error('piece sem displayName')
    return meta
}

// ---------------------------------------------------------------- metadata (JSON)
function jsonSafe(value, depth = 0) {
    if (depth > 12) return undefined
    if (isNil(value)) return value
    if (typeof value === 'function' || typeof value === 'symbol') return undefined
    if (typeof value === 'bigint') return String(value)
    if (Array.isArray(value)) return value.map((v) => jsonSafe(v, depth + 1)).filter((v) => v !== undefined)
    if (typeof value === 'object') {
        if (value instanceof Date) return value.toISOString()
        const out = {}
        for (const [k, v] of Object.entries(value)) {
            if (k === 'options' && typeof v === 'function') { out.optionsDynamic = true; continue }
            if (k === 'props' && typeof v === 'function') { out.propsDynamic = true; continue }
            const s = jsonSafe(v, depth + 1)
            if (s !== undefined) out[k] = s
        }
        return out
    }
    return value
}

function metadataOf(name, piece) {
    const m = typeof piece.metadata === 'function' ? piece.metadata() : piece
    const actions = {}
    for (const [k, a] of Object.entries(m.actions || actionsOf(piece))) {
        actions[k] = {
            name: a.name || k,
            displayName: a.displayName,
            description: a.description,
            requireAuth: a.requireAuth !== false,
            props: jsonSafe(a.props || {}),
        }
    }
    return {
        name,
        version: installedVersion(name),
        displayName: m.displayName,
        description: m.description,
        logoUrl: m.logoUrl,
        categories: jsonSafe(m.categories),
        auth: jsonSafe(m.auth),
        contextVersion: contextVersion(piece) || null,
        actions,
        triggers: Object.keys(m.triggers || {}).length,
    }
}

// ---------------------------------------------------------------- conexão → auth
const CONN_TO_PROP = { OAUTH2: 'OAUTH2', CLOUD_OAUTH2: 'OAUTH2', PLATFORM_OAUTH2: 'OAUTH2', BASIC_AUTH: 'BASIC_AUTH', CUSTOM_AUTH: 'CUSTOM_AUTH', OIDC: 'OIDC', SECRET_TEXT: 'SECRET_TEXT' }

/** framework/getAuthPropertyForValue. */
function authPropertyFor(pieceAuth, type) {
    if (!Array.isArray(pieceAuth) || isNil(pieceAuth)) return pieceAuth
    return pieceAuth.find((a) => CONN_TO_PROP[type] === a.type) ?? pieceAuth[0]
}

/** engine/connection-resolver getConnectionValue: pieces antigas (sem contextInfo) recebem o formato V0. */
function connectionForContext(conn, ctxVersion) {
    if (isNil(conn)) return undefined
    if (ctxVersion === undefined || ctxVersion === null) {
        if (conn.type === 'SECRET_TEXT') return conn.secret_text
        if (conn.type === 'CUSTOM_AUTH') return conn.props
    }
    return conn
}

function oauthExpired(c) {
    const now = Math.round(Date.now() / 1000)
    const grant = c.grant_type || 'authorization_code'
    if (grant === 'authorization_code' && !c.refresh_token) return false
    const exp = Number(c.expires_in); const expiresIn = Number.isFinite(exp) && exp > 0 ? exp : 3600
    const cl = Number(c.claimed_at); const claimedAt = Number.isFinite(cl) && cl > 0 ? cl : 0
    return now + 15 * 60 >= claimedAt + expiresIn
}

function formatOAuth2Response(resp) {
    const now = Math.round(Date.now() / 1000)
    const exp = Number(resp.expires_in)
    const data = { ...resp }
    for (const k of ['access_token', 'expires_in', 'refresh_token', 'scope', 'token_type']) delete data[k]
    return { ...resp, expires_in: Number.isFinite(exp) && exp > 0 ? exp : undefined, data, claimed_at: now }
}

async function postJson(url, body, timeoutMs = 20000) {
    const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) })
    const text = await r.text()
    let json; try { json = JSON.parse(text) } catch { json = undefined }
    if (!r.ok) {
        const err = json && (json.error_description || json.error || json.message)
        throw new UserError(`HTTP ${r.status} em ${new URL(url).host}: ${String(err || text).slice(0, 500)}`, r.status === 400 || r.status === 401 ? 400 : 502)
    }
    return json ?? {}
}

async function postForm(url, body, headers = {}) {
    const r = await fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json', ...headers },
        body: new URLSearchParams(Object.fromEntries(Object.entries(body).filter(([, v]) => !isNil(v)).map(([k, v]) => [k, String(v)]))),
        signal: AbortSignal.timeout(30000),
    })
    const text = await r.text()
    let json; try { json = JSON.parse(text) } catch { json = Object.fromEntries(new URLSearchParams(text)) }
    if (!r.ok) throw new UserError(`HTTP ${r.status} no token: ${String(json.error_description || json.error || text).slice(0, 500)}`, 400)
    return json
}

/** Renova tokens vencidos (server/oauth2 cloud + credentials + custom-auth refresh). */
async function refreshConnection(name, piece, conn) {
    if (!isObject(conn)) return { conn, changed: false }
    if (conn.type === 'CLOUD_OAUTH2' && oauthExpired(conn)) {
        const resp = await postJson(`${SECRETS}/refresh`, {
            refreshToken: conn.refresh_token, pieceName: name, clientId: conn.client_id, edition: 'ce',
            authorizationMethod: conn.authorization_method, tokenUrl: conn.token_url,
        })
        return { conn: { ...conn, ...resp, props: conn.props, type: 'CLOUD_OAUTH2', claimed_at: resp.claimed_at || Math.round(Date.now() / 1000) }, changed: true }
    }
    if (conn.type === 'OAUTH2' && oauthExpired(conn)) {
        const grant = conn.grant_type || 'authorization_code'
        const body = {}
        if (grant === 'client_credentials') {
            body.grant_type = 'client_credentials'
            if (conn.scope) body.scope = resolveTemplate(conn.props, conn.scope)
            Object.assign(body, conn.props || {})
        } else {
            body.grant_type = 'refresh_token'
            body.refresh_token = conn.refresh_token
        }
        if (conn.resource) body.resource = conn.resource
        const headers = {}
        if ((conn.authorization_method || 'BODY') === 'HEADER') headers.authorization = `Basic ${Buffer.from(`${conn.client_id}:${conn.client_secret}`).toString('base64')}`
        else { body.client_id = conn.client_id; body.client_secret = conn.client_secret }
        const resp = await postForm(conn.token_url, body, headers)
        const merged = { ...conn, ...formatOAuth2Response({ ...resp, refresh_token: resp.refresh_token ?? conn.refresh_token }) }
        return { conn: merged, changed: true }
    }
    if (conn.type === 'CUSTOM_AUTH') {
        const pa = authPropertyFor(piece.auth, 'CUSTOM_AUTH')
        if (pa && pa.refresh && typeof pa.refresh.generate === 'function') {
            const now = Math.round(Date.now() / 1000)
            const exp = Number(conn.expires_in)
            const fresh = conn.access_token && (exp === 0 || (Number.isFinite(exp) && now + Math.min(15 * 60, exp / 2) < Number(conn.claimed_at || 0) + exp))
            if (!fresh) {
                const r = await pa.refresh.generate({ auth: conn.props, server: serverContext() })
                const expiresIn = r.expires_in ?? pa.refresh.defaultExpiresIn ?? 3300
                return { conn: { ...conn, access_token: r.access_token, expires_in: expiresIn, claimed_at: now }, changed: true }
            }
        }
    }
    return { conn, changed: false }
}

function resolveTemplate(props, template) {
    let out = String(template ?? '')
    for (const [k, v] of Object.entries(props || {})) out = out.split(`{${k}}`).join(String(v))
    return out
}

// ---------------------------------------------------------------- processadores de props (engine/variables/processors)
function parseToJsonIfPossible(v) { try { return JSON.parse(v) } catch { return v } }

const MIME_EXT = { 'application/json': 'json', 'application/pdf': 'pdf', 'application/xml': 'xml', 'application/zip': 'zip', 'application/gzip': 'gz', 'application/octet-stream': 'bin', 'application/msword': 'doc', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx', 'application/vnd.ms-excel': 'xls', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx', 'application/vnd.ms-powerpoint': 'ppt', 'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'pptx', 'image/png': 'png', 'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp', 'image/svg+xml': 'svg', 'image/bmp': 'bmp', 'image/heic': 'heic', 'text/plain': 'txt', 'text/html': 'html', 'text/css': 'css', 'text/csv': 'csv', 'text/markdown': 'md', 'audio/mpeg': 'mp3', 'audio/mp4': 'm4a', 'audio/wav': 'wav', 'audio/ogg': 'ogg', 'video/mp4': 'mp4', 'video/webm': 'webm', 'video/quicktime': 'mov' }
const mimeExt = (m) => MIME_EXT[String(m || '').split(';')[0].trim().toLowerCase()] || null

/** Framework da própria piece (para criar ApFile da mesma classe que a piece espera). */
function frameworkFor(name) {
    const candidates = [
        path.join(PKG, 'node_modules', name, 'node_modules', '@activepieces', 'pieces-framework'),
        path.join(PKG, 'node_modules', '@activepieces', 'pieces-framework'),
    ]
    for (const c of candidates) { try { if (fs.existsSync(c)) return require(c) } catch { /* próximo */ } }
    return {}
}

function makeFile(fw, filename, buffer, extension) {
    if (typeof fw.ApFile === 'function') return new fw.ApFile(filename, buffer, extension)
    return { filename, data: buffer, extension, get base64() { return buffer.toString('base64') } }
}

function filenameFrom(url, disposition, mime) {
    if (disposition) {
        const u = /filename\*=UTF-8''([\w%\-.]+)(?:; ?|$)/i.exec(disposition)
        if (u) return decodeURIComponent(u[1])
        const i = disposition.toLowerCase().indexOf('filename=')
        if (i >= 0) { const m = /^filename=(["']?)(.*?[^\\])\1(?:; ?|$)/i.exec(disposition.slice(i)); if (m && m[2]) return m[2] }
    }
    try { const last = new URL(url).pathname.split('/').pop(); if (last && last.includes('.')) return last } catch { /* ignora */ }
    return `unknown.${mimeExt(mime) || 'bin'}`
}

async function fileProcessor(fw, property, value) {
    if (isNil(value)) return null
    if (isObject(value) && (value.data || value.base64)) {
        const buf = Buffer.isBuffer(value.data) ? value.data : Buffer.from(String(value.base64 || value.data), 'base64')
        const filename = value.filename || 'unknown.bin'
        return makeFile(fw, filename, buf, value.extension || filename.split('.').pop())
    }
    if (typeof value !== 'string') return null
    const streaming = property.streaming === true
    const b64 = /^data:([A-Za-z-+/.]+);base64,(.+)$/s.exec(value)
    if (b64) {
        const ext = mimeExt(b64[1]) || 'bin'
        const buf = Buffer.from(b64[2], 'base64')
        if (streaming) return { filename: `unknown.${ext}`, extension: ext, size: buf.length, body: Readable.from(buf) }
        return makeFile(fw, `unknown.${ext}`, buf, ext)
    }
    if (value.startsWith('file://') || value.startsWith(PUBLIC_URL + 'files/')) {
        const id = value.split('/').pop()
        const f = localFile(id)
        if (!f) return null
        const buf = fs.readFileSync(f.path)
        if (streaming) return { filename: f.name, extension: f.name.split('.').pop(), size: buf.length, body: Readable.from(buf) }
        return makeFile(fw, f.name, buf, f.name.split('.').pop())
    }
    if (!/^https?:\/\//i.test(value)) return null
    const r = await fetch(value)
    if (!r.ok) { await r.body?.cancel(); return null }
    const filename = filenameFrom(value, r.headers.get('content-disposition'), r.headers.get('content-type'))
    const ext = filename.includes('.') ? filename.split('.').pop() : undefined
    if (streaming && r.body) return { filename, extension: ext, size: undefined, body: Readable.fromWeb(r.body) }
    return makeFile(fw, filename, Buffer.from(await r.arrayBuffer()), ext)
}

function arrayZipper(value) {
    if (Array.isArray(value) || !isObject(value)) return value
    const len = Math.max(...Object.values(value).map((v) => (Array.isArray(v) ? v.length : 1)))
    return Array.from({ length: len }, (_, i) => Object.fromEntries(Object.entries(value).map(([k, v]) => [k, Array.isArray(v) ? v[i] : v])))
}

async function processValue(fw, property, value) {
    switch (property.type) {
        case 'JSON':
            if (isNil(value) || typeof value === 'object') return value
            try { return JSON.parse(value) } catch { return undefined }
        case 'OBJECT':
            if (isNil(value)) return value
            if (typeof value === 'string') { try { return JSON.parse(value) } catch { return undefined } }
            return isObject(value) ? value : undefined
        case 'NUMBER':
            if (isNil(value)) return value
            if (value === '') return undefined
            return Number(value)
        case 'LONG_TEXT': case 'SHORT_TEXT': case 'SECRET_TEXT': {
            if (isNil(value)) return value
            if (typeof value === 'object') return JSON.stringify(value)
            const s = value.toString()
            return s.length === 0 && !property.required ? undefined : s
        }
        case 'DATE_TIME': {
            if (!value) return undefined
            const d = new Date(typeof value === 'number' ? value : String(value))
            if (Number.isNaN(d.getTime())) return undefined
            // dayjs.tz(x, 'UTC'): datas sem fuso são lidas como UTC.
            if (typeof value === 'string' && !/[zZ]|[+-]\d\d:?\d\d$/.test(value) && /\d{2}:\d{2}/.test(value)) {
                const u = new Date(value.replace(' ', 'T') + 'Z'); if (!Number.isNaN(u.getTime())) return u.toISOString()
            }
            return d.toISOString()
        }
        case 'FILE':
            return fileProcessor(fw, property, value)
        case 'MULTI_SELECT_DROPDOWN': case 'STATIC_MULTI_SELECT_DROPDOWN': {
            if (isNil(value) || Array.isArray(value)) return value
            if (typeof value === 'string' && value.trim().length === 0) return undefined
            const p = parseToJsonIfPossible(value)
            return Array.isArray(p) ? p : [value]
        }
        case 'CHECKBOX': {
            if (isNil(value) || typeof value === 'boolean') return value
            if (typeof value === 'string' && value.trim().length === 0) return undefined
            const p = typeof value === 'string' ? parseToJsonIfPossible(value.trim().toLowerCase()) : value
            return typeof p === 'boolean' ? p : value
        }
        default:
            return value
    }
}

function validate(property, value, original) {
    if (property.type === 'JSON') {
        if (!property.required && original === '') return []
        if (!isNil(original) && isNil(value)) return [`esperado JSON, recebido: ${String(original).slice(0, 80)}`]
        if (!property.required && isNil(value)) return []
        return isObject(value) || Array.isArray(value) ? [] : [`esperado JSON, recebido: ${String(original).slice(0, 80)}`]
    }
    if (!property.required && isNil(value)) return []
    switch (property.type) {
        case 'SHORT_TEXT': case 'LONG_TEXT': case 'RICH_TEXT': return typeof value === 'string' ? [] : ['esperado texto']
        case 'NUMBER': return typeof value === 'number' && !Number.isNaN(value) ? [] : [`esperado número, recebido: ${original}`]
        case 'CHECKBOX': return typeof value === 'boolean' ? [] : [`esperado true/false, recebido: ${original}`]
        case 'DATE_TIME': return typeof value === 'string' ? [] : [`data inválida (use ISO, ex. 2024-03-14T12:00:00.000Z): ${original}`]
        case 'ARRAY': case 'MULTI_SELECT_DROPDOWN': case 'STATIC_MULTI_SELECT_DROPDOWN': return Array.isArray(value) ? [] : ['esperado lista']
        case 'OBJECT': return isObject(value) ? [] : ['esperado objeto']
        case 'FILE': return isObject(value) ? [] : ['esperado URL do arquivo ou data:<mime>;base64,...']
        default: return isNil(value) && property.required ? ['obrigatório'] : []
    }
}

/** engine/props-processor applyProcessorsAndValidators (sem schemas de propertySettings). */
async function processProps(fw, input, props, auth, requireAuth, strictRequired = false) {
    const out = { ...input }
    const errors = {}
    const authValue = input.auth
    if (authValue && requireAuth && auth) {
        const used = authPropertyFor(auth, authValue.type)
        if (used && ['CUSTOM_AUTH', 'OAUTH2', 'OIDC'].includes(used.type) && used.props && isObject(authValue)) {
            const r = await processProps(fw, authValue, used.props, undefined, false)
            out.auth = r.processed
            if (Object.keys(r.errors).length) errors.auth = r.errors
        }
    }
    for (const [key, value] of Object.entries(input)) {
        const property = props[key]
        if (isNil(property) || key === 'auth') continue
        if (property.type === 'ARRAY' && property.properties) {
            const items = arrayZipper(value) ?? []
            const arr = []; const errs = []
            for (const item of Array.isArray(items) ? items : []) {
                const r = await processProps(fw, item, property.properties, undefined, false)
                arr.push(r.processed); errs.push(r.errors)
            }
            out[key] = arr
            if (errs.some((e) => Object.keys(e).length)) errors[key] = { properties: errs }
        }
        if (!property.required && isNil(out[key])) out[key] = undefined
        out[key] = await processValue(fw, property, out[key])
    }
    for (const [key, property] of Object.entries(props)) {
        if (key === 'auth' || property.type === 'MARKDOWN' || property.type === 'DYNAMIC') continue
        const v = out[key]
        if (isNil(v) && isNil(input[key]) && !isNil(property.defaultValue)) {
            out[key] = await processValue(fw, property, property.defaultValue)
            continue
        }
        if (isNil(v) && isNil(input[key]) && property.type === 'CHECKBOX') { out[key] = false; continue }
        if (isNil(v) && property.required && isNil(input[key])) {
            // O motor não checa obrigatórios (o editor já checou); aqui o agente monta o input, então avisamos.
            if (strictRequired) errors[key] = ['obrigatório']
            continue
        }
        if (!(key in out)) continue
        const errs = validate(property, out[key], input[key])
        if (errs.length) errors[key] = errs
    }
    return { processed: out, errors }
}

// ---------------------------------------------------------------- contexto da ação
function serverContext() { return { apiUrl: PUBLIC_URL, publicUrl: PUBLIC_URL, token: TOKEN || 'minihub' } }

function safeSegment(s) { return String(s).replace(/[^A-Za-z0-9._@-]+/g, '_').slice(0, 120) }

/** context.store: chave/valor persistente por piece (o motor separa por fluxo; aqui por piece+escopo). */
function createStore(name) {
    const file = path.join(STORE, `${safeSegment(name)}.json`)
    const load = () => readJson(file, {})
    const key = (k, scope) => `${scope === 'PROJECT' ? 'p' : 'f'}:${k}`
    return {
        async put(k, v, scope) { const d = load(); d[key(k, scope)] = v; writeJsonAtomic(file, d); return v },
        async get(k, scope) { const d = load(); const v = d[key(k, scope)]; return v === undefined ? null : v },
        async delete(k, scope) { const d = load(); delete d[key(k, scope)]; writeJsonAtomic(file, d) },
    }
}

function localFile(id) {
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(String(id))) return null
    const meta = readJson(path.join(FILES, `${id}.json`), null)
    if (!meta) return null
    return { path: path.join(FILES, id), name: meta.name, mime: meta.mime }
}

/** context.files.write: salva localmente e devolve uma URL do próprio mini-hub. */
function createFiles() {
    return {
        async write({ fileName, data }) {
            const id = crypto.randomBytes(12).toString('base64url')
            const buf = Buffer.isBuffer(data) ? data : Buffer.from(data)
            fs.writeFileSync(path.join(FILES, id), buf)
            writeJsonAtomic(path.join(FILES, `${id}.json`), { name: String(fileName || 'arquivo'), size: buf.length, created: Date.now() })
            return `${PUBLIC_URL}files/${id}`
        },
    }
}

function cleanupFiles(maxAgeMs = 3 * 24 * 3600 * 1000) {
    try {
        for (const f of fs.readdirSync(FILES)) {
            if (!f.endsWith('.json')) continue
            const meta = readJson(path.join(FILES, f), {})
            if (Date.now() - (meta.created || 0) > maxAgeMs) {
                fs.rmSync(path.join(FILES, f), { force: true })
                fs.rmSync(path.join(FILES, f.slice(0, -5)), { force: true })
            }
        }
    } catch { /* limpeza é melhor-esforço */ }
}

function unsupported(what) {
    return async () => { throw new Error(`${what} não é suportado no mini-hub (só existe dentro de automações do Activepieces)`) }
}

function buildActionContext({ name, piece, action, auth, propsValue, hooks }) {
    const ctx = {
        executionType: 'BEGIN',
        resumePayload: undefined,
        store: createStore(name),
        output: { update: async () => undefined },
        flows: { list: async () => ({ data: [], next: null, previous: null }), current: { id: 'minihub', version: { id: 'minihub' } } },
        step: { name: action.name },
        auth,
        files: createFiles(),
        server: serverContext(),
        propsValue,
        tags: { add: async ({ name: t }) => { hooks.tags.push(t) } },
        connections: { get: unsupported('connections.get') },
        run: {
            id: `minihub-${crypto.randomBytes(6).toString('hex')}`,
            canPause: false,
            stop: (r) => { hooks.stopped = r ?? { response: {} } },
            respond: (r) => { hooks.response = r ?? { response: {} } },
            createWaitpoint: unsupported('pausar/esperar'),
            waitForWaitpoint: () => { throw new Error('pausar/esperar não é suportado no mini-hub') },
            pause: () => { throw new Error('pausar não é suportado no mini-hub') },
        },
        project: { id: 'minihub', externalId: undefined },
    }
    const v = contextVersion(piece)
    if (v === '1' || v === undefined || v === null) {
        ctx.generateResumeUrl = () => { throw new Error('generateResumeUrl não é suportado no mini-hub') }
    }
    if (v === undefined || v === null) ctx.serverUrl = PUBLIC_URL
    return ctx
}

function withTimeout(promise, ms, label) {
    let t
    return Promise.race([promise, new Promise((_, rej) => { t = setTimeout(() => rej(new Error(`${label} passou de ${Math.round(ms / 1000)}s`)), ms) })]).finally(() => clearTimeout(t))
}

/** Saída da ação → JSON (Buffers e ApFile viram texto legível, streams são descartados). */
function outputJson(v, depth = 0) {
    if (depth > 30) return '[profundo demais]'
    if (isNil(v)) return v ?? null
    if (Buffer.isBuffer(v)) return { type: 'buffer', size: v.length, base64: v.length <= 256 * 1024 ? v.toString('base64') : undefined }
    if (v instanceof Readable) return '[stream]'
    if (typeof v === 'bigint') return String(v)
    if (typeof v === 'function' || typeof v === 'symbol') return undefined
    if (v instanceof Date) return v.toISOString()
    if (Array.isArray(v)) return v.map((x) => outputJson(x, depth + 1))
    if (typeof v === 'object') {
        if (typeof v.toJSON === 'function') { try { return outputJson(v.toJSON(), depth + 1) } catch { /* segue */ } }
        const o = {}
        for (const [k, x] of Object.entries(v)) { const s = outputJson(x, depth + 1); if (s !== undefined) o[k] = s }
        return o
    }
    return v
}

// ---------------------------------------------------------------- operações
async function opMetadata({ name, version }) {
    const n = pieceName(name)
    await ensureInstalled(n, version)
    return metadataOf(n, loadPiece(n))
}

async function prepareAuth(n, piece, connection, requireAuth) {
    if (!requireAuth || isNil(piece.auth)) return { auth: undefined, connection: undefined, changed: false }
    if (isNil(connection)) {
        const pa = Array.isArray(piece.auth) ? piece.auth[0] : piece.auth
        if (pa && pa.required === false) return { auth: undefined, connection: undefined, changed: false }
        throw new UserError(`este conector precisa de uma conta conectada (${n})`)
    }
    if (!isObject(connection) || !connection.type) throw new UserError('conexão inválida (sem "type")')
    const { conn, changed } = await refreshConnection(n, piece, connection)
    return { auth: connectionForContext(conn, contextVersion(piece)), connection: conn, changed }
}

async function opRun({ name, version, action: actionName, input, connection }) {
    const n = pieceName(name)
    await ensureInstalled(n, version)
    const piece = loadPiece(n)
    const action = getAction(piece, actionName)
    if (!action) throw new UserError(`ação "${actionName}" não existe em ${n}. Ações: ${Object.keys(actionsOf(piece)).join(', ')}`, 404)
    const requireAuth = action.requireAuth !== false
    const prepared = await prepareAuth(n, piece, connection, requireAuth)
    const raw = { ...(isObject(input) ? input : {}) }
    if (prepared.auth !== undefined) raw.auth = prepared.auth
    const fw = frameworkFor(n)
    const { processed, errors } = await processProps(fw, raw, action.props || {}, piece.auth, requireAuth, true)
    if (Object.keys(errors).length > 0) {
        return { ok: false, error: `campos inválidos: ${JSON.stringify(errors)}`, connection: prepared.changed ? prepared.connection : undefined }
    }
    const hooks = { tags: [] }
    const ctx = buildActionContext({ name: n, piece, action, auth: processed.auth, propsValue: processed, hooks })
    const started = Date.now()
    try {
        const out = await withTimeout(Promise.resolve().then(() => action.run(ctx)), RUN_TIMEOUT_MS, `a ação ${action.name}`)
        return {
            ok: true,
            output: outputJson(out),
            durationMs: Date.now() - started,
            action: action.name,
            version: installedVersion(n),
            connection: prepared.changed ? prepared.connection : undefined,
        }
    } catch (e) {
        return { ok: false, error: errorText(e), action: action.name, version: installedVersion(n), connection: prepared.changed ? prepared.connection : undefined }
    }
}

async function opOptions({ name, version, action: actionName, prop, input, connection, searchValue }) {
    const n = pieceName(name)
    await ensureInstalled(n, version)
    const piece = loadPiece(n)
    const action = getAction(piece, actionName)
    if (!action) throw new UserError(`ação "${actionName}" não existe em ${n}`, 404)
    const property = (action.props || {})[prop]
    if (!property) throw new UserError(`campo "${prop}" não existe em ${action.name}`, 404)
    if (!['DROPDOWN', 'MULTI_SELECT_DROPDOWN', 'DYNAMIC'].includes(property.type)) throw new UserError(`campo "${prop}" não tem opções dinâmicas (${property.type})`)
    const prepared = await prepareAuth(n, piece, connection, action.requireAuth !== false)
    const resolved = { ...(isObject(input) ? input : {}) }
    if (prepared.auth !== undefined) resolved.auth = prepared.auth
    const ctx = {
        searchValue,
        server: serverContext(),
        project: { id: 'minihub', externalId: undefined },
        flows: { list: async () => ({ data: [], next: null, previous: null }), current: { id: 'minihub', version: { id: 'minihub' } } },
        step: { name: action.name },
        connections: { get: unsupported('connections.get') },
    }
    try {
        const fn = property.type === 'DYNAMIC' ? property.props : property.options
        const r = await withTimeout(Promise.resolve().then(() => fn(resolved, ctx)), 60000, `as opções de ${prop}`)
        return { ok: true, type: property.type, options: jsonSafe(r), connection: prepared.changed ? prepared.connection : undefined }
    } catch (e) {
        return { ok: false, type: property.type, error: errorText(e), connection: prepared.changed ? prepared.connection : undefined }
    }
}

async function opValidate({ name, version, connection }) {
    const n = pieceName(name)
    await ensureInstalled(n, version)
    const piece = loadPiece(n)
    if (isNil(piece.auth)) return { valid: true }
    if (!isObject(connection) || !connection.type) return { valid: false, error: 'conexão inválida' }
    const used = authPropertyFor(piece.auth, connection.type)
    if (isNil(used)) return { valid: false, error: 'o conector não aceita esse tipo de conta' }
    if (typeof used.validate !== 'function') return { valid: true }
    const expect = { OAUTH2: ['OAUTH2', 'CLOUD_OAUTH2', 'PLATFORM_OAUTH2'], BASIC_AUTH: ['BASIC_AUTH'], SECRET_TEXT: ['SECRET_TEXT'], CUSTOM_AUTH: ['CUSTOM_AUTH'], OIDC: ['OIDC'] }[used.type] || []
    if (!expect.includes(connection.type)) return { valid: false, error: `tipo de conta errado: o conector espera ${used.type}, recebeu ${connection.type}` }
    const auth = used.type === 'SECRET_TEXT' ? connection.secret_text : (used.type === 'CUSTOM_AUTH' || used.type === 'OIDC') ? connection.props : connection
    try {
        const r = await withTimeout(Promise.resolve().then(() => used.validate({ auth, server: serverContext() })), 60000, 'a validação da conta')
        return isObject(r) ? { valid: r.valid !== false, error: r.error } : { valid: true }
    } catch (e) {
        return { valid: false, error: errorText(e) }
    }
}

function oauthProp(piece) {
    const pa = Array.isArray(piece.auth) ? piece.auth.find((a) => a.type === 'OAUTH2') : piece.auth
    if (!pa || pa.type !== 'OAUTH2') throw new UserError('este conector não usa OAuth2')
    return pa
}

function backfill(props, authProps) {
    const out = { ...(props || {}) }
    for (const [k, p] of Object.entries(authProps || {})) if (isNil(out[k])) out[k] = p && !isNil(p.defaultValue) ? p.defaultValue : ''
    return out
}

/** server/oauth2-util buildAuthorizationUrl. */
async function opOAuthUrl({ name, version, clientId, redirectUrl, props, scopes }) {
    const n = pieceName(name)
    await ensureInstalled(n, version)
    const pa = oauthProp(loadPiece(n))
    if (!clientId) throw new UserError('clientId ausente')
    if (!redirectUrl) throw new UserError('redirectUrl ausente')
    const tp = backfill(props, pa.props)
    const allowed = pa.scope || []
    const selected = Array.isArray(scopes) && scopes.length ? scopes.filter((s) => allowed.includes(s)) : allowed
    const q = {
        response_type: 'code', client_id: clientId, redirect_uri: redirectUrl, access_type: 'offline',
        state: crypto.randomBytes(10).toString('base64url'), prompt: 'consent',
        scope: resolveTemplate(tp, selected.join(' ')), ...(pa.extra || {}),
    }
    if (pa.prompt === 'omit') delete q.prompt
    else if (!isNil(pa.prompt)) q.prompt = pa.prompt
    let codeVerifier
    if (pa.pkce) {
        codeVerifier = crypto.randomBytes(32).toString('base64url').slice(0, 43)
        const method = pa.pkceMethod || 'plain'
        q.code_challenge_method = method
        q.code_challenge = method === 'S256' ? crypto.createHash('sha256').update(codeVerifier).digest('base64url') : codeVerifier
    }
    const url = new URL(resolveTemplate(tp, pa.authUrl))
    for (const [k, v] of Object.entries(q)) if (v !== '' && !isNil(v)) url.searchParams.append(k, String(v))
    return {
        authorizationUrl: url.toString(), codeVerifier, state: q.state,
        tokenUrl: resolveTemplate(tp, pa.tokenUrl), authorizationMethod: pa.authorizationMethod, grantType: pa.grantType,
        scope: q.scope,
    }
}

/** Troca o code por tokens: CLOUD (secrets.activepieces.com, como o CE) ou credenciais próprias. */
async function opOAuthClaim({ name, version, mode, code, codeVerifier, clientId, clientSecret, redirectUrl, props }) {
    const n = pieceName(name)
    await ensureInstalled(n, version)
    const pa = oauthProp(loadPiece(n))
    const tp = backfill(props, pa.props)
    const tokenUrl = resolveTemplate(tp, pa.tokenUrl)
    if (mode === 'cloud') {
        const value = await postJson(`${SECRETS}/claim`, {
            code, codeVerifier, authorizationMethod: pa.authorizationMethod, clientId, tokenUrl, pieceName: n, edition: 'ce',
        }, 15000)
        return { connection: { ...value, type: 'CLOUD_OAUTH2', client_id: value.client_id || clientId, token_url: tokenUrl, props: props || undefined, authorization_method: pa.authorizationMethod, claimed_at: value.claimed_at || Math.round(Date.now() / 1000) } }
    }
    const grant = pa.grantType === 'client_credentials' ? 'client_credentials' : 'authorization_code'
    const body = { grant_type: grant }
    if (grant === 'authorization_code') { body.redirect_uri = redirectUrl; body.code = code }
    else { if (pa.scope && pa.scope.length) body.scope = resolveTemplate(tp, pa.scope.join(' ')); Object.assign(body, props || {}) }
    if (codeVerifier) body.code_verifier = codeVerifier
    const method = pa.authorizationMethod || 'BODY'
    const headers = {}
    if (method === 'HEADER') headers.authorization = `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString('base64')}`
    else { body.client_id = clientId; body.client_secret = clientSecret }
    const resp = await postForm(tokenUrl, body, headers)
    return {
        connection: {
            type: 'OAUTH2', ...formatOAuth2Response(resp), token_url: tokenUrl, client_id: clientId, client_secret: clientSecret,
            redirect_url: redirectUrl, grant_type: grant, props: props || undefined, authorization_method: method,
        },
    }
}

// ---------------------------------------------------------------- HTTP
function send(res, status, body) {
    const text = JSON.stringify(body)
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(text) })
    res.end(text)
}

function readBody(req, limit = 40 * 1024 * 1024) {
    return new Promise((resolve, reject) => {
        let size = 0; const chunks = []
        req.on('data', (c) => { size += c.length; if (size > limit) { reject(new UserError('pedido grande demais', 413)); req.destroy() } else chunks.push(c) })
        req.on('end', () => {
            if (!chunks.length) return resolve({})
            try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))) } catch { reject(new UserError('JSON inválido')) }
        })
        req.on('error', reject)
    })
}

const ROUTES = {
    '/install': async (b) => { const n = pieceName(b.name); return { name: n, version: await ensureInstalled(n, b.version, b.exact === true) } },
    '/metadata': opMetadata,
    '/options': opOptions,
    '/run': opRun,
    '/validate': opValidate,
    '/update': async (b) => updatePiece(pieceName(b.name)),
    '/oauth/url': opOAuthUrl,
    '/oauth/claim': opOAuthClaim,
    '/installed': async () => ({ pieces: state() }),
}

function authorized(req) {
    if (!TOKEN) return true
    const got = Buffer.from(String(req.headers['x-minihub-token'] || ''))
    const want = Buffer.from(TOKEN)
    return got.length === want.length && crypto.timingSafeEqual(got, want)
}

function createServer() {
    return http.createServer(async (req, res) => {
        try {
            const url = new URL(req.url, PUBLIC_URL)
            if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true, node: process.version, pid: process.pid, script: SCRIPT_SHA })
            if (req.method === 'GET' && url.pathname.startsWith('/files/')) {
                // Sem token: a URL é aleatória e só existe no 127.0.0.1 (pieces leem os próprios arquivos).
                const f = localFile(url.pathname.slice('/files/'.length))
                if (!f || !fs.existsSync(f.path)) return send(res, 404, { error: 'arquivo não encontrado' })
                res.writeHead(200, { 'content-type': f.mime || 'application/octet-stream', 'content-disposition': `attachment; filename="${f.name.replace(/"/g, '')}"` })
                return fs.createReadStream(f.path).pipe(res)
            }
            if (!authorized(req)) return send(res, 401, { error: 'token inválido' })
            const route = req.method === 'POST' ? ROUTES[url.pathname] : undefined
            if (!route) return send(res, 404, { error: `rota desconhecida: ${req.method} ${url.pathname}` })
            const body = await readBody(req)
            const result = await route(body)
            send(res, 200, result)
        } catch (e) {
            send(res, e instanceof UserError ? e.status : 500, { error: errorText(e) })
        }
    })
}

function main() {
    mkdirs()
    cleanupFiles()
    setInterval(cleanupFiles, 6 * 3600 * 1000).unref()
    process.on('unhandledRejection', (e) => { console.error('[minihub] promessa sem tratamento:', errorText(e)) })
    process.on('uncaughtException', (e) => { console.error('[minihub] exceção sem tratamento:', errorText(e)) })
    const server = createServer()
    server.requestTimeout = 0
    server.listen(PORT, '127.0.0.1', () => console.log(`[minihub] ouvindo em ${PUBLIC_URL} (node ${process.version}, raiz ${ROOT})`))
}

module.exports = {
    pieceName, processProps, processValue, connectionForContext, authPropertyFor, oauthExpired, formatOAuth2Response,
    extractPiece, metadataOf, jsonSafe, outputJson, resolveTemplate, opRun, opMetadata, opOptions, opValidate, opOAuthUrl, opOAuthClaim,
    ensureInstalled, updatePiece, installedVersion, createServer, buildActionContext, refreshConnection, PKG, ROOT,
}

if (require.main === module) main()
