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

    @Test fun retiredSmsCapabilityIsRemovedFromExistingProfiles() {
        val oldProfile = listOf("sms", DeviceCapPolicy.PHONE_CALLS)
        assertEquals(listOf(DeviceCapPolicy.PHONE_CALLS), DeviceCapPolicy.removeRetiredCapabilities(oldProfile))
        assertEquals(setOf("device_dial"), DeviceCapPolicy.availableTools(oldProfile, emptySet()))
        assertTrue(DeviceCapPolicy.allTools.none { it.startsWith("device_sms_") })
    }

    @Test fun visibleSystemHandoffsNeedAgentOptInButNotReadPermissions() {
        assertEquals(setOf("device_bluetooth_pair"), DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.BLUETOOTH), emptySet()))
        assertEquals(setOf("device_calendar_create"), DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.CALENDAR), emptySet()))
    }

    @Test fun accessibilityToolsRequireThisAgentOptInAndTheGlobalAndroidServiceGrant() {
        val allAccessibilityTools = setOf(
            "device_accessibility_open_app", "device_accessibility_read_screen",
            "device_accessibility_click", "device_accessibility_type",
            "device_accessibility_scroll", "device_accessibility_back",
            "device_accessibility_inspect_image", "device_accessibility_tap", "device_open_link",
        )
        assertEquals(emptySet<String>(), DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.ACCESSIBILITY), emptySet()))
        assertEquals(emptySet<String>(), DeviceCapPolicy.availableTools(
            emptySet(), setOf(DeviceCapPolicy.ACCESSIBILITY)))
        assertEquals(allAccessibilityTools, DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.ACCESSIBILITY), setOf(DeviceCapPolicy.ACCESSIBILITY)))
        assertTrue(allAccessibilityTools.all { DeviceCapPolicy.requiredCapability(it) == DeviceCapPolicy.ACCESSIBILITY })
        assertTrue(allAccessibilityTools.all(DeviceCapPolicy::requiresSystemGrant))
    }

    @Test fun sensitiveReadsStillRequireBothAgentOptInAndAndroidGrant() {
        val tools = DeviceCapPolicy.availableTools(
            setOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.BLUETOOTH),
            setOf(DeviceCapPolicy.CONTACTS, DeviceCapPolicy.BLUETOOTH),
        )
        assertTrue("device_contacts" in tools)
        assertTrue("device_bluetooth" in tools)
        assertFalse("device_contacts" in DeviceCapPolicy.availableTools(emptySet(), setOf(DeviceCapPolicy.CONTACTS)))
        assertFalse("device_contacts" in DeviceCapPolicy.availableTools(setOf(DeviceCapPolicy.CONTACTS), emptySet()))
    }

    @Test fun everyMappedToolHasAnExplicitCapabilityAndBluetoothGateUsesAndroidApi() {
        assertTrue(DeviceCapPolicy.allTools.all { DeviceCapPolicy.requiredCapability(it) != null })
        assertFalse(DeviceCapPolicy.bluetoothConnectRuntimePermissionRequired(30))
        assertTrue(DeviceCapPolicy.bluetoothConnectRuntimePermissionRequired(31))
    }

    @Test fun agentGuidanceNamesContactsAndForbidsBulkDisclosure() {
        val guidance = DeviceCapPolicy.agentGuidance(setOf("device_contacts", "device_notifications"))
            .joinToString(" ")
        assertTrue(guidance.contains("device_contacts"))
        assertTrue(guidance.contains("agenda inteira"))
        assertTrue(guidance.contains("device_notifications"))
        assertTrue(guidance.contains("contornar"))
    }

    @Test fun callsOnlyOpenUserConfirmedSystemFlows() {
        assertTrue(DeviceCapPolicy.requiresUserHandoff("device_dial"))
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
