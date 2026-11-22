package dev.cueopen.app.web

import dev.cueopen.app.web.WebExtract.SearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Partes puras (sem Android) da busca "site como API": URLs de cada buscador, o script que extrai os resultados
 * da página já renderizada, o parse do JSON devolvido, a limpeza de links de redirecionamento, a fusão dos
 * buscadores e o cache. Testáveis na JVM.
 */
object SearchApi {
    enum class Engine(val label: String) { GOOGLE("Google"), BING("Bing"), DUCK("DuckDuckGo") }

    fun url(e: Engine, q: String): String {
        val enc = java.net.URLEncoder.encode(q.trim(), "UTF-8")
        return when (e) {
            Engine.GOOGLE -> "https://www.google.com/search?q=$enc&hl=pt-BR&gl=br&num=10"
            Engine.BING -> "https://www.bing.com/search?q=$enc&setlang=pt-br&cc=br"
            Engine.DUCK -> "https://html.duckduckgo.com/html/?q=$enc&kl=br-pt"
        }
    }

    /** Script que roda dentro da página e devolve JSON [{t,u,s}] (vazio enquanto a página ainda não tem resultados). */
    val EXTRACT_JS = """
(function(){try{
var out=[],seen={};
function im(b){if(!b)return '';var l=b.querySelectorAll('img');for(var k=0;k<l.length;k++){var x=l[k].getAttribute('data-src')||l[k].getAttribute('data-lazysrc')||l[k].getAttribute('src')||'';if(/^https?:/.test(x)&&!/logo|favicon|sprite|1x1/i.test(x))return x;}return '';}
function add(t,u,s,i){t=(t||'').trim();u=(u||'').trim();if(!t||!u||seen[u])return;if(!/^https?:/.test(u)&&u.indexOf('/url?')!==0)return;seen[u]=1;out.push({t:t,u:u,s:(s||'').replace(/\s+/g,' ').trim().slice(0,300),i:i||''});}
var h=location.hostname;
if(h.indexOf('google.')>=0){
 document.querySelectorAll('a h3').forEach(function(h3){var a=h3.closest('a');if(!a)return;var box=a.closest('div.g,div.MjjYud,div[data-hveid],div.Gx5Zad')||a.parentElement;
  var sn='';if(box){var e=box.querySelector('div.VwiC3b,div[data-sncf],span.aCOpRe,div.BNeawe.s3v9rd,div[style*="-webkit-line-clamp"]');sn=e?e.innerText:'';}
  add(h3.innerText,a.getAttribute('href')||a.href,sn,im(box));});
}else if(h.indexOf('bing.')>=0){
 document.querySelectorAll('li.b_algo').forEach(function(li){var a=li.querySelector('h2 a');if(!a)return;var p=li.querySelector('.b_caption p,.b_lineclamp2,.b_lineclamp3,.b_algoSlug');add(a.innerText,a.href,p?p.innerText:'',im(li));});
}else{
 document.querySelectorAll('.result').forEach(function(r){if(r.classList.contains('result--ad'))return;var a=r.querySelector('a.result__a');if(!a)return;var p=r.querySelector('.result__snippet');add(a.innerText,a.getAttribute('href'),p?p.innerText:'');});
}
var block=/unusual traffic|captcha|not a robot|consent\.google|Antes de continuar/i.test(document.body?document.body.innerText.slice(0,3000)+location.href:'');
return JSON.stringify({hits:out.slice(0,15),blocked:block&&out.length===0});
}catch(e){return JSON.stringify({hits:[],err:String(e)});}})();
""".trimIndent()

    data class Page(val hits: List<SearchHit>, val blocked: Boolean)

    /** JSON do script → resultados limpos. A WebView devolve a string JSON entre aspas (escapada); aceita os dois. */
    fun parse(raw: String?, e: Engine): Page {
        if (raw.isNullOrBlank() || raw == "null") return Page(emptyList(), false)
        return runCatching {
            var el = Json.parseToJsonElement(raw.trim())
            if (el is JsonPrimitive && el.isString) el = Json.parseToJsonElement(el.content)
            val o = el.jsonObject
            fun str(x: JsonObject, k: String) = (x[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val hits = (o["hits"]?.jsonArray ?: emptyList()).mapNotNull { h ->
                val x = h as? JsonObject ?: return@mapNotNull null
                val u = cleanUrl(str(x, "u"), e) ?: return@mapNotNull null
                SearchHit(str(x, "t").trim(), u, str(x, "s").trim(), str(x, "i").trim().takeIf { PageMeta.isGoodImage(it) }.orEmpty())
            }.filter { it.title.isNotBlank() && !isEngineInternal(it.url) }.distinctBy { it.url }
            Page(hits, o["blocked"]?.jsonPrimitive?.content == "true")
        }.getOrDefault(Page(emptyList(), false))
    }

    /** Remove redirecionamentos dos buscadores (/url?q=, uddg=, bing.com/ck/a?u=a1<base64>). */
    fun cleanUrl(u: String, e: Engine): String? {
        var x = u.trim()
        if (x.startsWith("//")) x = "https:$x"
        if (x.startsWith("/url?")) x = "https://www.google.com$x"
        Regex("[?&](?:q|url)=(https?[^&]+)").find(x)?.takeIf { x.contains("google.") && x.contains("/url?") }?.let { x = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
        Regex("[?&]uddg=([^&]+)").find(x)?.let { x = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
        if (x.contains("bing.com/ck/a")) Regex("[?&]u=a1([^&]+)").find(x)?.let { m ->
            runCatching {
                val b = m.groupValues[1].replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
                String(java.util.Base64.getDecoder().decode(b), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.startsWith("http") }?.let { x = it }
        }
        return x.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isEngineInternal(u: String): Boolean = Regex("""^https?://([a-z]+\.)?(google\.[a-z.]+/(search|preferences|advanced_search|setprefs)|bing\.com/(search|images|videos|news)|duckduckgo\.com/(\?|y\.js))""").containsMatchIn(u)

    /** Junta buscadores intercalando (1º do A, 1º do B, …) sem repetir link: mais cobertura no topo. */
    fun merge(lists: List<List<SearchHit>>, max: Int): List<SearchHit> {
        val seen = HashSet<String>(); val out = mutableListOf<SearchHit>()
        val key = { h: SearchHit -> h.url.lowercase().removeSuffix("/").replace(Regex("^https?://(www\\.)?"), "") }
        var i = 0
        while (out.size < max && lists.any { i < it.size }) {
            lists.forEach { l -> l.getOrNull(i)?.let { if (out.size < max && seen.add(key(it))) out += it } }
            i++
        }
        return out
    }

    fun format(query: String, hits: List<SearchHit>, sources: String): String =
        "Resultados para \"$query\" ($sources; abra os relevantes com web_fetch):\n" + hits.mapIndexed { i, h ->
            "${i + 1}. ${h.title}\n   ${h.url}" + (if (h.snippet.isNotBlank()) "\n   ${h.snippet.take(240)}" else "") + (if (h.image.isNotBlank()) "\n   imagem: ${h.image}" else "")
        }.joinToString("\n")

    /** Cache curto (mesma busca em sequência = instantânea). */
    class Cache(private val ttlMs: Long = 5 * 60_000L, private val size: Int = 40) {
        private val map = object : LinkedHashMap<String, Pair<Long, String>>(16, .75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, String>>?) = this.size > this@Cache.size
        }
        @Synchronized fun get(k: String, now: Long = System.currentTimeMillis()): String? = map[k.trim().lowercase()]?.takeIf { now - it.first < ttlMs }?.second
        @Synchronized fun put(k: String, v: String, now: Long = System.currentTimeMillis()) { map[k.trim().lowercase()] = now to v }
    }
}
