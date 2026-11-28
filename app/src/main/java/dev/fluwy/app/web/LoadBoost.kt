package dev.fluwy.app.web

/**
 * Acelera o carregamento das páginas no WebView sem quebrar o site (JVM-testável):
 * - [shouldBlock]: corta rastreadores, anúncios e beacons (nunca a página principal nem scripts do próprio site);
 * - [START_JS]: injetado no início do documento — imagens/iframes fora da tela viram lazy,
 *   decodificação assíncrona e faz requestIdleCallback existir (alguns sites travam sem ele).
 */
object LoadBoost {
    private val BLOCK_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adservice.google.", "connect.facebook.net", "facebook.com/tr", "analytics.tiktok.com",
        "hotjar.com", "clarity.ms", "scorecardresearch.com", "quantserve.com", "taboola.com", "outbrain.com",
        "criteo.com", "criteo.net", "adnxs.com", "amazon-adsystem.com", "pubmatic.com", "rubiconproject.com",
        "openx.net", "casalemedia.com", "moatads.com", "chartbeat.com", "newrelic.com", "nr-data.net",
        "segment.io", "segment.com/v1", "mixpanel.com", "amplitude.com", "fullstory.com", "mouseflow.com",
        "yandex.ru/metrika", "mc.yandex.ru", "bat.bing.com", "ads.linkedin.com", "px.ads.", "adsrvr.org",
    )
    private val BLOCK_PATH = Regex("""(?i)/(gen_204|collect\?|beacon|pixel\.gif|__utm\.gif|log_event|tr\?id=)""")

    /** true = responder vazio. Página principal nunca é bloqueada. */
    fun shouldBlock(url: String, mainFrame: Boolean): Boolean {
        if (mainFrame) return false
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        val hostPath = u.substringAfter("://")
        return BLOCK_HOSTS.any { hostPath.contains(it) } || BLOCK_PATH.containsMatchIn(u)
    }

    /** Busca de lista (sem render visual): também corta imagem, fonte e vídeo. */
    fun shouldBlockForSearch(url: String, mainFrame: Boolean): Boolean =
        shouldBlock(url, mainFrame) || (!mainFrame && Regex("""(?i)\.(png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf|otf|mp4|webm|m3u8)(\?|#|$)""").containsMatchIn(url))

    val START_JS = """
(function(){if(window.__fluwyBoost)return;window.__fluwyBoost=1;
 try{if(!window.requestIdleCallback){window.requestIdleCallback=function(cb){return setTimeout(function(){cb({didTimeout:false,timeRemaining:function(){return 8}})},1)};window.cancelIdleCallback=clearTimeout;}}catch(e){}
 function lazy(n){try{if(n.tagName==='IMG'||n.tagName==='IFRAME'){if(!n.hasAttribute('loading')){var r=n.getBoundingClientRect();if(r.top>innerHeight*1.5)n.setAttribute('loading','lazy');}if(n.tagName==='IMG'&&!n.hasAttribute('decoding'))n.setAttribute('decoding','async');}}catch(e){}}
 try{new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var a=ms[i].addedNodes;for(var j=0;j<a.length;j++){var n=a[j];if(n.nodeType!==1)continue;lazy(n);if(n.querySelectorAll){var q=n.querySelectorAll('img,iframe');for(var k=0;k<q.length&&k<200;k++)lazy(q[k]);}}}}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
""".trimIndent()
}

/** Quando parar de ler a lista (JVM-testável). */
object SearchPoll {
    /** ≥6 resultados já basta; depois do carregamento, para com ≥1 após 2 leituras ou sem nada após 5. */
    fun done(hits: Int, finished: Boolean, afterFinish: Int): Boolean =
        hits >= 6 || (finished && ((hits >= 1 && afterFinish >= 2) || afterFinish >= 5))
    fun interval(finished: Boolean): Long = if (finished) 250 else 200
}
