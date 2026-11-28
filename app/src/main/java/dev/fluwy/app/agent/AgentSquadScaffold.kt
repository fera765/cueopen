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

/** Mata servidores de desenvolvimento órfãos numa porta (o destroy() do proot não derruba o processo filho). */
internal suspend fun AgentRunner.killPort(port: Int, sessionId: String?) {
    val pats = listOf("[v]ite.*--port $port", "[n]ext dev -p $port", "[r]eact-scripts start", "PORT=$port").filterNot { it == "[r]eact-scripts start" }
    sandbox.exec(pats.joinToString(" ; ") { "pkill -f '$it'" } + " ; fuser -k $port/tcp 2>/dev/null ; sleep 0.5 ; " + pats.joinToString(" ; ") { "pkill -9 -f '$it'" } + " ; true",
        cwd = "/tmp", timeoutSec = 20, sessionId = sessionId)
}

/** Status HTTP e começo da resposta do preview (0 = sem resposta). */
internal fun AgentRunner.previewStatus(url: String, readTimeoutMs: Int = 3_000): Pair<Int, String> = runCatching {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 1_500; c.readTimeout = readTimeoutMs; c.instanceFollowRedirects = true
    try {
        val code = c.responseCode
        // Só os primeiros 400 caracteres (readText() baixava o módulo inteiro e podia levar minutos).
        val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { rd -> val b = CharArray(400); val n = rd.read(b); if (n > 0) String(b, 0, n) else "" } }.getOrNull().orEmpty()
        code to body
    } finally { c.disconnect() }
}.getOrDefault(0 to "")

internal fun AgentRunner.portAlive(port: Int): Boolean = runCatching {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500); true }
}.getOrDefault(false)

/**
 * Config do Vite que herda a do projeto e liga o polling do watcher (HMR ao vivo no proot), sem mexer no arquivo
 * do usuário. Vite empacota a config (e o import da original, inclusive .ts) antes de rodar. null = usa a do projeto.
 */
internal fun AgentRunner.viteWatchConfig(dir: File, addTailwind: Boolean = false): String? {
    val base = listOf("vite.config.ts", "vite.config.mts", "vite.config.js", "vite.config.mjs", "vite.config.cjs").firstOrNull { File(dir, it).isFile }
    val wrapper = File(dir, ".fluwy.vite.config.mjs")
    val importBase = base?.let { "import base from './$it'\n" } ?: ""
    return runCatching {
        wrapper.writeText(importBase + "import { mergeConfig } from 'vite'\n" +
            (if (addTailwind) "import tailwindcss from '@tailwindcss/vite'\n" else "") +
            "const over = { " + (if (addTailwind) "plugins: [tailwindcss()], " else "") +
            // .fluwy/ (plano do time, build de verificação) fora do watcher: gravar ali recarregava a página em loop.
            "server: { host: '127.0.0.1', watch: { usePolling: true, interval: 250, ignored: ['**/.fluwy/**', '**/.fluwy.vite.config.mjs', '**/node_modules/**', '**/.git/**'] } } }\n" +
            (if (base != null) "export default async (env) => mergeConfig(typeof base === 'function' ? await base(env) : (base || {}), over)\n"
                else "export default over\n"))
        wrapper.name
    }.getOrNull()
}

/** Imports default do App (./components/X) que apontam para arquivo ausente, vazio ou sem export default. */
internal fun AgentRunner.brokenImports(dir: File): List<String> {
    val app = listOf("src/App.tsx", "src/App.jsx", "src/App.js").map { File(dir, it) }.firstOrNull { it.isFile } ?: return emptyList()
    val text = app.readText()
    return Regex("""import\s+([A-Z]\w*)\s+from\s+['"](\.{1,2}/[^'"]+)['"]""").findAll(text).mapNotNull { m ->
        val rel = m.groupValues[2]
        val base = File(app.parentFile, rel).normalize()
        val f = listOf("", ".jsx", ".tsx", ".js", ".ts", "/index.jsx", "/index.tsx", "/index.js").map { File(base.path + it) }.firstOrNull { it.isFile }
        when {
            f == null -> "${m.groupValues[1]} ($rel: arquivo não existe)"
            f.length() == 0L -> "${m.groupValues[1]} (${f.name}: arquivo vazio)"
            !Regex("""export\s+default\b""").containsMatchIn(f.readText()) -> "${m.groupValues[1]} (${f.name}: sem export default)"
            else -> null
        }
    }.toList()
}

/** Âncoras internas (href="#x") sem elemento com id="x" no código do projeto. Devolve (quebradas, ids existentes). */
internal fun AgentRunner.brokenAnchors(dir: File): Pair<List<String>, List<String>> {
    val files = (listOf(File(dir, "index.html")) + File(dir, "src").walkTopDown().filter { it.isFile && it.extension in setOf("jsx", "tsx", "js", "ts", "html", "vue", "svelte") }.take(400).toList())
        .filter { it.isFile }
    val text = files.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
    val hrefs = Regex("""href\s*=\s*\{?\s*["'`]#([A-Za-z][\w-]*)["'`]""").findAll(text).map { it.groupValues[1] }.toSet()
    val ids = (Regex("""\bid\s*=\s*\{?\s*["'`]([A-Za-z][\w-]*)["'`]""").findAll(text) + Regex("""\bid\s*:\s*["'`]([A-Za-z][\w-]*)["'`]""").findAll(text))
        .map { it.groupValues[1] }.toSet()
    // Menus montados de uma lista ({ id: 'servicos' } / href={`#${s.id}`}) contam como ids conhecidos.
    return hrefs.filter { it !in ids && it != "top" }.sorted() to ids.sorted()
}

internal fun AgentRunner.pascal(raw: String): String = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
    .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }.takeIf { it.isNotBlank() && it[0].isLetter() } ?: ""
internal fun AgentRunner.kebab(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

/**
 * Tailwind pedido (ou já no projeto): instala se faltar, liga o plugin do Vite (pela config do app, sem mexer na do
 * usuário) e garante @import "tailwindcss" no CSS de entrada importado pelo main. Sem isso as classes não estilizam nada.
 * Devolve true se o plugin do Vite precisa entrar pela config do app.
 */
internal suspend fun AgentRunner.ensureTailwind(sessionId: String, dir: File, guestDir: String, wanted: Boolean, log: (String) -> Unit): Boolean {
    val pkgFile = File(dir, "package.json")
    var pkg = runCatching { pkgFile.readText() }.getOrDefault("")
    val has = pkg.contains("\"tailwindcss\"")
    if (!has && !wanted) return false
    val v3 = Regex("\"tailwindcss\"\\s*:\\s*\"[\\^~>=]*3").containsMatchIn(pkg)
    val needs = when {
        !has -> "tailwindcss@^4 @tailwindcss/vite@^4"
        !v3 && !pkg.contains("@tailwindcss/vite") && !pkg.contains("@tailwindcss/postcss") && pkg.contains("\"vite\"") -> "@tailwindcss/vite@^4"
        else -> null
    }
    if (needs != null) {
        log("Ligando o Tailwind CSS ($needs)…")
        val r = sandbox.exec(dev.fluwy.app.sandbox.InteractivePrompts.ENV + " && npm install -D --no-audit --no-fund --loglevel=error $needs < /dev/null",
            cwd = guestDir, timeoutSec = 600, idleTimeoutSec = 240, sessionId = sessionId)
        if (r.exitCode != 0) { log("Não consegui instalar o Tailwind: " + r.output.takeLast(300)); return false }
        pkg = runCatching { pkgFile.readText() }.getOrDefault(pkg)
    }
    // CSS de entrada: o importado pelo main (ou um novo src/index.css importado no topo do main).
    val main = listOf("src/main.tsx", "src/main.jsx", "src/main.ts", "src/main.js", "src/index.tsx", "src/index.jsx").map { File(dir, it) }.firstOrNull { it.isFile }
    if (main != null) {
        val mainText = main.readText()
        val cssRel = Regex("""import\s+['"](\./[^'"]+\.css)['"]""").find(mainText)?.groupValues?.get(1)
        val css = if (cssRel != null) File(main.parentFile, cssRel.removePrefix("./")) else File(dir, "src/index.css")
        if (cssRel == null) main.writeText("import './" + css.relativeTo(main.parentFile).path + "'\n" + mainText)
        val text = if (css.isFile) css.readText() else ""
        val isV3 = v3 && !pkg.contains("@tailwindcss/")
        val fixed = if (isV3) {
            if (text.contains("@tailwind base")) text else "@tailwind base;\n@tailwind components;\n@tailwind utilities;\n\n$text"
        } else {
            // Tema padrão do create-vite (body centralizado, cores escuras) atrapalha o layout: sai junto.
            val base = if (text.contains("place-items: center") && text.contains(":root")) "" else text
            val noOld = base.lines().filterNot { it.trim().startsWith("@tailwind ") }.joinToString("\n")
            if (Regex("""@import\s+['"]tailwindcss['"]""").containsMatchIn(noOld)) noOld else "@import \"tailwindcss\";\n\n$noOld"
        }
        if (fixed != text) { css.parentFile?.mkdirs(); css.writeText(fixed) }
    }
    val viteCfg = listOf("vite.config.ts", "vite.config.mts", "vite.config.js", "vite.config.mjs").map { File(dir, it) }.firstOrNull { it.isFile }?.readText().orEmpty()
    return !v3 && pkg.contains("@tailwindcss/vite") && !viteCfg.contains("@tailwindcss/vite")
}

/**
 * Antes do time: cada componente do plano já existe (esqueleto visível) e já está no App. Assim o usuário vê cada seção
 * sendo construída no lugar dela desde o primeiro salvamento — sem isso a tela ficava branca até o App ser editado no fim.
 * Devolve tarefa → caminho do arquivo do componente (no sandbox).
 */
internal fun AgentRunner.wireComponents(dir: File, guestDir: String, tasks: List<SquadTask>, log: (String) -> Unit): Map<String, Pair<String, String>> {
    val pkg = runCatching { File(dir, "package.json").readText() }.getOrDefault("")
    if (!pkg.contains("\"react\"")) return emptyMap()
    val ts = File(dir, "src/main.tsx").isFile || File(dir, "src/App.tsx").isFile
    val ext = if (ts) "tsx" else "jsx"
    val out = linkedMapOf<String, Pair<String, File>>()  // taskId → (Nome, arquivo)
    val ids = mutableMapOf<String, String>()  // Nome → id da seção (o do alvo do líder, ex.: #inicio; senão o nome)
    tasks.forEach { t ->
        val fromFile = Regex("""(?:^|/)(?:components|sections|pages)/([A-Za-z][A-Za-z0-9]*)\.(?:jsx|tsx|js|ts)$""").find(t.file)?.groupValues?.get(1)
        // Só tarefas que SÃO um componente: arquivo em components/sections/pages, ou sem arquivo mas com alvo #id.
        // (Antes um arquivo de dados como src/data/news.js com alvo #noticias virava "componente" e a tarefa era
        // reprovada por não ter export default; tarefas com alvo #root ganhavam um Root.jsx fantasma.)
        if (t.file.isNotBlank() && fromFile == null) return@forEach
        val name = fromFile ?: t.target.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.startsWith("#") || it.startsWith(".") }?.let { pascal(it.drop(1)) }.orEmpty()
        if (name.isBlank() || name in setOf("App", "Main", "Root", "Body", "Html", "Index")) return@forEach
        val f = when {
            t.file.startsWith("/") -> File(dir, t.file.substringAfter(guestDir.trimEnd('/') + "/", "src/components/$name.$ext"))
            t.file.isNotBlank() -> File(dir, t.file.removePrefix("./"))
            else -> File(dir, "src/components/$name.$ext")
        }
        if (out.values.none { it.first == name }) {
            out[t.id] = name to f
            ids[name] = t.target.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.startsWith("#") && it.length > 1 }
                ?.drop(1)?.takeIf { Regex("^[A-Za-z][\\w-]*$").matches(it) } ?: kebab(name)
        }
    }
    if (out.isEmpty()) return emptyMap()
    // Ordem natural da página: menu/cabeçalho em cima, rodapé/contato final embaixo (antes o Footer podia cair no meio).
    fun rank(n: String) = when {
        Regex("(?i)^(nav|navbar|header|cabecalho|menu|topbar)").containsMatchIn(n) -> 0
        Regex("(?i)(footer|rodape)").containsMatchIn(n) -> 2
        else -> 1
    }
    val ordered = out.entries.sortedBy { rank(it.value.first) }.associate { it.key to it.value }
    out.clear(); out.putAll(ordered)
    out.values.forEach { (name, f) ->
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            val sid = ids[name] ?: kebab(name)
            f.writeText("export default function $name() {\n  return (\n    <section id=\"$sid\" className=\"${kebab(name)} fluwy-placeholder\"\n" +
                "      style={{ padding: '40px 24px', margin: '12px', border: '2px dashed #c9c9c9', borderRadius: 12, textAlign: 'center', color: '#888', fontFamily: 'system-ui' }}>\n" +
                "      $name · em construção…\n    </section>\n  )\n}\n")
        }
    }
    val app = listOf("src/App.tsx", "src/App.jsx", "src/App.js").map { File(dir, it) }.firstOrNull { it.isFile } ?: File(dir, "src/App.$ext")
    val appText = if (app.isFile) app.readText() else ""
    val wired = out.values.count { (name, _) -> Regex("""import\s+$name\b""").containsMatchIn(appText) }
    // Projeto já montado (edições seguintes): nunca reescreve o App que tem seções próprias; componente novo é ligado pela tarefa.
    val appHasOwnSections = Regex("""import\s+\w+\s+from\s+['"]\./(components|sections|pages)/""").containsMatchIn(appText)
    if (wired == 0 && appHasOwnSections) {
        log("Projeto já existente: o App não foi reescrito; componentes novos (${out.values.joinToString { it.first }}) entram no App pela própria tarefa.")
    } else if (wired == 0) {
        val imports = out.values.joinToString("") { (name, f) ->
            val rel = f.relativeTo(app.parentFile).path.substringBeforeLast('.').let { if (it.startsWith(".")) it else "./$it" }
            "import $name from '$rel'\n"
        }
        app.parentFile?.mkdirs()
        app.writeText(imports + "\nexport default function App() {\n  return (\n    <div className=\"min-h-screen\">\n" +
            out.values.joinToString("") { "      <${it.first} />\n" } + "    </div>\n  )\n}\n")
        log("App ligado a ${out.size} componentes antes do time entrar (cada seção aparece no lugar dela).")
    } else if (wired < out.size) log("O App já importa $wired de ${out.size} componentes; os demais precisam ser ligados pelo líder.")
    return out.mapValues { (_, v) -> (guestDir.trimEnd('/') + "/" + v.second.relativeTo(dir).path) to (ids[v.first] ?: kebab(v.first)) }
}

/** Projeto React + Vite em branco que já roda (o time constrói em cima dele). */
internal fun AgentRunner.scaffoldReactVite(dir: File) {
    dir.mkdirs()
    fun w(path: String, text: String) { val f = File(dir, path); if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(text.trimIndent() + "\n") } }
    w("package.json", """
        {
          "name": "app", "private": true, "version": "0.0.0", "type": "module",
          "scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" },
          "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1" },
          "devDependencies": { "@vitejs/plugin-react": "^4.3.4", "vite": "^5.4.11", "tailwindcss": "^4.1.0", "@tailwindcss/vite": "^4.1.0" }
        }
    """)
    w("vite.config.js", """
        import { defineConfig } from 'vite'
        import react from '@vitejs/plugin-react'
        import tailwindcss from '@tailwindcss/vite'
        export default defineConfig({ plugins: [react(), tailwindcss()], server: { host: '127.0.0.1', watch: { usePolling: true, interval: 250 } } })
    """)
    w("index.html", """
        <!doctype html>
        <html lang="pt-BR">
          <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" /><title>App</title></head>
          <body><div id="root"></div><script type="module" src="/src/main.jsx"></script></body>
        </html>
    """)
    w("src/main.jsx", """
        import React from 'react'
        import ReactDOM from 'react-dom/client'
        import App from './App.jsx'
        import './index.css'
        ReactDOM.createRoot(document.getElementById('root')).render(<App />)
    """)
    w("src/App.jsx", """
        export default function App() {
          return (
            <main className="app">
              <section id="hero" className="hero"><h1>Em construção…</h1></section>
            </main>
          )
        }
    """)
    w("src/index.css", """
        @import "tailwindcss";
        body { margin: 0; font-family: system-ui, sans-serif; }
    """)
}
