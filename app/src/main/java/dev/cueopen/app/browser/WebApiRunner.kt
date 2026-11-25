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

    /** Executa a API. Devolve o JSON final (texto) ou {"ok":false,"error":…}. */
    suspend fun run(ctx: Context, a: WebApi, args: JsonObject, timeoutMs: Long = 45_000): String = lock.withLock {
        val started = System.currentTimeMillis()
        val h = host()
        val helpers = helpers(ctx)
        val full = JsonObject(a.params.filter { it.default.isNotBlank() && it.name !in args }.associate { p ->
            p.name to (p.default.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(p.default))
        } + args)
        val argsJs = full.toString()
        var stage = "montar a URL"
        val out = withTimeoutOrNull(timeoutMs) {
            if (h.ui.value.desktop != a.desktop) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runCatching { h.setDesktop(a.desktop) } }
            // 1) URL pelo template (precisa de uma página qualquer para rodar JS).
            if (h.runJs("1").startsWith("ERROR")) h.navigate("about:blank")
            val url = h.runJs("(function(){$helpers;const args=$argsJs;return `${a.urlTemplate}`})()").trim()
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url))
                return@withTimeoutOrNull err(a, full, "URL inválida a partir do template: ${url.take(200)}")
            // 2) Carrega com a espera completa do browser do app (redirecionamentos, SPA, conteúdo estável).
            stage = "carregar $url"
            val nav = h.navigate(url)
            if (nav.startsWith("ERROR")) return@withTimeoutOrNull err(a, full, nav.lineSequence().first().take(300), url)
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
                "return JSON.stringify({ok:true,url:location.href,data:r===undefined?null:r})}catch(e){return JSON.stringify({ok:false,url:location.href,error:String(e&&e.message||e)})}})()", promiseTimeoutMs = 35_000)
            val o = runCatching { json.parseToJsonElement(r).jsonObject }.getOrNull()
                ?: return@withTimeoutOrNull err(a, full, "o código não devolveu JSON: ${r.take(300)}", url)
            if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return@withTimeoutOrNull err(a, full, o["error"]?.jsonPrimitive?.contentOrNull ?: "erro no código", o["url"]?.jsonPrimitive?.contentOrNull)
            envelope(a, full, o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["data"], System.currentTimeMillis() - started)
        }
        out ?: err(a, full, "tempo esgotado (${timeoutMs / 1000}s) na etapa: $stage")
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
