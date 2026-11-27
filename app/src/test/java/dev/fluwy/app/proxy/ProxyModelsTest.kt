package dev.fluwy.app.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyModelsTest {
    private val profile = ProxyProfile(name = "Office", scheme = ProxyScheme.HTTP, host = "proxy.example", port = 3128)

    @Test fun defaultsToDirectEnvironment() {
        assertTrue(ProxyEnvironment.variables(null).isEmpty())
    }

    @Test fun environmentIncludesLocalBypassAndNoCredentials() {
        val env = ProxyEnvironment.variables(profile)
        assertEquals("http://proxy.example:3128", env["HTTPS_PROXY"])
        assertEquals("localhost,127.0.0.1,::1", env["NO_PROXY"])
        assertFalse(env.values.any { it.contains("@") })
    }

    @Test fun terminalAndBrowserRejectCredentialedProfiles() {
        val credentialed = profile.copy(hasCredentials = true)
        assertFalse(ProxyValidator.validate(credentialed, ProxyScope.TERMINAL).valid)
        assertFalse(ProxyValidator.validate(credentialed, ProxyScope.BROWSER).valid)
        assertTrue(ProxyValidator.validate(credentialed, ProxyScope.AGENT_API).valid)
        assertFalse(ProxyValidator.validate(credentialed.copy(scheme = ProxyScheme.SOCKS), ProxyScope.AGENT_API).valid)
    }

    @Test fun socksTerminalEnvironmentUsesSocks5RemoteDnsUri() {
        assertEquals("socks5h://proxy.example:1080", ProxyEnvironment.variables(profile.copy(scheme = ProxyScheme.SOCKS, port = 1080))["ALL_PROXY"])
    }

    @Test fun unsupportedHttpsProxyIsRejectedForOkHttpScopes() {
        assertFalse(ProxyValidator.validate(profile.copy(scheme = ProxyScheme.HTTPS), ProxyScope.AGENT_API).valid)
        assertFalse(ProxyValidator.validate(profile.copy(scheme = ProxyScheme.HTTPS), ProxyScope.TERMINAL).valid)
    }

    @Test fun hostValidationRejectsRuleInjectionAndAcceptsIpv6() {
        assertFalse(ProxyValidator.validate(profile.copy(host = "proxy.example;DIRECT"), ProxyScope.BROWSER).valid)
        assertTrue(ProxyValidator.validate(profile.copy(host = "[2001:db8::1]"), ProxyScope.BROWSER).valid)
        assertEquals("http://[2001:db8::1]:3128", ProxyValidator.endpoint(profile.copy(host = "2001:db8::1")))
    }

    @Test fun displaySanitizesProxyCredentials() {
        val value = "https://user:secret@proxy.example:443 password=secret"
        val sanitized = ProxyEnvironment.sanitizeForDisplay(value)
        assertFalse(sanitized.contains("secret"))
        assertFalse(ProxyEnvironment.sanitizeForDisplay("socks5h://user:secret@proxy.example:1080").contains("secret"))
    }

    @Test fun rotationIsOffByDefaultAndNeverRepeatsCurrentProfile() {
        assertFalse(ProxyRotationSettings().enabled)
        val second = profile.copy(id = "proxy-second", name = "Second", host = "proxy2.example")
        assertEquals("proxy-second", ProxyRotationPolicy.nextProfileId(
            profile.id, listOf(profile.id, second.id), listOf(profile, second), ProxyScope.BROWSER, pick = 0,
        ))
        assertNull(ProxyRotationPolicy.nextProfileId(
            profile.id, listOf(profile.id), listOf(profile), ProxyScope.BROWSER, pick = 123,
        ))
    }

    @Test fun rotationExcludesProfilesInvalidForTheSelectedScope() {
        val credentialed = profile.copy(id = "credentialed", hasCredentials = true)
        assertNull(ProxyRotationPolicy.nextProfileId(
            currentId = "direct", candidates = listOf(credentialed.id), profiles = listOf(credentialed),
            scope = ProxyScope.BROWSER, pick = 0,
        ))
        assertEquals("credentialed", ProxyRotationPolicy.nextProfileId(
            currentId = null, candidates = listOf(credentialed.id), profiles = listOf(credentialed),
            scope = ProxyScope.AGENT_API, pick = 0,
        ))
    }
}
