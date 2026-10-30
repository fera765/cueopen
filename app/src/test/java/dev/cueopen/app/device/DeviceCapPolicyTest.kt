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
        assertEquals(setOf("device_dial", "device_sms_compose"), tools)
        assertFalse("device_sms_read" in tools)
        assertTrue(DeviceCapPolicy.requiredCapability("device_sms_read") == DeviceCapPolicy.SMS)
    }

    @Test fun visibleSystemHandoffsNeedAgentOptInButNotReadPermissions() {
        assertEquals(setOf("device_bluetooth_pair"), DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.BLUETOOTH), emptySet()))
        assertEquals(setOf("device_calendar_create"), DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.CALENDAR), emptySet()))
        assertEquals(setOf("device_sms_compose"), DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.SMS), emptySet()))
    }

    @Test fun sensitiveReadsStillRequireBothAgentOptInAndAndroidGrant() {
        val tools = DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.SMS, DeviceCapPolicy.BLUETOOTH),
            setOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.SMS, DeviceCapPolicy.BLUETOOTH),
        )
        assertTrue("device_contacts" in tools)
        assertTrue("device_sms_read" in tools)
        assertTrue("device_bluetooth" in tools)
        assertFalse("device_contacts" in DeviceCapPolicy.availableTools(emptySet(), setOf(DeviceCapPolicy.CONTACTS)))
        assertFalse("device_contacts" in DeviceCapPolicy.availableTools(setOf(DeviceCapPolicy.CONTACTS), emptySet()))
    }

    @Test fun everyMappedToolHasAnExplicitCapabilityAndBluetoothGateUsesAndroidApi() {
        assertTrue(DeviceCapPolicy.allTools.all { DeviceCapPolicy.requiredCapability(it) != null })
        assertFalse(DeviceCapPolicy.bluetoothConnectRuntimePermissionRequired(30))
        assertTrue(DeviceCapPolicy.bluetoothConnectRuntimePermissionRequired(31))
    }

    @Test fun agentGuidanceNamesContactsAndForbidsBulkDisclosureAndSmsSideChannels() {
        val guidance = DeviceCapPolicy.agentGuidance(setOf("device_contacts", "device_notifications"))
            .joinToString(" ")
        assertTrue(guidance.contains("device_contacts"))
        assertTrue(guidance.contains("agenda inteira"))
        assertTrue(guidance.contains("device_notifications"))
        assertTrue(guidance.contains("contornar"))
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
