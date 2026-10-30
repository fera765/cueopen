package dev.cueopen.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapPolicyTest {
    @Test fun toolsRequireBothAgentOptInAndLiveSystemGrant() {
        val tools = DeviceCapPolicy.availableTools(
            agentCapabilities = setOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.CALENDAR, DeviceCapPolicy.CALL_LOG),
            systemGrants = setOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.CALENDAR),
        )
        assertEquals(setOf("device_contacts", "device_calendar_read", "device_calendar_create"), tools)
        assertFalse("device_call_log" in tools)
    }

    @Test fun revokedCapabilityIsRemovedEvenWhenAgentOptedIn() {
        val tools = DeviceCapPolicy.availableTools(
            agentCapabilities = setOf(DeviceCapPolicy.SMS, DeviceCapPolicy.PHONE_CALLS),
            systemGrants = setOf(DeviceCapPolicy.PHONE_CALLS),
        )
        assertEquals(setOf("device_dial"), tools)
        assertTrue(DeviceCapPolicy.requiredCapability("device_sms_read") == DeviceCapPolicy.SMS)
    }

    @Test fun callsAndSmsOnlyOpenUserConfirmedSystemFlows() {
        assertTrue(DeviceCapPolicy.requiresUserHandoff("device_dial"))
        assertTrue(DeviceCapPolicy.requiresUserHandoff("device_sms_compose"))
        assertTrue(DeviceCapPolicy.requiresUserHandoff("device_calendar_create"))
        assertFalse(DeviceCapPolicy.requiresUserHandoff("device_sms_read"))
    }

    @Test fun legacyBackgroundConsentMigratesOnlyOnce() {
        val original = listOf(DeviceCapPolicy.CONTACTS)
        assertEquals(listOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.BACKGROUND),
            DeviceCapPolicy.migrateLegacyBackgroundConsent(original, autonomous = true, migrationCompleted = false))
        assertEquals(original,
            DeviceCapPolicy.migrateLegacyBackgroundConsent(original, autonomous = true, migrationCompleted = true))
        assertEquals(original,
            DeviceCapPolicy.migrateLegacyBackgroundConsent(original, autonomous = false, migrationCompleted = false))
    }
}
