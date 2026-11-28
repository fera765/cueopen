package dev.fluwy.app.device

import dev.fluwy.app.data.AgentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAutonomyPolicyTest {
    private fun profile(
        browser: Boolean = true,
        browserAutonomy: Boolean = false,
        ownsBackground: Boolean = false,
        caps: List<String> = emptyList(),
    ) = AgentProfile(
        name = "Agente de teste",
        useBrowser = browser,
        browserAutonomy = browserAutonomy,
        browserAutonomyGrantedBackground = ownsBackground,
        deviceCaps = caps,
    )

    @Test fun browserAutonomyAddsOnlyThePerAgentBackgroundConsent() {
        val enabled = BrowserAutonomyPolicy.setEnabled(profile(), true)
        assertTrue(enabled.browserAutonomy)
        assertTrue(enabled.browserAutonomyGrantedBackground)
        assertEquals(listOf(DeviceCapPolicy.BACKGROUND), enabled.deviceCaps)
        assertTrue(enabled.useBrowser)
    }

    @Test fun turningBrowserAutonomyOffRemovesOnlyTheGrantItAdded() {
        val enabled = BrowserAutonomyPolicy.setEnabled(profile(), true)
        val disabled = BrowserAutonomyPolicy.setEnabled(enabled, false)
        assertFalse(disabled.browserAutonomy)
        assertFalse(disabled.browserAutonomyGrantedBackground)
        assertTrue(disabled.deviceCaps.isEmpty())
    }

    @Test fun priorBackgroundConsentIsPreservedWhenBrowserAutonomyTurnsOff() {
        val enabled = BrowserAutonomyPolicy.setEnabled(profile(caps = listOf(DeviceCapPolicy.BACKGROUND)), true)
        assertFalse(enabled.browserAutonomyGrantedBackground)
        val disabled = BrowserAutonomyPolicy.setEnabled(enabled, false)
        assertEquals(listOf(DeviceCapPolicy.BACKGROUND), disabled.deviceCaps)
    }

    @Test fun normalBrowserAccessDoesNotEnableAutonomyOrBackground() {
        val enabled = BrowserAutonomyPolicy.setBrowserEnabled(profile(), true)
        assertTrue(enabled.useBrowser)
        assertFalse(enabled.browserAutonomy)
        assertTrue(enabled.deviceCaps.isEmpty())
    }

    @Test fun disablingNormalBrowserAlsoClearsItsOwnedAutonomyGrant() {
        val autonomous = BrowserAutonomyPolicy.setEnabled(profile(), true)
        val disabled = BrowserAutonomyPolicy.setBrowserEnabled(autonomous, false)
        assertFalse(disabled.useBrowser)
        assertFalse(disabled.browserAutonomy)
        assertFalse(disabled.browserAutonomyGrantedBackground)
        assertTrue(disabled.deviceCaps.isEmpty())
    }

    @Test fun explicitBackgroundChangeTakesPrecedenceAndDoesNotLeaveOrphan() {
        val autonomous = BrowserAutonomyPolicy.setEnabled(profile(), true)
        val explicitlyKept = BrowserAutonomyPolicy.setBackgroundEnabled(autonomous, true)
        val autonomyOff = BrowserAutonomyPolicy.setEnabled(explicitlyKept, false)
        assertEquals(listOf(DeviceCapPolicy.BACKGROUND), autonomyOff.deviceCaps)

        val revoked = BrowserAutonomyPolicy.setBackgroundEnabled(autonomous, false)
        assertFalse(revoked.browserAutonomy)
        assertTrue(revoked.deviceCaps.isEmpty())
    }
}
