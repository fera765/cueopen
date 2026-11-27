package dev.fluwy.app.browser

import kotlinx.serialization.json.*

/**
 * Formata o snapshot da página para o modelo, sem dependências de Android (testável na JVM).
 *
 * O JS ([SNAPSHOT_JS]) devolve dados estruturados; aqui o texto é montado com orçamento por seção:
 * - resumo no topo (tipo, formulários, diálogo/cookie bloqueando, rolagem) para o modelo decidir rápido;
 * - estrutura em árvore pelos headings;
 * - conteúdo principal sem boilerplate (cookies, menus repetidos, linhas duplicadas);
 * - elementos interativos com prioridade para o que está na tela e no conteúdo principal,
 *   agrupados por região, com valor atual dos campos e links duplicados colapsados.
 * Os índices [n] continuam sendo os de data-fluwy (browser_click/browser_type).
 */
object SnapshotFormat {
    data class El(
        val i: Int, val role: String, val label: String, val href: String = "", val region: String = "main",
        val inView: Boolean = false, val value: String = "", val placeholder: String = "",
        val checked: Boolean? = null, val disabled: Boolean = false, val expanded: String = "", val required: Boolean = false,
    )
    data class Snap(
        val url: String, val title: String, val mainText: String, val fullText: String,
        val headings: List<Pair<Int, String>>, val elements: List<El>, val landmark: String = "body",
        val dialog: String = "", val forms: Int = 0, val ogType: String = "", val lang: String = "",
    )

    fun parse(raw: String): Snap? = runCatching {
        val o = Json.parseToJsonElement(raw).jsonObject
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
        val hs = (o["headings"] as? JsonArray).orEmpty().mapNotNull { h ->
            val ho = h as? JsonObject ?: return@mapNotNull null
            val lvl = ho.s("level").filter { it.isDigit() }.toIntOrNull() ?: 2
            ho.s("text").takeIf { it.isNotBlank() }?.let { lvl.coerceIn(1, 6) to it }
        }
        val els = (o["elements"] as? JsonArray).orEmpty().mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            El(
                i = eo.s("i").toIntOrNull() ?: return@mapNotNull null, role = eo.s("role").ifBlank { "element" },
                label = eo.s("label"), href = eo.s("href"), region = eo.s("region").ifBlank { "main" },
                inView = eo.b("inView") == true, value = eo.s("value"), placeholder = eo.s("ph"),
                checked = eo.b("checked"), disabled = eo.b("disabled") == true, expanded = eo.s("expanded"),
                required = eo.b("required") == true,
            )
        }
        Snap(
            url = o.s("url"), title = o.s("title"), mainText = o.s("mainText"), fullText = o.s("text"),
            headings = hs, elements = els, landmark = o.s("landmark").ifBlank { "body" }, dialog = o.s("dialog"),
            forms = o.s("forms").toIntOrNull() ?: 0, ogType = o.s("ogType"), lang = o.s("lang"),
        )
    }.getOrNull()

    private val BOILER = Regex(
        "(?i)^(aceitar( todos)?( os)? cookies?|accept( all)?( cookies)?|usamos cookies.*|we use cookies.*|" +
            "política de (privacidade|cookies)|privacy policy|cookie settings|configurações de cookies|" +
            "pular para o conteúdo( principal)?|skip to (main )?content|voltar ao topo|back to top|" +
            "todos os direitos reservados.*|all rights reserved.*|©.*|menu|fechar|close|×|✕)$"
    )

    /** Remove boilerplate, linhas repetidas e espaços; mantém a ordem do texto. */
    fun cleanText(text: String): String {
        val seen = HashMap<String, Int>()
        val out = ArrayList<String>()
        var blank = false
        for (raw in text.lines()) {
            val l = raw.replace(Regex("[\\t\\u00A0 ]+"), " ").trim()
            if (l.isEmpty()) { if (!blank && out.isNotEmpty()) out += ""; blank = true; continue }
            if (BOILER.matches(l)) continue
            val k = l.lowercase()
            val n = (seen[k] ?: 0) + 1; seen[k] = n
            // linha curta repetida = menu/rodapé; linha longa repetida = bloco duplicado
            if (n > 1 && (l.length < 60 || n > 2)) continue
            out += l; blank = false
        }
        return out.joinToString("\n").trim()
    }

    /** Corta no limite sem quebrar no meio da palavra. */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf(' ', max).takeIf { it > max * 0.8 } ?: max
        return s.take(cut).trimEnd() + " …[+${s.length - cut} caracteres; role ou use max_chars maior]"
    }

    fun pageKind(s: Snap): String = when {
        s.ogType.contains("product", true) || Regex("(?i)adicionar ao carrinho|add to cart|comprar agora|buy now").containsMatchIn(s.fullText) -> "produto"
        s.ogType.contains("article", true) -> "artigo/notícia"
        s.elements.any { it.role == "textbox" && Regex("(?i)senha|password").containsMatchIn(it.label + it.placeholder) } -> "login"
        Regex("(?i)/search|[?&](q|query|k|s)=").containsMatchIn(s.url) -> "resultados de busca"
        s.forms > 0 && s.elements.count { it.role in setOf("textbox", "combobox", "checkbox") } >= 3 -> "formulário"
        else -> "página"
    }

    private val REGION_ORDER = listOf("dialog", "main", "form", "header", "nav", "aside", "footer")
    private val REGION_NAME = mapOf("dialog" to "Diálogo aberto", "main" to "Conteúdo", "form" to "Formulário",
        "header" to "Topo", "nav" to "Navegação", "aside" to "Lateral", "footer" to "Rodapé")

    fun elementLine(e: El): String = buildString {
        append('[').append(e.i).append("] ").append(e.role)
        val label = e.label.ifBlank { e.placeholder }
        if (label.isNotBlank()) append(" \"").append(label.take(80)).append('"')
        if (e.value.isNotBlank() && e.value != label) append(" = \"").append(e.value.take(60)).append('"')
        else if (e.role == "textbox" && e.placeholder.isNotBlank() && e.label.isNotBlank()) append(" (vazio: ").append(e.placeholder.take(40)).append(')')
        e.checked?.let { append(if (it) " [marcado]" else " [desmarcado]") }
        if (e.required) append(" [obrigatório]")
        if (e.disabled) append(" [desativado]")
        if (e.expanded.isNotBlank()) append(" [expandido=").append(e.expanded).append(']')
        if (e.href.isNotBlank() && !e.href.startsWith("javascript", true)) append(" -> ").append(shortUrl(e.href))
    }

    fun shortUrl(u: String): String = u.replace(Regex("[?&](utm_[a-z]+|gclid|fbclid|ref_?)=[^&#]*"), "").take(120)

    /** Seleciona e agrupa os elementos dentro do orçamento; devolve as linhas e quantos ficaram de fora. */
    fun elementsBlock(els: List<El>, budget: Int, hasDialog: Boolean): Pair<String, Int> {
        // colapsa links idênticos (mesmo destino e rótulo) mantendo o primeiro índice
        val seen = HashSet<String>()
        val uniq = els.filter { e -> e.href.isBlank() || seen.add(e.href.lowercase() + "|" + e.label.lowercase()) }
        fun score(e: El): Int {
            var s = 0
            if (e.region == "dialog") s += 100
            if (e.inView) s += 40
            if (e.region == "main" || e.region == "form") s += 25
            if (e.role in setOf("textbox", "combobox", "button", "checkbox")) s += 15
            if (e.label.isBlank() && e.placeholder.isBlank()) s -= 30
            if (e.region == "footer") s -= 20
            return s
        }
        val chosen = ArrayList<El>(); var used = 0
        for (e in uniq.sortedWith(compareByDescending<El> { score(it) }.thenBy { it.i })) {
            val len = elementLine(e).length + 1
            if (used + len > budget) continue
            chosen += e; used += len
        }
        val byRegion = chosen.groupBy { if (it.region in REGION_ORDER) it.region else "main" }
        val txt = buildString {
            for (r in REGION_ORDER) {
                val list = byRegion[r] ?: continue
                append("# ").append(REGION_NAME[r]).append(if (r == "dialog" && hasDialog) " — resolva antes de interagir com o resto" else "").append('\n')
                list.sortedBy { it.i }.forEach { append(elementLine(it)).append('\n') }
            }
        }
        return txt to (uniq.size - chosen.size)
    }

    fun outline(hs: List<Pair<Int, String>>, budget: Int): String = buildString {
        val min = hs.minOfOrNull { it.first } ?: 1
        var last = ""
        for ((lvl, t) in hs) {
            if (t.equals(last, true)) continue; last = t
            val line = "  ".repeat((lvl - min).coerceIn(0, 4)) + "- " + t.take(140) + "\n"
            if (length + line.length > budget) { append("  …\n"); break }
            append(line)
        }
    }

    /**
     * Monta o corpo do snapshot (o que vem depois do cabeçalho Aba/URL/Título e itens com foto).
     * [maxChars] é o orçamento total: ~60% texto, ~30% elementos, ~10% estrutura.
     */
    fun body(s: Snap, maxChars: Int): String {
        val text = cleanText(s.mainText.ifBlank { s.fullText }.let { m ->
            // conteúdo principal muito curto (SPA, landmark errado) → usa a página inteira
            if (m.length < 400 && s.fullText.length > m.length * 2) s.fullText else m
        })
        val elBudget = (maxChars * 0.3).toInt().coerceAtLeast(800)
        val hBudget = (maxChars * 0.1).toInt().coerceAtLeast(300)
        val (elTxt, hidden) = elementsBlock(s.elements, elBudget, s.dialog.isNotBlank())
        val tBudget = (maxChars - elTxt.length - hBudget.coerceAtMost(s.headings.sumOf { it.second.length + 4 })).coerceAtLeast(maxChars / 2)
        val inputs = s.elements.count { it.role in setOf("textbox", "combobox") }
        return buildString {
            append("--- Resumo ---\n")
            append("Tipo: ").append(pageKind(s))
            append(" · ").append(s.elements.size).append(" elementos interativos")
            if (s.forms > 0) append(" · ").append(s.forms).append(" formulário(s), ").append(inputs).append(" campo(s)")
            append('\n')
            if (s.dialog.isNotBlank()) append("⚠ Diálogo/sobreposição aberto: \"").append(s.dialog.take(120)).append("\" — feche ou responda primeiro (itens em # Diálogo aberto).\n")
            if (s.headings.isNotEmpty()) append("\n--- Estrutura ---\n").append(outline(s.headings, hBudget))
            append("\n--- Conteúdo principal (").append(s.landmark).append(") ---\n")
            append(if (text.isBlank()) "(sem texto visível)" else clip(text, tBudget))
            append("\n\n--- Elementos interativos (use o número em browser_click/browser_type) ---\n")
            append(elTxt)
            if (hidden > 0) append("(+").append(hidden).append(" elementos menos relevantes omitidos; role a página ou leia de novo com max_chars maior)\n")
        }
    }

    /** JS do snapshot: marca data-fluwy, classifica região/visibilidade e devolve JSON estruturado. */
    val SNAPSHOT_JS = """
(function(){
  var vh=innerHeight||800, vw=innerWidth||400;
  function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return null;var st=getComputedStyle(e);if(st.visibility=='hidden'||st.display=='none'||+st.opacity===0)return null;return r;}
  function txt(s){return (s||'').replace(/\s+/g,' ').trim();}
  function dlg(){var c=[].slice.call(document.querySelectorAll('dialog[open],[role=dialog],[role=alertdialog],[aria-modal=true],[id*=cookie i],[class*=cookie i],[id*=consent i],[class*=consent i],[class*=modal i]'));
    for(var k=0;k<c.length;k++){var r=vis(c[k]);if(r&&r.width>vw*0.5&&r.height>60)return c[k];}return null;}
  var D=dlg();
  function region(e){if(D&&D.contains(e))return 'dialog';var p=e.closest('form,header,nav,aside,footer,main,article,[role=navigation],[role=banner],[role=contentinfo],[role=main],[role=complementary],[role=search]');
    if(!p)return 'main';var t=p.tagName.toLowerCase(),r=p.getAttribute('role')||'';
    if(t=='form'||r=='search')return 'form';if(t=='header'||r=='banner')return 'header';if(t=='nav'||r=='navigation')return 'nav';if(t=='aside'||r=='complementary')return 'aside';if(t=='footer'||r=='contentinfo')return 'footer';return 'main';}
  function lbl(e){var l=e.getAttribute('aria-label')||'';if(!l&&e.id){var f=document.querySelector('label[for="'+CSS.escape(e.id)+'"]');if(f)l=f.innerText;}
    if(!l&&e.closest('label'))l=e.closest('label').innerText;if(!l&&/^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName))l=e.name||'';
    if(!l)l=e.innerText||e.getAttribute('title')||e.getAttribute('alt')||'';if(!l){var im=e.querySelector&&e.querySelector('img[alt]');if(im)l=im.alt;}return txt(l).slice(0,80);}
  [].slice.call(document.querySelectorAll('[data-fluwy]')).forEach(function(e){e.removeAttribute('data-fluwy');});
  var els=[].slice.call(document.querySelectorAll('a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=option],[role=switch],[role=combobox],[contenteditable=true],summary,[onclick]'));
  var out=[],i=0;
  els.forEach(function(e){var r=vis(e);if(!r)return;e.setAttribute('data-fluwy',i);
    var T=e.tagName,ty=(e.type||'').toLowerCase();
    var role=e.getAttribute('role')||({A:'link',BUTTON:'button',TEXTAREA:'textbox',SELECT:'combobox',SUMMARY:'button'}[T])||(T=='INPUT'?(ty=='checkbox'?'checkbox':ty=='radio'?'radio':/^(submit|button|reset|image)$/.test(ty)?'button':'textbox'):e.isContentEditable?'textbox':'button');
    var o={i:i,role:role,label:lbl(e),region:region(e),inView:r.bottom>0&&r.top<vh&&r.right>0&&r.left<vw};
    if(e.href)o.href=String(e.href);
    if(T=='SELECT'){var op=e.options[e.selectedIndex];o.value=op?txt(op.text):'';}
    else if(role=='textbox'&&ty!='password')o.value=txt(e.value||(e.isContentEditable?e.innerText:'')).slice(0,60);
    else if(ty=='password'&&e.value)o.value='••••';
    if(e.placeholder)o.ph=txt(e.placeholder);
    if(role=='checkbox'||role=='radio'||role=='switch')o.checked=!!(e.checked||e.getAttribute('aria-checked')=='true');
    if(e.disabled||e.getAttribute('aria-disabled')=='true')o.disabled=true;
    if(e.required)o.required=true;
    if(e.getAttribute('aria-expanded'))o.expanded=e.getAttribute('aria-expanded');
    out.push(o);i++;});
  var hs=[];[].slice.call(document.querySelectorAll('h1,h2,h3,h4,[role=heading]')).forEach(function(h){if(!vis(h))return;var t=txt(h.innerText||h.getAttribute('aria-label'));if(t)hs.push({level:h.tagName.toLowerCase()=='div'?('h'+(h.getAttribute('aria-level')||2)):h.tagName.toLowerCase(),text:t.slice(0,180)});});
  var cands=[].slice.call(document.querySelectorAll('main,[role=main],article,#content,#main,.content,.post,.article'));
  var main=null,best=0;cands.forEach(function(c){var n=(c.innerText||'').length;if(n>best){best=n;main=c;}});
  var full=document.body?document.body.innerText:'';
  if(main&&best<full.length*0.15)main=null;
  var mt=function(k){var m=document.querySelector('meta[property="'+k+'"],meta[name="'+k+'"]');return m?(m.content||''):'';};
  var tiles=[],seenT={};
  [].slice.call(document.querySelectorAll('a[href]')).forEach(function(a){if(tiles.length>=24)return;var im=a.querySelector('img')||(a.closest('li,article,div')||a).querySelector('img');if(!im)return;
    var r=im.getBoundingClientRect();if(r.width<70||r.height<50)return;var src=im.currentSrc||im.getAttribute('data-src')||im.src||'';if(!/^https?:/.test(src)||/logo|sprite|icon|1x1/i.test(src))return;
    var box=a.closest('li,article,[class*=card],[class*=product],[class*=item]')||a;var t=txt(a.getAttribute('title')||a.innerText||im.alt);if(t.length<4||seenT[a.href])return;seenT[a.href]=1;
    var pr=(box.innerText||'').match(/R\$\s?[\d.]+(,\d{2})?|US\$\s?[\d.,]+|€\s?[\d.,]+/);tiles.push({t:t.slice(0,120),u:a.href,i:src,p:pr?pr[0]:''});});
  return JSON.stringify({url:location.href,title:document.title,lang:document.documentElement.lang||'',text:full.replace(/\n{3,}/g,'\n\n'),
    mainText:main?main.innerText:'',landmark:main?(main.tagName.toLowerCase()+(main.id?'#'+main.id:'')):'body',headings:hs.slice(0,80),elements:out.slice(0,400),
    dialog:D?txt(D.getAttribute('aria-label')||D.innerText).slice(0,120):'',forms:document.forms.length,
    ogImage:mt('og:image')||mt('twitter:image'),ogType:mt('og:type'),price:mt('product:price:amount'),tiles:tiles});
})()
""".trimIndent()
}
