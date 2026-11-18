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
 * Túnel: tunnl.gg (github.com/klipitkas/tunnl.gg, MIT) por SSH com `stable@` — a URL é derivada da chave SSH, então
 * cada projeto tem a sua chave (gerada uma vez e guardada no app) e volta SEMPRE na mesma URL, inclusive depois de
 * reiniciar o app. Um loop reconecta se a conexão cair (limites do plano grátis: 2 h ocioso / 24 h por conexão).
 * Sites estáticos são servidos por `python3 -m http.server` no Linux do app; APIs usam a porta que o agente já subiu.
 */
object LiveServer {
    private const val DIR = "/opt/fluwy-live"
    private const val HOST = "proxy.tunnl.gg"
    private val URL_RE = Regex("\"url\"\\s*:\\s*\"(https://[a-z0-9.-]+)\"")

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
            val prev = all[slug]
            // 1) O que vai ao ar: pasta estática (servidor próprio) ou porta já aberta.
            val targetPort: Int
            if (dirHost != null) {
                val d = File(dirHost)
                if (!d.isDirectory) return@withContext "ERRO: a pasta $dirHost não existe."
                if (!File(d, "index.html").exists() && d.listFiles()?.none { it.name.endsWith(".html") } == true)
                    return@withContext "ERRO: a pasta não tem index.html (nem outro .html) para publicar."
                targetPort = staticPortOf(slug)
                servers.remove(slug)?.destroy()
                if (!alive(targetPort)) {
                    val serverLog = File(hostDir(), "$slug.server.log")
                    servers[slug] = sandbox.spawn("cd '${dirHost.replace("'", "")}' && exec python3 -m http.server $targetPort --bind 127.0.0.1", serverLog)
                    repeat(30) { if (alive(targetPort)) return@repeat; delay(300) }
                    if (!alive(targetPort)) return@withContext "ERRO: o servidor do site não subiu: ${runCatching { serverLog.readText().takeLast(400) }.getOrDefault("")}"
                }
            } else {
                targetPort = port ?: return@withContext "ERRO: informe dir (pasta do site) ou port (servidor/API já rodando)."
                if (!alive(targetPort)) return@withContext "ERRO: nada respondendo em 127.0.0.1:$targetPort. Suba o servidor (em segundo plano) antes de publicar."
            }
            // 2) Túnel estável (reconecta sozinho; mesma chave ⇒ mesma URL).
            val key = ensureKey(slug)
            tunnels.remove(slug)?.destroy()
            val tLog = File(hostDir(), "$slug.tunnel.jsonl").apply { delete() }
            val cmd = "mkdir -p $DIR && while true; do ssh -n -T -i $key -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new " +
                "-o UserKnownHostsFile=$DIR/known_hosts -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes " +
                "-R 80:localhost:$targetPort stable@$HOST output=json host=localhost:$targetPort; sleep 5; done"
            tunnels[slug] = sandbox.spawn(cmd, tLog)
            runCatching { SandboxService.start(CueApp.instance, "Live Server no ar") }
            var url: String? = null
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline && url == null) {
                url = runCatching { URL_RE.find(tLog.readText())?.groupValues?.get(1) }.getOrNull()
                if (url == null) delay(700)
            }
            if (url == null) {
                val tail = runCatching { tLog.readText().takeLast(500) }.getOrDefault("")
                dev.cueopen.app.diag.ErrorLog.log("live", "Túnel de '$title' não abriu: $tail")
                return@withContext "ERRO: o túnel público não abriu em 45 s (sem internet ou serviço indisponível). Detalhe: $tail"
            }
            if (prev?.url != null && prev.url != url)
                dev.cueopen.app.diag.ErrorLog.log("live", "URL de '$title' mudou de ${prev.url} para $url (o servidor de túnel trocou a chave de host).")
            all[slug] = Project(slug, title, url, targetPort, dirHost, true)
            save(all)
            "✅ Publicado: $url\nProjeto: $title (URL definitiva — publicar de novo usa sempre esta mesma URL). " +
                "Serve: ${if (dirHost != null) "site estático da pasta" else "servidor local na porta $targetPort"}. " +
                "Visitantes no navegador podem ver um aviso de segurança do túnel (uma vez por semana) antes do site."
        }
    }

    suspend fun stop(title: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val slug = slugOf(title)
            tunnels.remove(slug)?.destroy(); servers.remove(slug)?.destroy()
            val all = readAll()
            val p = all[slug] ?: return@withContext "Nenhum projeto publicado com o nome \"$title\"."
            all[slug] = p.copy(active = false); save(all)
            "⏹ \"$title\" saiu do ar. A URL ${p.url ?: ""} continua reservada para este projeto e volta igual ao publicar de novo."
        }
    }

    fun status(): String {
        val list = projects()
        if (list.isEmpty()) return "Nenhum projeto publicado ainda."
        return list.joinToString("\n") { p ->
            "- ${p.title}: ${p.url ?: "(sem URL ainda)"} · ${if (p.active && tunnels[p.slug]?.isAlive == true) "no ar" else if (p.active) "ativo (reconecta ao abrir o app)" else "parado"}"
        }
    }

    /** Ao abrir o app: projetos que estavam no ar voltam sozinhos, na mesma URL. */
    suspend fun restoreActive() {
        projects().filter { it.active && tunnels[it.slug]?.isAlive != true }.forEach { p ->
            runCatching { publish(p.title, p.dir, if (p.dir == null) p.port else null) }
        }
    }
}
