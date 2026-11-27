package dev.cueopen.app.browser

import android.content.Context
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.WebApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Motor do Web API Scraper. Usa um browser PRÓPRIO do app (chave [KEY]), separado dos browsers das sessões, mas com
 * a mesma infraestrutura deles: mesmo perfil (cookies/logins), mesmo proxy, mesmo palco off-screen (a página fica
 * "visível" para o Chromium, então carga, timers, setTimeout e rolagem funcionam com o app em segundo plano).
 * O WebView solto que existia antes ficava invisível: a carga e os timers travavam.
 *
 * Fluxo: monta a URL pelo template (JS) → navega → espera wait_for → roda o código (Promise aguardada) → envelope JSON.
 */
object WebApiRunner {
    const val KEY = "__webapi__"
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var helpersJs: String? = null
    private fun helpers(ctx: Context): String = helpersJs ?: ctx.assets.open("webapi/helpers.js").bufferedReader().readText().also { helpersJs = it }
    private fun host(): BrowserHost = CueApp.instance.browserFor(KEY)

    /** Acesso exclusivo à página das APIs (ex.: webhook do WhatsApp lendo a fila de mensagens) sem disputar com uma chamada. */
    suspend fun <T> withPage(block: suspend (BrowserHost, String) -> T): T = lock.withLock { block(host(), helpers(CueApp.instance)) }

    /** Executa a API. Devolve o JSON final (texto) ou {"ok":false,"error":…}. */
    suspend fun run(ctx: Context, a: WebApi, args: JsonObject, timeoutMs0: Long = 45_000): String = lock.withLock {
        // Com biblioteca injetada (WA-JS) a 1ª carga do WhatsApp Web pode levar ~30 s: mais folga.
        val timeoutMs = if (a.inject.isNotEmpty()) maxOf(timeoutMs0, 120_000) else timeoutMs0
        val started = System.currentTimeMillis()
        val h = host()
        val helpers = helpers(ctx)
        val full = JsonObject(a.params.filter { it.default.isNotBlank() && it.name !in args }.associate { p ->
            p.name to (p.default.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(p.default))
        } + args)
        val argsJs = full.toString()
        var stage = "montar a URL"
        val out = withTimeoutOrNull(timeoutMs) {
            // WhatsApp Web só funciona em modo desktop (no mobile redireciona para /mobile/): força, mesmo com o chip em Mobile.
            val wantDesktop = a.desktop || a.urlTemplate.contains("web.whatsapp.com")
            if (h.ui.value.desktop != wantDesktop) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runCatching { h.setDesktop(wantDesktop) } }
            // 1) URL pelo template (precisa de uma página qualquer para rodar JS).
            if (h.runJs("1").startsWith("ERROR")) h.navigate("about:blank")
            val url = h.runJs("(function(){$helpers;const args=$argsJs;return `${a.urlTemplate}`})()").trim()
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url))
                return@withTimeoutOrNull err(a, full, "URL inválida a partir do template: ${url.take(200)}")
            // 2) Carrega com a espera completa do browser do app (redirecionamentos, SPA, conteúdo estável).
            // Mesma página já aberta (ex.: WhatsApp Web carregado): não recarrega — chamadas seguidas ficam instantâneas.
            val here = h.currentUrl()
            fun base(u: String) = u.substringBefore('#').substringBefore('?').trimEnd('/')
            if (base(here) != base(url) || here.isBlank()) {
                stage = "carregar $url"
                val nav = h.navigate(url)
                if (nav.startsWith("ERROR")) return@withTimeoutOrNull err(a, full, nav.lineSequence().first().take(300), url)
            }
            // Bibliotecas (ex.: WA-JS): baixadas uma vez pelo app e injetadas só se ainda não estão na página.
            suspend fun injectAll(): String? {
                for (lib in a.inject) {
                    stage = "injetar biblioteca $lib"
                    val key = "__inj_" + Integer.toHexString(lib.hashCode())
                    if (h.runJs("!!window['$key']") == "true") continue
                    val code = library(ctx, lib) ?: return "não consegui baixar a biblioteca $lib"
                    h.evalRaw(code + "\n;window['$key']=true;1", 60_000)
                }
                return null
            }
            injectAll()?.let { return@withTimeoutOrNull err(a, full, it, url) }
            // WhatsApp: espera o WA-JS ficar pronto; travou (aba antiga, WhatsApp atualizado) → recarrega a página uma vez.
            if (url.contains("web.whatsapp.com") && a.inject.isNotEmpty()) {
                suspend fun waState(ms: Long): String {
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < ms) {
                        val st = h.runJs("(function(){if(document.querySelector('canvas[aria-label*=\"QR\"],div[data-ref] canvas,[data-testid=\"qrcode\"]'))return 'qr';return (window.WPP&&WPP.isReady)?'ready':'wait'})()")
                        if (st == "ready" || st == "qr") return st
                        kotlinx.coroutines.delay(400)
                    }
                    return "wait"
                }
                stage = "esperar o WhatsApp Web ficar pronto"
                if (waState(30_000) == "wait") {
                    stage = "recarregar o WhatsApp Web (não ficou pronto)"
                    h.navigate(url)
                    injectAll()?.let { return@withTimeoutOrNull err(a, full, it, url) }
                    if (waState(40_000) == "wait") return@withTimeoutOrNull err(a, full, "WhatsApp Web não ficou pronto nem depois de recarregar (sem internet, página travada ou WA-JS incompatível). Abra o Browser em web.whatsapp.com e confira.", url)
                }
            }
            // Bloqueio anti-robô: não tenta contornar — avisa.
            val blocked = h.runJs("(function(){$helpers;try{return api.blocked()}catch(e){return ''}})()").let { if (it.startsWith("(sem valor")) "" else it }
            if (blocked.isNotBlank() && !blocked.startsWith("ERROR")) return@withTimeoutOrNull buildJsonObject {
                put("api", a.slug); put("ok", false); put("blocked", true); put("args", full); put("url", url); put("error", blocked)
                put("hint", "O site pediu verificação humana. O usuário pode resolvê-la uma vez no Browser (mesma sessão); depois a API volta a funcionar.")
            }.toString()
            // 3) wait_for
            if (a.waitFor.isNotBlank()) {
                stage = "esperar o seletor wait_for (${a.waitFor})"
                val sel = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(a.waitFor))
                var n = 0
                while (n++ < 40 && h.runJs("!!document.querySelector($sel)") != "true") kotlinx.coroutines.delay(250)
            }
            // 4) Código da API — runJs aguarda a Promise e devolve o erro real.
            stage = "rodar o código (algum await não terminou: confira api.wait/fetch/seletores)"
            val r = h.runJs("(async()=>{$helpers;const args=$argsJs;try{const r=await (async function(args,api){\n${a.code}\n})(args,api);" +
                "return JSON.stringify({ok:true,url:location.href,data:r===undefined?null:r})}catch(e){return JSON.stringify({ok:false,url:location.href,error:String(e&&e.message||e)})}})()", promiseTimeoutMs = if (a.inject.isNotEmpty()) 100_000 else 35_000)
            val o = runCatching { json.parseToJsonElement(r).jsonObject }.getOrNull()
                ?: return@withTimeoutOrNull err(a, full, "o código não devolveu JSON: ${r.take(300)}", url)
            if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return@withTimeoutOrNull err(a, full, o["error"]?.jsonPrimitive?.contentOrNull ?: "erro no código", o["url"]?.jsonPrimitive?.contentOrNull)
            envelope(a, full, o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["data"], System.currentTimeMillis() - started)
        }
        val res = out ?: err(a, full, "tempo esgotado (${timeoutMs / 1000}s) na etapa: $stage")
        val ok = res.contains("\"ok\":true")
        val count = runCatching { (Json.parseToJsonElement(res).jsonObject["results"] as? kotlinx.serialization.json.JsonArray)?.size?.toString() }.getOrNull()
            ?: Regex("\"count\":(\\d+)").find(res)?.groupValues?.get(1) ?: "sem campo results"
        dev.cueopen.app.diag.ActivityLog.webApi(a.slug, ok, System.currentTimeMillis() - started,
            Regex("\"url\":\"([^\"]*)").find(res)?.groupValues?.get(1) ?: "-",
            if (ok) "args=$argsJs resultados=${count ?: "?"}" else "args=$argsJs etapa=$stage erro=" + (Regex("\"error\":\"([^\"]*)").find(res)?.groupValues?.get(1) ?: ""))
        if (!ok) runCatching { dev.cueopen.app.diag.ErrorLog.log("webapi", "${a.slug} falhou na etapa \"$stage\": " + res.take(600), null, a.slug) }
        res
    }

    /** Requisições fetch/XHR que a página da API fez (para o criador enxergar de onde vêm os dados). */
    suspend fun network(): String = lock.withLock {
        host().runJs("(function(){var seen={};return performance.getEntriesByType('resource').filter(function(e){return /fetch|xmlhttprequest/.test(e.initiatorType)})" +
            ".map(function(e){return e.initiatorType+' '+Math.round(e.transferSize/1024)+'KB '+e.name}).filter(function(x){var k=x.split('?')[0];if(seen[k])return false;seen[k]=1;return true}).slice(-30).join('\\n')})()")
    }

    /** Baixa (e guarda por 24 h) uma biblioteca JS de URL https. */
    private suspend fun library(ctx: Context, url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        val f = java.io.File(java.io.File(ctx.filesDir, "webapi-libs").apply { mkdirs() }, Integer.toHexString(url.hashCode()) + ".js")
        if (f.isFile && f.length() > 1000 && System.currentTimeMillis() - f.lastModified() < 24 * 3_600_000L) return@withContext f.readText()
        runCatching {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.BROWSER, okhttp3.OkHttpClient()).newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body!!.string().also { if (it.length > 1000) f.writeText(it) }
            }
        }.getOrElse { if (f.isFile) f.readText() else null }
    }

    private fun envelope(a: WebApi, args: JsonObject, url: String, data: JsonElement?, ms: Long): String = buildJsonObject {
        put("api", a.slug); put("ok", true); put("args", args); put("url", url)
        put("updated_at", java.time.Instant.now().toString()); put("took_ms", ms)
        // Objeto devolvido pelo código entra no nível de cima (results, count, has_more, next_page…); outros tipos em "data".
        if (data is JsonObject) data.forEach { (k, v) -> if (k !in setOf("api", "ok", "args", "url")) put(k, v) }
        else put("data", data ?: kotlinx.serialization.json.JsonNull)
    }.toString()

    private fun err(a: WebApi, args: JsonObject, msg: String, url: String? = null): String = buildJsonObject {
        put("api", a.slug); put("ok", false); put("args", args); url?.let { put("url", it) }; put("error", msg.take(500))
    }.toString()
}
