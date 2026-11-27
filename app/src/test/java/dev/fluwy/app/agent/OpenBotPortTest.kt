package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserPolicies
import dev.fluwy.app.data.BrowserLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class OpenBotPortTest {
    @Test fun memoryLimitExplainsInsteadOfTruncating() {
        assertNull(ContextManager.memoryTooLong("a".repeat(ContextManager.MEMORY_MAX_CHARS)))
        val err = ContextManager.memoryTooLong("a".repeat(ContextManager.MEMORY_MAX_CHARS + 20))
        assertNotNull(err)
        assertTrue(err!!.contains("520"))
    }

    @Test fun localClockCarriesZoneAndOffset() {
        val sp = TimeZone.getTimeZone("America/Sao_Paulo")
        val t = 1_795_000_000_000L // nov/2026
        assertEquals("UTC-03:00", LocalClock.offset(sp, t))
        val line = LocalClock.line(t, sp)
        assertTrue(line, line.contains("America/Sao_Paulo (UTC-03:00)"))
        assertTrue(line, Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}").containsMatchIn(line))
        assertEquals("UTC+05:30", LocalClock.offset(TimeZone.getTimeZone("Asia/Kolkata"), t))
    }

    private fun login(domain: String) = BrowserLogin(name = domain, url = "https://$domain", domain = domain)

    @Test fun noLoginHintOnlyOnLoginPagesWithoutMatchingLogin() {
        val logins = listOf(login("walmart.com"), login("github.com"))
        val loginPage = "Entrar\ncampo E-mail\ncampo Senha\nbotão Continuar"
        val hint = BrowserPolicies.noLoginHint("accounts.wal-mart-walmart.net", loginPage, logins)
        assertNotNull(hint)
        assertTrue(hint!!, hint.contains("walmart.com"))
        assertNull(BrowserPolicies.noLoginHint("github.com", loginPage, logins))           // há login
        assertNull(BrowserPolicies.noLoginHint("example.org", "Bem-vindo ao blog", logins)) // não é login
        assertNull(BrowserPolicies.noLoginHint("example.org", loginPage, emptyList()))      // nada salvo
    }
}
