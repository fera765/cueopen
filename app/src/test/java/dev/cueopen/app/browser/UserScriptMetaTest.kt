package dev.cueopen.app.browser

import dev.cueopen.app.data.UserScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptMetaTest {
    private val full = """
        // ==UserScript==
        // @name         Dias ativos do anúncio
        // @namespace    fluwy
        // @version      1.2.0
        // @description  Mostra há quantos dias cada anúncio está ativo
        // @author       Eu
        // @match        https://www.facebook.com/ads/library/*
        // @include      /^https://m\.facebook\.com/ads/.*$/
        // @exclude      https://www.facebook.com/ads/library/report*
        // @require      https://cdn.example.com/lib.js
        // @grant        GM_addStyle
        // @grant        GM_xmlhttpRequest
        // @run-at       document-end
        // ==/UserScript==
        (function(){ GM_addStyle('.x{}'); })();
    """.trimIndent()

    @Test fun `header metadata is extracted`() {
        val (m, err) = UserScriptMeta.parse(full)
        assertNull(err); assertNotNull(m); m!!
        assertEquals("Dias ativos do anúncio", m.name)
        assertEquals("1.2.0", m.version)
        assertEquals("fluwy", m.namespace)
        assertEquals("Mostra há quantos dias cada anúncio está ativo", m.description)
        assertEquals(listOf("https://www.facebook.com/ads/library/*"), m.matches)
        assertEquals(1, m.includes.size); assertEquals(1, m.excludes.size)
        assertEquals(listOf("https://cdn.example.com/lib.js"), m.requires)
        assertEquals(listOf("GM_addStyle", "GM_xmlhttpRequest"), m.grants)
        assertEquals("document-end", m.runAt)
    }

    @Test fun `scripts without a proper header are rejected with a clear error`() {
        assertNotNull(UserScriptMeta.parse("alert(1)").second)
        assertNotNull(UserScriptMeta.parse("// ==UserScript==\n// @name X\n// @match https://a.com/*\nalert(1)").second) // sem fechar
        assertNotNull(UserScriptMeta.parse("// ==UserScript==\n// @match https://a.com/*\n// ==/UserScript==\nalert(1)").second) // sem @name
        assertNotNull(UserScriptMeta.parse("// ==UserScript==\n// @name X\n// ==/UserScript==\nalert(1)").second) // sem @match
        assertNotNull(UserScriptMeta.parse("// ==UserScript==\n// @name X\n// @match https://a.com/*\n// ==/UserScript==\n").second) // sem código
        assertNotNull(UserScriptMeta.parse("// ==UserScript==\n// @name X\n// @match a.com\n// ==/UserScript==\nalert(1)").second) // @match inválido
    }

    @Test fun `match include and exclude follow Tampermonkey rules`() {
        val m = UserScriptMeta.parse(full).first!!
        assertTrue(UserScriptMatcher.matches(m, "https://www.facebook.com/ads/library/?q=x"))
        assertTrue(UserScriptMatcher.matches(m, "https://m.facebook.com/ads/abc"))
        assertFalse(UserScriptMatcher.matches(m, "https://www.facebook.com/ads/library/report?x"))
        assertFalse(UserScriptMatcher.matches(m, "https://www.facebook.com/home"))
        assertFalse(UserScriptMatcher.matches(m, "http://www.facebook.com/ads/library/"))
        assertTrue(UserScriptMatcher.matchPattern("*://*.exemplo.com/*", "https://a.b.exemplo.com/x"))
        assertTrue(UserScriptMatcher.matchPattern("*://*.exemplo.com/*", "http://exemplo.com/"))
        assertFalse(UserScriptMatcher.matchPattern("*://*.exemplo.com/*", "https://exemplo.com.evil.io/"))
        assertTrue(UserScriptMatcher.matchPattern("<all_urls>", "https://qualquer.site/a"))
        assertTrue(UserScriptMatcher.matchPattern("*://*/*", "https://qualquer.site/a"))
        assertTrue(UserScriptMatcher.includePattern("*google.tld/*", "https://www.google.com.br/search"))
        assertFalse(UserScriptMatcher.matches(listOf("*://*/*"), emptyList(), emptyList(), "file:///root/a.html"))
    }

    @Test fun `all sites is allowed when declared`() {
        val m = UserScriptMeta.parse("// ==UserScript==\n// @name Todos\n// @match *://*/*\n// ==/UserScript==\nconsole.log(1)").first!!
        assertTrue(m.allSites)
        val s = BrowserPolicies.fromMeta(m, "x", "", "manual", null)
        assertEquals(listOf("x"), BrowserPolicies.scriptsFor("https://any.com/", null, listOf(s.copy(code = "x"))).map { it.code })
    }

    @Test fun `reinstalling the same name updates instead of duplicating`() {
        val m = UserScriptMeta.parse(full).first!!
        val old = BrowserPolicies.fromMeta(m, "v1", "", "manual", null).copy(enabled = false, runs = 7, agentIds = listOf("A"))
        assertEquals(old, BrowserPolicies.findExisting(m, listOf(old)))
        val upd = BrowserPolicies.fromMeta(m, "v2", "", "arquivo", old)
        assertEquals(old.id, upd.id); assertEquals("v2", upd.code); assertFalse(upd.enabled); assertEquals(7, upd.runs); assertEquals(listOf("A"), upd.agentIds)
    }

    @Test fun `wrapper exposes the GM api and keeps token and code`() {
        val w = BrowserPolicies.wrap(UserScript(id = "k1", name = "n", code = "GM_addStyle('a')", version = "1"), token = "tok-123")
        listOf("function GM_addStyle", "function GM_xmlhttpRequest", "function GM_getValue", "var GM={", "unsafeWindow=window", "\"tok-123\"", "GM_addStyle('a')", "return 'dup'")
            .forEach { assertTrue(it, w.contains(it)) }
    }

    @Test fun `GM requests reach the internet but never the local network`() {
        assertNull(BrowserPolicies.gmRequestDenial("https://api.example.com/x"))
        listOf("http://localhost:8080", "http://127.0.0.1/", "http://192.168.0.10/", "http://10.0.0.2/a", "http://172.20.1.1/",
            "http://169.254.169.254/latest", "http://[::1]/", "file:///etc/passwd", "http://user@127.0.0.1/", "http://printer.local/")
            .forEach { assertNotNull(it, BrowserPolicies.gmRequestDenial(it)) }
    }
}
