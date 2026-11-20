package dev.cueopen.app.live

import dev.cueopen.app.CueApp
import dev.cueopen.app.sandbox.SandboxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Live Server: publica um site (pasta do workspace) ou uma API/servidor (porta local) numa URL pública HTTPS
 * DEFINITIVA, sem conta nem API key.
 *
 * Túnel principal: Cloudflare Quick Tunnel (`cloudflared tunnel --url`, sem conta) — link DIRETO, sem tela de aviso,
 * captcha ou autorização. A URL (*.trycloudflare.com) é nova a cada publicação/reinício do túnel.
 * Reserva: tunnl.gg (`stable@`, URL fixa por chave SSH, mas navegadores veem um aviso semanal) — só se o cloudflared
 * não rodar no aparelho.
 * Sites estáticos são servidos por `python3 -m http.server` no Linux do app; APIs usam a porta que o agente já subiu.
 */
object LiveServer {
    private const val DIR = "/opt/fluwy-live"
    private const val HOST = "proxy.tunnl.gg"
    private val URL_RE = Regex("\"url\"\\s*:\\s*\"(https://[a-z0-9.-]+)\"")
    /** URL do Quick Tunnel (ignora api.trycloudflare.com, que não tem hífen). */
    val CF_URL_RE = Regex("https://[a-z0-9]+(?:-[a-z0-9]+)+\\.trycloudflare\\.com")
    private const val CF_BIN = "$DIR/bin/cloudflared"

    data class Project(val slug: String, val title: String, val url: String?, val port: Int, val dir: String?, val active: Boolean)

    @Volatile var restoreStarted = false
    private val mutex = Mutex()
    private val tunnels = ConcurrentHashMap<String, Process>()
    private val servers = ConcurrentHashMap<String, Process>()
    private val sandbox get() = CueApp.instance.sandbox
    private fun hostDir() = File(sandbox.alpineDir, DIR.trimStart('/')).apply { mkdirs() }
    private fun registry() = File(hostDir(), "projects.json")

    fun slugOf(name: String): String = name.lowercase()
        .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "site" }

    /** Porta estável por projeto para o servidor estático (8800–8999). */
    fun staticPortOf(slug: String): Int = 8800 + (slug.hashCode() and 0x7fffffff) % 200

    // ---------------- Registro (projeto → URL definitiva) ----------------
    private fun readAll(): MutableMap<String, Project> = runCatching {
        val o = JSONObject(registry().readText())
        o.keys().asSequence().associateWith { k ->
            val p = o.getJSONObject(k)
            Project(k, p.optString("title", k), p.optString("url").ifBlank { null }, p.optInt("port"), p.optString("dir").ifBlank { null }, p.optBoolean("active"))
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun save(all: Map<String, Project>) {
        val o = JSONObject()
        all.values.forEach { p ->
            o.put(p.slug, JSONObject().put("title", p.title).put("url", p.url ?: "").put("port", p.port).put("dir", p.dir ?: "").put("active", p.active))
        }
        registry().writeText(o.toString(2))
    }

    fun projects(): List<Project> = readAll().values.sortedBy { it.title.lowercase() }

    // ---------------- Pré-requisitos ----------------
    private suspend fun ensureTools(log: (String) -> Unit): Boolean {
        val r = sandbox.exec(
            "command -v ssh >/dev/null && command -v ssh-keygen >/dev/null || apk add --no-cache openssh-client openssh-keygen >/dev/null 2>&1; " +
                "command -v python3 >/dev/null || apk add --no-cache python3 >/dev/null 2>&1; command -v ssh && command -v python3",
            cwd = "/tmp", timeoutSec = 600, idleTimeoutSec = 300,
        )
        if (r.exitCode != 0) log("Não consegui instalar ssh/python3 no Linux do app: ${r.output.takeLast(300)}")
        return r.exitCode == 0
    }

    /** Chave SSH do projeto (a URL estável vem dela). Gerada uma vez; nunca sai do aparelho. */
    private suspend fun ensureKey(slug: String): String {
        val key = "$DIR/keys/$slug"
        if (!File(hostDir(), "keys/$slug").exists())
            sandbox.exec("mkdir -p $DIR/keys && ssh-keygen -t ed25519 -N '' -C fluwy-live-$slug -f $key -q", cwd = "/tmp", timeoutSec = 60)
        return key
    }

    /** cloudflared: pacote do Alpine ou binário oficial do GitHub (cloudflare/cloudflared, Apache-2.0). */
    private suspend fun ensureCloudflared(log: (String) -> Unit): String? {
        val r = sandbox.exec(
            "mkdir -p $DIR/bin; " +
                "if $CF_BIN --version >/dev/null 2>&1; then echo OK; exit 0; fi; " +
                "if command -v cloudflared >/dev/null || apk add --no-cache cloudflared >/dev/null 2>&1; then " +
                "  ln -sf \"$(command -v cloudflared)\" $CF_BIN; $CF_BIN --version >/dev/null 2>&1 && { echo OK; exit 0; }; fi; " +
                "case $(uname -m) in aarch64|arm64) A=arm64;; x86_64) A=amd64;; armv7*|armv8l|arm*) A=arm;; i?86) A=386;; *) A=arm64;; esac; " +
                "command -v curl >/dev/null || apk add --no-cache curl >/dev/null 2>&1; " +
                "rm -f $CF_BIN; curl -fsSL --retry 3 -o $CF_BIN.tmp https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${'$'}A " +
                "&& chmod +x $CF_BIN.tmp && mv $CF_BIN.tmp $CF_BIN && $CF_BIN --version && echo OK",
            cwd = "/tmp", timeoutSec = 600, idleTimeoutSec = 240,
        )
        if (r.output.contains("OK") && File(hostDir(), "bin/cloudflared").exists()) return CF_BIN
        log("cloudflared indisponível: ${r.output.takeLast(300)}")
        dev.cueopen.app.diag.ErrorLog.log("live", "cloudflared indisponível: ${r.output.takeLast(500)}")
        return null
    }

    /** Lê a URL mais recente do log do túnel (o Quick Tunnel gera outra ao reconectar). */
    private fun latestCfUrl(f: File): String? = runCatching { CF_URL_RE.findAll(f.readText()).lastOrNull()?.value }.getOrNull()

    private fun alive(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 600); true }
    }.getOrDefault(false)

    // ---------------- Publicar / parar ----------------
    /**
     * Publica [dirHost] (pasta já resolvida para o caminho real) ou a [port] local já em execução.
     * Devolve o texto para o modelo, com a URL definitiva.
     */
    suspend fun publish(title: String, dirHost: String?, port: Int?, spaFallback: Boolean = false): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val logs = StringBuilder()
            if (!runCatching { sandbox.isInstalled() }.getOrDefault(false)) return@withContext "ERRO: o Linux do app não está instalado (Configurações › Sandbox)."
            if (!ensureTools { logs.appendLine(it) }) return@withContext "ERRO: $logs"
            val slug = slugOf(title)
            val all = readAll()
            all[slug]?.let { kill(it) } // republicar: nada do túnel/servidor antigo fica órfão no ar
            // 1) O que vai ao ar: pasta estática (servidor próprio) ou porta já aberta.
            val targetPort: Int
            if (dirHost != null) {
                val d = File(dirHost)
                if (!d.isDirectory) return@withContext "ERRO: a pasta $dirHost não existe."
                if (!File(d, "index.html").exists() && d.listFiles()?.none { it.name.endsWith(".html") } == true)
                    return@withContext "ERRO: a pasta não tem index.html (nem outro .html) para publicar."
                // Projeto de build (Vite/React/Next) servido como arquivos soltos: o browser recebe .jsx como text/plain e a página fica em branco.
                val pkg = File(d, "package.json").takeIf { it.isFile }?.readText().orEmpty()
                val idx = File(d, "index.html").takeIf { it.isFile }?.readText().orEmpty()
                if (pkg.isNotEmpty() && (Regex("\"(vite|next|react-scripts|@vitejs/[^\"]+)\"").containsMatchIn(pkg) || Regex("src=[\"']/?src/[^\"']+\\.(jsx|tsx|ts)").containsMatchIn(idx)))
                    return@withContext "ERRO: ${d.name} é um projeto de build (Vite/React). Servido como arquivos soltos, o .jsx chega como text/plain e a página fica em branco. " +
                        "Faça `npm run build` e publique a pasta dist (dir=<projeto>/dist), ou suba o servidor de desenvolvimento em segundo plano e publique a porta (port=…)."
                targetPort = staticPortOf(slug)
                servers.remove(slug)?.destroy()
                if (!alive(targetPort)) {
                    val serverLog = File(hostDir(), "$slug.server.log")
                    servers[slug] = sandbox.spawn(": ${marker(slug)}; cd '${dirHost.replace("'", "")}' && exec python3 -m http.server $targetPort --bind 127.0.0.1", serverLog)
                    repeat(30) { if (alive(targetPort)) return@repeat; delay(300) }
                    if (!alive(targetPort)) return@withContext "ERRO: o servidor do site não subiu: ${runCatching { serverLog.readText().takeLast(400) }.getOrDefault("")}"
                }
            } else {
                targetPort = port ?: return@withContext "ERRO: informe dir (pasta do site) ou port (servidor/API já rodando)."
                if (!alive(targetPort)) return@withContext "ERRO: nada respondendo em 127.0.0.1:$targetPort. Suba o servidor (em segundo plano) antes de publicar."
            }
            // 2) Túnel: Cloudflare Quick Tunnel (link direto, sem aviso); tunnl.gg só como reserva.
            tunnels.remove(slug)?.destroy()
            runCatching { SandboxService.start(CueApp.instance, "Live Server no ar") }
            var url: String? = null
            var direct = true
            val cf = ensureCloudflared { logs.appendLine(it) }
            if (cf != null) {
                val cfLog = File(hostDir(), "$slug.cloudflared.log").apply { delete() }
                // O link vive enquanto ESTE processo viver: --retries alto faz o cloudflared reconectar por quedas de rede
                // sem sair (mesma URL). O loop só age se o processo morrer de vez (aí sai URL nova, registrada no log).
                tunnels[slug] = sandbox.spawn(
                    ": ${marker(slug)}; while true; do $cf tunnel --no-autoupdate --protocol http2 --edge-ip-version auto --retries 100000 --grace-period 30s --url http://127.0.0.1:$targetPort; echo FLUWY_TUNNEL_RESTART; sleep 3; done", cfLog,
                )
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline && url == null) {
                    url = latestCfUrl(cfLog)
                    if (url == null) delay(600)
                }
                if (url == null) {
                    val tail = runCatching { cfLog.readText().takeLast(500) }.getOrDefault("")
                    dev.cueopen.app.diag.ErrorLog.log("live", "Quick Tunnel de '$title' não abriu: $tail")
                    tunnels.remove(slug)?.destroy()
                }
            }
            if (url == null) {
                direct = false
                if (!ensureTools { logs.appendLine(it) }) return@withContext "ERRO: $logs"
                val key = ensureKey(slug)
                val tLog = File(hostDir(), "$slug.tunnel.jsonl").apply { delete() }
                val cmd = ": ${marker(slug)}; mkdir -p $DIR && while true; do ssh -n -T -i $key -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new " +
                    "-o UserKnownHostsFile=$DIR/known_hosts -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes " +
                    "-R 80:localhost:$targetPort stable@$HOST output=json host=localhost:$targetPort; sleep 5; done"
                tunnels[slug] = sandbox.spawn(cmd, tLog)
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline && url == null) {
                    url = runCatching { URL_RE.find(tLog.readText())?.groupValues?.get(1) }.getOrNull()
                    if (url == null) delay(700)
                }
                if (url == null) {
                    val tail = runCatching { tLog.readText().takeLast(500) }.getOrDefault("")
                    dev.cueopen.app.diag.ErrorLog.log("live", "Túnel de '$title' não abriu: $tail")
                    return@withContext "ERRO: o túnel público não abriu em 45 s (sem internet ou serviço indisponível). Detalhe: $logs $tail"
                }
            }
            runCatching { SandboxService.start(CueApp.instance, "Live Server no ar") } // agora com túnel vivo: segura o Wi-Fi
            all[slug] = Project(slug, title, url, targetPort, dirHost, true)
            save(all)
            val serve = if (dirHost != null) "site estático da pasta" else "servidor local na porta $targetPort"
            if (direct) "✅ Publicado: $url\nProjeto: $title · Serve: $serve. Link direto (abre o site na hora, sem tela de aviso). " +
                "Atenção: este link vale enquanto o túnel estiver no ar; ao publicar de novo ou reiniciar o app, sai um link novo."
            else "✅ Publicado (modo reserva): $url\nProjeto: $title · Serve: $serve. O túnel direto não funcionou neste aparelho; " +
                "neste modo a URL é fixa, mas navegadores podem ver um aviso semanal antes do site."
        }
    }

    /** Marca colocada nos comandos do projeto: permite matar o laço e os filhos dentro do Linux do app. */
    private fun marker(slug: String) = "fluwy-live:$slug:"

    /** Acha o projeto pelo nome: exato, depois parcial; sem nome (ou nome sem par) e um único no ar → esse. */
    fun resolve(name: String, all: Collection<Project> = projects()): Project? {
        val slug = slugOf(name)
        all.firstOrNull { it.slug == slug }?.let { return it }
        if (name.isNotBlank()) all.filter { it.slug.contains(slug) || slug.contains(it.slug) || it.title.equals(name, true) }
            .let { m -> (m.filter { it.active }.takeIf { it.size == 1 } ?: m.takeIf { it.size == 1 })?.let { return it.first() } }
        return all.filter { it.active }.singleOrNull()
    }

    /** Mata TUDO do projeto: o laço de reconexão, o cloudflared/ssh e o servidor estático (não só o processo do proot). */
    private suspend fun kill(p: Project) {
        tunnels.remove(p.slug)?.destroy(); servers.remove(p.slug)?.destroy()
        val port = p.port
        // [x]: o padrão não casa com a linha de comando do próprio shell que roda o pkill.
        val pats = listOf("[f]luwy-live:${p.slug}:", "[u]rl http://127.0.0.1:$port", "[l]ocalhost:$port stable@", "[h]ttp.server $port")
        val sh = pats.joinToString(" ; ") { "pkill -f '$it'" } + " ; sleep 0.6 ; " + pats.joinToString(" ; ") { "pkill -9 -f '$it'" } + " ; true"
        runCatching { sandbox.exec(sh, cwd = "/tmp", timeoutSec = 30) }
    }

    /** Despublica: tira o link do ar, mata o app/servidor e marca o projeto como parado (não volta ao abrir o app). */
    suspend fun stop(title: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAll()
            val all2 = all.values
            val stopAll = title.trim().lowercase() in setOf("*", "all", "todos", "tudo")
            val targets = if (stopAll) all2.filter { it.active || tunnels[it.slug] != null }
                else listOfNotNull(resolve(title, all2))
            if (targets.isEmpty()) {
                val on = all2.filter { it.active }.joinToString { it.title }
                return@withContext if (on.isBlank()) "Nenhum projeto publicado no ar." else "Não achei \"$title\". No ar: $on. Use project com um desses nomes (ou \"todos\")."
            }
            targets.forEach { p -> kill(p); all[p.slug] = p.copy(active = false) }
            save(all)
            if (!hasActive()) runCatching { SandboxService.start(CueApp.instance, "Terminal ativo") }
            targets.joinToString("\n") { p ->
                val left = if (p.dir != null && alive(p.port)) " (aviso: a porta ${p.port} ainda responde)" else ""
                "⏹ \"${p.title}\" despublicado: o link ${p.url ?: ""} saiu do ar e o app foi encerrado.$left"
            }
        }
    }

    /** Há projeto ativo (o serviço segura o Wi-Fi acordado só nesse caso). */
    fun hasActive(): Boolean = tunnels.values.any { it.isAlive }

    fun status(): String {
        val list = projects()
        if (list.isEmpty()) return "Nenhum projeto publicado ainda."
        return list.joinToString("\n") { p ->
            val u = latestCfUrl(File(hostDir(), "${p.slug}.cloudflared.log"))?.takeIf { tunnels[p.slug]?.isAlive == true } ?: p.url
            "- ${p.title}: ${u ?: "(sem URL ainda)"} · ${if (p.active && tunnels[p.slug]?.isAlive == true) "no ar" else if (p.active) "ativo (reconecta ao abrir o app)" else "parado"}"
        }
    }

    /** Ao abrir o app: projetos que estavam no ar voltam sozinhos, na mesma URL. */
    suspend fun restoreActive() {
        projects().filter { it.active && tunnels[it.slug]?.isAlive != true }.forEach { p ->
            runCatching { publish(p.title, p.dir, if (p.dir == null) p.port else null) }
        }
    }
}
