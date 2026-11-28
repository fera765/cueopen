package dev.fluwy.app.agent
import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

/**
 * Prepara o app AO VIVO antes do time entrar: pasta vazia vira um projeto React + Vite em branco; com package.json,
 * instala as dependências e sobe o servidor de desenvolvimento (http://127.0.0.1:PORTA, com HMR). Site estático
 * (index.html sem package.json): file://. URL já rodando: usada como está. null = não ficou de pé (ver log).
 */
internal suspend fun AgentRunner.squadPreviewUrl(sessionId: String, preview: String, log: (String) -> Unit, wantTailwind: Boolean = false): String? {
    val p = preview.trim()
    if (p.isBlank()) return null
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    // /public/workspace e caminhos relativos viram /root/workspace/… (o mesmo lugar que o agente e o time usam).
    var guest = p.removePrefix("file://").trimEnd('/').let {
        when {
            it.startsWith("/public/") || it == "/public" -> "/root" + it.removePrefix("/public")
            it.startsWith("/home/") -> "/root" + it.removePrefix("/home")
            it.startsWith("/") -> it
            else -> "/root/workspace/" + it.removePrefix("./")
        }
    }
    var f = sandbox.hostFile(guest, sessionId)
    val isFile = f.isFile || (!f.exists() && f.name.contains('.'))
    if (!isFile && !File(f, "package.json").isFile && !File(f, "index.html").isFile && f.isDirectory) {
        // Preview apontou para a pasta de cima (ex.: /root/workspace) e o projeto está numa subpasta: usa a subpasta.
        val subs = f.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" && File(it, "package.json").isFile }.orEmpty()
        if (subs.size == 1) { f = subs[0]; guest = guest.trimEnd('/') + "/" + subs[0].name; log("Projeto encontrado em $guest.") }
    }
    val dir = if (isFile) f.parentFile else f
    val guestDir = if (isFile) guest.substringBeforeLast('/') else guest
    squadProjectDir[sessionId] = guestDir
    if (store.session(sessionId)?.squadProject != guestDir) store.editSession(sessionId) { it.copy(squadProject = guestDir) }
    val pkg = File(dir, "package.json")
    val staticSite = !pkg.isFile && (File(dir, "index.html").isFile || isFile)
    if (staticSite) return "file://" + (if (isFile) f else File(dir, "index.html")).absolutePath
    if (!pkg.isFile) {
        // Nunca cria o projeto solto na raiz do workspace (misturaria com outros arquivos): usa /root/workspace/app.
        if (guestDir.trimEnd('/') == "/root/workspace" && dir.listFiles()?.any { !it.name.startsWith(".") } == true) {
            log("A raiz do workspace já tem outros arquivos; o projeto vai em /root/workspace/app.")
            return squadPreviewUrl(sessionId, "/root/workspace/app", log, wantTailwind)
        }
        log("Criando o projeto React + Vite em branco em $guestDir…"); scaffoldReactVite(dir)
    }
    val text = runCatching { pkg.readText() }.getOrDefault("")
    if (text.contains("\"vite\"") && !File(dir, "index.html").isFile) {
        log("O projeto Vite não tinha index.html na raiz; criei um padrão (div#root + /src/main.jsx).")
        File(dir, "index.html").writeText("<!doctype html>\n<html lang=\"pt-BR\"><head><meta charset=\"UTF-8\" /><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" /><title>App</title></head>\n" +
            "<body><div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script></body></html>\n")
    }
    val port = 5173 + (guestDir.hashCode() and 0x7fffffff) % 300
    squadDevServers[sessionId]?.let { (pt, proc) -> if (pt == port && proc.isAlive && previewStatus("http://127.0.0.1:$port/").first == 200) return "http://127.0.0.1:$port/" }
    squadDevServers.remove(sessionId)?.second?.destroy()
    val q = "'" + guestDir.replace("'", "") + "'"
    // 1) Dependências: só segue com tudo instalado (erro aqui não liga o time).
    if (!File(dir, "node_modules").isDirectory) {
        log("Instalando as dependências (npm install)…")
        val r = sandbox.exec("(command -v npm >/dev/null || apk add --no-cache nodejs npm >/dev/null 2>&1) && " + dev.fluwy.app.sandbox.InteractivePrompts.ENV +
            " && npm install --no-audit --no-fund --loglevel=error < /dev/null",
            cwd = guestDir, timeoutSec = 900, idleTimeoutSec = 300, sessionId = sessionId)
        if (r.exitCode != 0 || !File(dir, "node_modules").isDirectory) { log("npm install falhou: " + r.output.takeLast(600)); return null }
    }
    // 2) Servidor de desenvolvimento.
    val tailwindPlugin = runCatching { ensureTailwind(sessionId, dir, guestDir, wantTailwind, log) }.getOrDefault(false)
    // API Node (Express, Fastify, Koa, Hono, Nest…) sem front-end: sobe com PORT e vale qualquer resposta HTTP.
    val isApi = !Regex("\"(vite|next|react-scripts)\"").containsMatchIn(text) &&
        Regex("\"(express|fastify|koa|hono|@nestjs/core|@hapi/hapi|restify|polka)\"").containsMatchIn(text)
    if (isApi) squadApi.add(sessionId) else squadApi.remove(sessionId)
    val scripts = runCatching { org.json.JSONObject(text).optJSONObject("scripts") }.getOrNull()
    val main = runCatching { org.json.JSONObject(text).optString("main") }.getOrDefault("").ifBlank {
        listOf("server.js", "index.js", "src/server.js", "src/index.js", "app.js", "src/app.js").firstOrNull { File(dir, it).isFile } ?: "index.js" }
    val run = when {
        isApi && scripts?.has("dev") == true -> "PORT=$port HOST=127.0.0.1 npm run dev"
        isApi && scripts?.has("start") == true -> "PORT=$port HOST=127.0.0.1 npm start"
        isApi -> "PORT=$port HOST=127.0.0.1 node $main"
        text.contains("\"next\"") -> "npx next dev -p $port -H 127.0.0.1"
        text.contains("\"vite\"") -> "npx vite --host 127.0.0.1 --port $port --strictPort" + (viteWatchConfig(dir, tailwindPlugin)?.let { " --config $it" } ?: "")
        text.contains("react-scripts") -> "BROWSER=none HOST=127.0.0.1 PORT=$port npx react-scripts start"
        else -> "HOST=127.0.0.1 PORT=$port npm run dev -- --host 127.0.0.1 --port $port"
    }
    log("Subindo o app na porta $port…")
    val logFile = File(sandbox.workspaceFor(sessionId), ".fluwy/devserver.log").apply { parentFile?.mkdirs(); delete() }
    // Polling: no Linux do app (proot) o watcher de arquivos do Vite/Next/CRA não vê as edições, e o HMR não atualiza a tela.
    // Servidor antigo (de outra rodada ou de antes do app reiniciar) segurando a porta: com --strictPort o novo morria
    // na hora e o app via "HTTP 0", enquanto um wget depois achava o antigo de pé. Derruba o antigo e espera a porta liberar.
    if (portAlive(port)) {
        killPort(port, sessionId)
        repeat(20) { if (portAlive(port)) kotlinx.coroutines.delay(300) }
    }
    // `env VAR=x cmd`: `exec VAR=x cmd` tentava executar "VAR=x" como programa (API Node e CRA nunca subiam).
    val proc = sandbox.spawn("cd $q && exec env $run < /dev/null", logFile, sessionId = sessionId,
        extraEnv = mapOf("CHOKIDAR_USEPOLLING" to "true", "CHOKIDAR_INTERVAL" to "250", "WATCHPACK_POLLING" to "true", "BROWSER" to "none"))
    squadDevServers[sessionId] = port to proc
    val url = "http://127.0.0.1:$port/"
    val deadline = System.currentTimeMillis() + 4 * 60_000L
    fun up(c: Int) = if (isApi) c in 100..499 else c == 200
    // Espera de verdade: a 1ª resposta do Vite (pré-empacotamento) pode levar dezenas de segundos no celular.
    // Só desiste antes do prazo se o processo morreu E a porta não abriu.
    var code = 0
    var body = ""
    while (System.currentTimeMillis() < deadline) {
        val r = if (portAlive(port)) previewStatus(url, 30_000) else (0 to "")
        code = r.first; body = r.second
        if (up(code)) break
        if (!proc.isAlive && !portAlive(port)) { kotlinx.coroutines.delay(1_500); if (!portAlive(port)) break }
        kotlinx.coroutines.delay(1_000)
    }
    if (up(code)) log("App respondeu (HTTP $code) na porta $port.")
    if (!up(code)) {
        log("O app não respondeu 200 (HTTP $code) ${body.take(200)}\nLog do servidor: " + runCatching { logFile.readText().takeLast(600) }.getOrDefault(""))
        return null
    }
    // 3) O código de entrada compila (Vite responde 500 se não).
    if (text.contains("\"vite\"")) {
        val entry = Regex("""src=["'](/[^"']+\.(?:jsx?|tsx?))["']""").find(runCatching { File(dir, "index.html").readText() }.getOrDefault(""))?.groupValues?.get(1)
        if (entry != null) {
            var res = previewStatus(url.trimEnd('/') + entry, 90_000)
            repeat(2) { if (res.first == 0) { kotlinx.coroutines.delay(2_000); res = previewStatus(url.trimEnd('/') + entry, 90_000) } }
            val (ec, eb) = res
            if (ec != 200) { log("$entry não compila (HTTP $ec): ${eb.take(300)}"); return null }
        }
    }
    return url
}

/**
 * Aquece o dev server antes do browser: pede o código de entrada e os módulos que ele importa (App, componentes e as
 * dependências pré-empacotadas do Vite). Sem isso a 1ª carga no WebView travava (só onPageStarted por 30 s) enquanto o
 * Vite empacotava o React, e no celular com pouca memória o renderer chegava a ser encerrado.
 */
internal fun AgentRunner.warmDevServer(url: String, html: String, progress: (Int) -> Unit = {}) {
    val base = url.trimEnd('/')
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(Regex("""src=["'](/[^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList() + "/@vite/client")
    // Teto TOTAL de 75 s: cada pedido usa no máximo o tempo que sobra (antes uma leitura sem limite travava o time aqui para sempre).
    val deadline = System.currentTimeMillis() + 75_000
    while (queue.isNotEmpty() && seen.size < 60) {
        val left = (deadline - System.currentTimeMillis()).toInt()
        if (left < 1_000) break
        val path = queue.removeFirst().substringBefore('?')
        if (!seen.add(path)) continue
        if (seen.size % 5 == 0) progress(seen.size)
        val body = readCapped(base + path, minOf(25_000, left), deadline)
        Regex("""(?:from\s*|import\s*\(?\s*)["'](/(?:src|node_modules/\.vite/deps)/[^"'?]+)""").findAll(body).forEach { queue.add(it.groupValues[1]) }
    }
}

/**
 * GET que nunca trava: além do readTimeout (que é por leitura e reinicia a cada pedaço — um servidor que "pinga" bytes
 * devagar segurava a leitura por minutos), lê em blocos e para no [deadline] ou em 3 MB.
 */
internal fun AgentRunner.readCapped(url: String, readMs: Int, deadline: Long): String = runCatching {
    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
        connectTimeout = 1_500; readTimeout = readMs.coerceAtLeast(500)
        try {
            if (responseCode !in 200..299) return@run ""
            val sb = StringBuilder(); val buf = CharArray(16_384)
            inputStream.bufferedReader().use { rd ->
                while (System.currentTimeMillis() < deadline && sb.length < 3_000_000) { val n = rd.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            }
            sb.toString()
        } finally { disconnect() }
    }
}.getOrDefault("")

internal fun AgentRunner.fetchText(url: String, readMs: Int): String = readCapped(url, readMs, System.currentTimeMillis() + readMs)

/** O App compila no dev server? (Vite: GET /src/App.jsx|tsx = 200). null = não dá para saber. */
internal fun AgentRunner.appModuleOk(url: String): Boolean? {
    val base = url.trimEnd('/')
    for (p in listOf("/src/App.jsx", "/src/App.tsx", "/src/App.js")) {
        val c = previewStatus(base + p, 60_000).first
        if (c == 200) return true
        if (c in 500..599) return false
    }
    return null
}

/** O app precisa estar VIVO no browser (página carregada e desenhando algo) antes de o time entrar. */
internal suspend fun AgentRunner.appAliveInBrowser(sessionId: String, url: String, log: (String) -> Unit): Boolean {
    val browser = browserFor(sessionId)
    if (sessionId !in squadApi) runCatching {
        log("Aquecendo o app (pré-carregando React e componentes)…")
        // Em paralelo e com espera limitada: mesmo que uma leitura bloqueie, o time segue em até 90 s.
        val warm = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).async {
            warmDevServer(url, fetchText(url, 20_000)) { n -> log("Aquecendo o app… $n módulos prontos") }
        }
        if (kotlinx.coroutines.withTimeoutOrNull(90_000) { warm.await() } == null) { warm.cancel(); log("Aquecimento passou de 90 s; seguindo para abrir o app.") }
    }
    // Motor do browser caiu 2+ vezes há pouco: não insiste em carregar/inspecionar (isso derrubava de novo); servidor OK basta.
    if (sessionId !in squadApi && browser.liteMode() && previewStatus(url, 15_000).first == 200 && appModuleOk(url) != false) {
        log("O browser do celular está instável (encerrado pelo Android há pouco); o time começa com o app no ar e a prévia abre quando o browser estabilizar.")
        kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { browser.navigate(url) } }
        return true
    }
    // Navegar nunca pode prender o time: o WebView do celular às vezes trava/renasce (renderer encerrado).
    kotlinx.coroutines.withTimeoutOrNull(40_000) { runCatching { browser.navigate(url) } }
    if (sessionId in squadApi) return previewStatus(url).first in 100..499  // API: responder já basta
    val deadline = System.currentTimeMillis() + 120_000  // 1ª compilação no celular pode passar de 1 min
    var last = ""
    var nextNav = System.currentTimeMillis() + 20_000
    var jsFails = 0
    while (System.currentTimeMillis() < deadline) {
        if (System.currentTimeMillis() > nextNav && !last.contains("ok")) { kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { browser.navigate(url) } }; nextNav = System.currentTimeMillis() + 20_000 }
        last = runCatching { browser.runJs(
            "(function(){var t=document.title||'';if(/não disponível|not available|ERR_/i.test(t+document.body.innerText.slice(0,200)))return 'erro';" +
                "var r=document.getElementById('root')||document.getElementById('__next')||document.getElementById('app')||document.body;" +
                "return (r&&r.children.length>0&&(r.innerText||'').trim().length+r.querySelectorAll('img,svg,canvas').length>0)?'ok':'vazio';})()") }.getOrDefault("")
        if (last.contains("ok")) return true
        // WebView sem responder JS (renderer ocupado/encerrado por memória) mas servidor e código OK: não barra o time por isso.
        if (last.startsWith("timeout") || last.startsWith("ERROR")) {
            jsFails++
            if (jsFails == 3) kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { browser.navigate(url) } }
            if (jsFails >= 5 && previewStatus(url, 15_000).first == 200 && (appModuleOk(url) != false)) {
                log("O browser do celular demorou a responder, mas o app está no ar e compilando (HTTP 200); o time vai começar.")
                return true
            }
        } else jsFails = 0
        if (last.contains("erro")) { kotlinx.coroutines.delay(1_500); kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { browser.navigate(url) } } }
        kotlinx.coroutines.delay(1_500)
    }
    // Servidor no ar e App compilando: o problema é o WebView do celular (lento/encerrado), não o projeto. O time começa
    // mesmo assim (antes ficava barrado e o pedido morria aqui).
    if (previewStatus(url, 15_000).first == 200 && appModuleOk(url) != false) {
        log("O browser do celular não respondeu a tempo, mas o app está no ar e compilando (HTTP 200); o time vai começar (preview pode demorar a aparecer).")
        return true
    }
    log("O app não apareceu no browser em 2 min (estado: ${last.ifBlank { "sem resposta" }}). Log do servidor: " +
        runCatching { File(sandbox.workspaceFor(sessionId), ".fluwy/devserver.log").readText().takeLast(500) }.getOrDefault(""))
    return false
}
