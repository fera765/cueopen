package dev.cueopen.app.browser

import dev.cueopen.app.web.LoadBoost
import dev.cueopen.app.web.SearchApi
import dev.cueopen.app.web.SearchPoll
import dev.cueopen.app.web.WebExtract.SearchHit
import dev.cueopen.app.workflow.WfRetry
import org.junit.Assert.*
import org.junit.Test

class SnapshotWebTest {
    private fun raw(els: String = "[]", main: String = "", text: String = "", extra: String = "") =
        """{"url":"https://loja.com/p/1","title":"T","text":${q(text)},"mainText":${q(main)},"landmark":"main","headings":[{"level":"h1","text":"Celular X"},{"level":"h2","text":"Ficha"}],"elements":$els,"forms":1$extra}"""
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test fun parseBasico() { val s = SnapshotFormat.parse(raw())!!; assertEquals("T", s.title); assertEquals(2, s.headings.size); assertEquals(1, s.headings[0].first) }
    @Test fun parseInvalido() = assertNull(SnapshotFormat.parse("não é json"))
    @Test fun parseElementoSemIndiceIgnorado() = assertEquals(0, SnapshotFormat.parse(raw("""[{"role":"link"}]"""))!!.elements.size)
    @Test fun limpaCookies() { val t = SnapshotFormat.cleanText("Aceitar cookies\nPreço R$ 10\nUsamos cookies para melhorar"); assertEquals("Preço R$ 10", t) }
    @Test fun removeMenuRepetido() = assertEquals("Início\nTexto", SnapshotFormat.cleanText("Início\nTexto\nInício"))
    @Test fun colapsaBrancos() = assertEquals("a\n\nb", SnapshotFormat.cleanText("a\n\n\n\n b "))
    @Test fun clipSemCortarPalavra() { val c = SnapshotFormat.clip("palavra ".repeat(100), 100); assertTrue(c.contains("…[+")); assertFalse(c.substringBefore(" …").endsWith("pal")) }
    @Test fun clipCurtoIntacto() = assertEquals("abc", SnapshotFormat.clip("abc", 10))
    @Test fun linhaCampo() { val l = SnapshotFormat.elementLine(SnapshotFormat.El(3, "textbox", "E-mail", value = "a@b.com", required = true)); assertEquals("[3] textbox \"E-mail\" = \"a@b.com\" [obrigatório]", l) }
    @Test fun linhaCheckbox() = assertTrue(SnapshotFormat.elementLine(SnapshotFormat.El(1, "checkbox", "Aceito", checked = true)).endsWith("[marcado]"))
    @Test fun linhaPlaceholderComoRotulo() = assertTrue(SnapshotFormat.elementLine(SnapshotFormat.El(1, "textbox", "", placeholder = "Buscar")).contains("\"Buscar\""))
    @Test fun linkSemUtm() = assertEquals("https://x.com/a", SnapshotFormat.shortUrl("https://x.com/a?utm_source=g"))
    @Test fun linkJavascriptOculto() = assertFalse(SnapshotFormat.elementLine(SnapshotFormat.El(1, "link", "x", href = "javascript:void(0)")).contains("->"))
    @Test fun dialogoPrimeiro() {
        val els = listOf(SnapshotFormat.El(0, "link", "Home", region = "nav"), SnapshotFormat.El(5, "button", "Aceitar", region = "dialog"))
        val (t, _) = SnapshotFormat.elementsBlock(els, 1000, true); assertTrue(t.indexOf("Diálogo") < t.indexOf("Navegação"))
    }
    @Test fun visivelPrioritarioNoOrcamento() {
        val els = (0 until 50).map { SnapshotFormat.El(it, "link", "Rodapé link $it", region = "footer") } + SnapshotFormat.El(99, "button", "Comprar", inView = true)
        val (t, hidden) = SnapshotFormat.elementsBlock(els, 200, false); assertTrue(t.contains("[99]")); assertTrue(hidden > 0)
    }
    @Test fun linksDuplicadosColapsados() {
        val els = listOf(SnapshotFormat.El(0, "link", "Ver", href = "https://a.com/x"), SnapshotFormat.El(1, "link", "Ver", href = "https://a.com/x"))
        assertEquals(1, Regex("\\[\\d+]").findAll(SnapshotFormat.elementsBlock(els, 1000, false).first).count())
    }
    @Test fun indiceOrdenadoDentroDaRegiao() {
        val els = listOf(SnapshotFormat.El(7, "button", "B", inView = true), SnapshotFormat.El(2, "button", "A"))
        val t = SnapshotFormat.elementsBlock(els, 1000, false).first; assertTrue(t.indexOf("[2]") < t.indexOf("[7]"))
    }
    @Test fun outlineIndenta() = assertEquals("- A\n  - B\n", SnapshotFormat.outline(listOf(1 to "A", 2 to "B", 2 to "B"), 500))
    @Test fun tipoProduto() = assertEquals("produto", SnapshotFormat.pageKind(SnapshotFormat.parse(raw(text = "Adicionar ao carrinho"))!!))
    @Test fun tipoLogin() = assertEquals("login", SnapshotFormat.pageKind(SnapshotFormat.parse(raw("""[{"i":0,"role":"textbox","label":"Senha"}]"""))!!))
    @Test fun tipoBusca() = assertEquals("resultados de busca", SnapshotFormat.pageKind(SnapshotFormat.Snap("https://g.com/search?q=a", "", "", "", emptyList(), emptyList())))
    @Test fun bodyRespeitaOrcamento() {
        val els = (0 until 300).joinToString(",", "[", "]") { """{"i":$it,"role":"link","label":"Link número $it","href":"https://x.com/$it"}""" }
        val b = SnapshotFormat.body(SnapshotFormat.parse(raw(els, main = "texto ".repeat(5000)))!!, 4000); assertTrue(b.length < 5200)
    }
    @Test fun bodyTemResumoEDialogo() {
        val b = SnapshotFormat.body(SnapshotFormat.parse(raw(extra = ",\"dialog\":\"Cookies\""))!!, 3000)
        assertTrue(b.startsWith("--- Resumo ---")); assertTrue(b.contains("⚠ Diálogo"))
    }
    @Test fun bodyMainCurtoUsaPaginaToda() { val b = SnapshotFormat.body(SnapshotFormat.parse(raw(main = "pouco", text = "conteudo grande ".repeat(100)))!!, 6000); assertTrue(b.contains("conteudo grande")) }
    @Test fun bodySemTexto() = assertTrue(SnapshotFormat.body(SnapshotFormat.parse(raw())!!, 3000).contains("(sem texto visível)"))
    @Test fun jsTemMarcacoes() { listOf("data-cue", "inView", "region", "mainText", "tiles", "dialog").forEach { assertTrue(it, SnapshotFormat.SNAPSHOT_JS.contains(it)) } }

    @Test fun bloqueiaRastreador() = assertTrue(LoadBoost.shouldBlock("https://www.google-analytics.com/collect?v=1", false))
    @Test fun naoBloqueiaPrincipal() = assertFalse(LoadBoost.shouldBlock("https://doubleclick.net/x", true))
    @Test fun naoBloqueiaSite() = assertFalse(LoadBoost.shouldBlock("https://loja.com/app.js", false))
    @Test fun naoBloqueiaData() = assertFalse(LoadBoost.shouldBlock("data:image/png;base64,xx", false))
    @Test fun buscaCortaImagem() = assertTrue(LoadBoost.shouldBlockForSearch("https://x.com/a.webp?x=1", false))
    @Test fun buscaMantemHtml() = assertFalse(LoadBoost.shouldBlockForSearch("https://www.google.com/search?q=a", true))
    @Test fun startJsIdempotente() = assertTrue(LoadBoost.START_JS.contains("__cueBoost") && !LoadBoost.START_JS.contains("animation-duration"))

    @Test fun pollParaCom6() = assertTrue(SearchPoll.done(6, false, 0))
    @Test fun pollEsperaCarregar() = assertFalse(SearchPoll.done(2, false, 3))
    @Test fun pollDepoisDeCarregar() = assertTrue(SearchPoll.done(1, true, 2))
    @Test fun pollDesisteVazio() { assertFalse(SearchPoll.done(0, true, 4)); assertTrue(SearchPoll.done(0, true, 5)) }

    private fun h(u: String, t: String = "t") = SearchHit(t, u, "")
    @Test fun mergeLimitaDominio() {
        val l = listOf(h("https://a.com/1"), h("https://a.com/2"), h("https://a.com/3"), h("https://b.com/1"))
        assertEquals(listOf("https://a.com/1", "https://a.com/2", "https://b.com/1"), SearchApi.merge(listOf(l), 3).map { it.url })
    }
    @Test fun mergeCompletaSiteUnico() = assertEquals(4, SearchApi.merge(listOf((1..4).map { h("https://a.com/$it") }), 4).size)
    @Test fun mergeRemoveLixo() {
        val l = listOf(h("https://www.google.com/search?q=x"), h("https://accounts.google.com/x"), h("https://ok.com", ""), h("https://ok.com/a"))
        assertEquals(listOf("https://ok.com/a"), SearchApi.merge(listOf(l), 5).map { it.url })
    }
    @Test fun mergeDedupWww() = assertEquals(1, SearchApi.merge(listOf(listOf(h("https://www.x.com/a")), listOf(h("http://x.com/a/"))), 5).size)
    @Test fun hostNormaliza() = assertEquals("g1.com", SearchApi.host("https://m.g1.com:443/x"))

    @Test fun retryGetRede() = assertTrue(WfRetry.shouldRetry("get", "Falha de rede: timeout", 0))
    @Test fun retryGet503() = assertTrue(WfRetry.shouldRetry("GET", "HTTP 503: x", 1))
    @Test fun semRetryPost() = assertFalse(WfRetry.shouldRetry("POST", "HTTP 503: x", 0))
    @Test fun semRetry404() = assertFalse(WfRetry.shouldRetry("GET", "HTTP 404: x", 0))
    @Test fun retryLimite() = assertFalse(WfRetry.shouldRetry("GET", "HTTP 429: x", 2))
    @Test fun backoff() = assertEquals(listOf(1500L, 4000L), listOf(WfRetry.backoffMs(0), WfRetry.backoffMs(1)))
}
