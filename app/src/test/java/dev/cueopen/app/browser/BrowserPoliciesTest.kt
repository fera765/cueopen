package dev.cueopen.app.browser

import dev.cueopen.app.data.BrowserLogin
import dev.cueopen.app.data.UserScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPoliciesTest {
    @Test fun `domains normalize and match safely`() {
        assertEquals("facebook.com", BrowserPolicies.normalizeDomain("https://www.Facebook.com/ads/library?x=1"))
        assertEquals("*.exemplo.com", BrowserPolicies.normalizeDomain("*.exemplo.com"))
        assertEquals("", BrowserPolicies.normalizeDomain("not a domain"))
        assertTrue(BrowserPolicies.domainMatches("m.facebook.com", "facebook.com"))
        assertTrue(BrowserPolicies.domainMatches("facebook.com", "facebook.com"))
        assertFalse(BrowserPolicies.domainMatches("evilfacebook.com", "facebook.com"))
        assertFalse(BrowserPolicies.domainMatches("facebook.com.evil.io", "facebook.com"))
        assertTrue(BrowserPolicies.domainMatches("a.exemplo.com", "*.exemplo.com"))
        assertFalse(BrowserPolicies.domainMatches("exemplo.com", "*.exemplo.com"))
    }

    @Test fun `scripts only run on configured domains for allowed agents`() {
        val s = UserScript(id = "s1", name = "ads", code = "1", domains = listOf("facebook.com"), priority = 1)
        val noDomain = UserScript(id = "s2", name = "global", code = "1", domains = emptyList())
        val onlyA = UserScript(id = "s3", name = "a", code = "1", domains = listOf("facebook.com"), agentIds = listOf("A"), priority = 5)
        val off = s.copy(id = "s4", enabled = false)
        val path = s.copy(id = "s5", pathContains = "/ads/")
        val list = listOf(s, noDomain, onlyA, off, path)
        assertEquals(listOf("s3", "s1", "s5"), BrowserPolicies.scriptsFor("https://www.facebook.com/ads/library", "A", list).map { it.id })
        assertEquals(listOf("s1"), BrowserPolicies.scriptsFor("https://facebook.com/home", "B", list).map { it.id })
        assertTrue(BrowserPolicies.scriptsFor("https://google.com", "A", list).isEmpty())
        assertTrue(BrowserPolicies.scriptsFor("file:///root/x.html", "A", list).isEmpty())
    }

    @Test fun `script wrapper runs once per document and reports errors`() {
        val w = BrowserPolicies.wrap(UserScript(id = "ab-12", name = "x", code = "throw new Error('boom')", domains = listOf("x.com")))
        assertTrue(w.contains("g['ab12']") && w.contains("return 'dup'") && w.contains("catch(e)"))
        assertEquals("err:boom", BrowserPolicies.parseResult("\"err:boom\""))
    }

    @Test fun `failing scripts auto disable and history is bounded`() {
        var s = UserScript(name = "x", code = "1", domains = listOf("x.com"))
        repeat(BrowserPolicies.AUTO_DISABLE_AFTER_ERRORS) { s = BrowserPolicies.record(s, "x.com", "err:falhou", 1, "10:00") }
        assertFalse(s.enabled); assertEquals(3, s.errors); assertEquals("falhou", s.lastError)
        var ok = UserScript(name = "y", code = "1", domains = listOf("x.com"))
        repeat(20) { ok = BrowserPolicies.record(ok, "x.com", "ok", 1, "10:00") }
        assertTrue(ok.enabled); assertEquals(20, ok.runs); assertEquals(BrowserPolicies.HISTORY_SIZE, ok.history.size)
        assertEquals(ok, BrowserPolicies.record(ok, "x.com", "dup", 1, "10:00"))
    }

    @Test fun `script validation requires explicit domains`() {
        assertNotNull(BrowserPolicies.validateScript("x", "1", emptyList()))
        assertNotNull(BrowserPolicies.validateScript("x", "", listOf("a.com")))
        assertNotNull(BrowserPolicies.validateScript("x", "1", listOf("nada")))
        assertNotNull(BrowserPolicies.validateScript("x", "a".repeat(BrowserPolicies.MAX_SCRIPT_CHARS + 1), listOf("a.com")))
        assertNull(BrowserPolicies.validateScript("x", "1", listOf("a.com", "*.b.com")))
    }

    @Test fun `saved logins respect agent permissions and revocation`() {
        val open = BrowserLogin(name = "fb", url = "https://facebook.com", domain = "facebook.com")
        val restricted = BrowserLogin(name = "ml", url = "https://mercadolivre.com.br", domain = "mercadolivre.com.br", allowedAgentIds = listOf("A"))
        val logins = listOf(open, restricted)
        assertEquals(open, BrowserPolicies.loginFor("business.facebook.com", logins))
        assertNull(BrowserPolicies.loginFor("google.com", logins))
        assertNull(BrowserPolicies.loginDenial(open, "Z"))
        assertNull(BrowserPolicies.loginDenial(restricted, "A"))
        assertNotNull(BrowserPolicies.loginDenial(restricted, "B"))
        assertNotNull(BrowserPolicies.loginDenial(restricted, null))
        assertNotNull(BrowserPolicies.loginDenial(open.copy(status = "revoked"), "A"))
    }
}
