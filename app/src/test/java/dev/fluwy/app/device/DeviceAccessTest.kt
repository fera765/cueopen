package dev.fluwy.app.device

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessTest {
    private class FakeState(
        val permissions: MutableSet<String> = mutableSetOf(),
        val appOps: MutableSet<String> = mutableSetOf(),
        val special: MutableSet<String> = mutableSetOf(),
    ) : AndroidCapabilityState {
        override fun permissionGranted(permission: String) = permission in permissions
        override fun appOpAllowed(operation: String) = operation in appOps
        override fun specialAccessGranted(capability: String) = capability in special
    }

    @Test
    fun contactsRequiresRuntimePermissionAndAllowedAppOp() {
        val state = FakeState(
            permissions = mutableSetOf(Manifest.permission.READ_CONTACTS),
            appOps = mutableSetOf("android:read_contacts"),
        )
        assertTrue(DeviceCaps.granted(state, DeviceCaps.CONTACTS))

        state.appOps.clear()
        assertFalse(DeviceCaps.granted(state, DeviceCaps.CONTACTS))
        state.permissions.clear()
        state.appOps += "android:read_contacts"
        assertFalse(DeviceCaps.granted(state, DeviceCaps.CONTACTS))
    }

    @Test
    fun retiredSmsCapabilityIsNotExposedOrGranted() {
        assertFalse(DeviceCaps.all.any { it.id == "sms" })
        assertFalse(DeviceCaps.granted(FakeState(), "sms"))
    }

    @Test
    fun overlayAndScreenUseSpecialAccessStateWithoutChangingAgentConsent() {
        val state = FakeState()
        assertFalse(DeviceCaps.granted(state, DeviceCaps.OVERLAY))
        assertFalse(DeviceCaps.granted(state, DeviceCaps.SCREEN))

        state.special += DeviceCaps.OVERLAY
        assertTrue(DeviceCaps.granted(state, DeviceCaps.OVERLAY))
        state.special += DeviceCaps.SCREEN
        assertTrue(DeviceCaps.granted(state, DeviceCaps.SCREEN))
    }

    @Test
    fun accessibilityDependsOnTheActualAndroidSpecialAccess() {
        val state = FakeState()
        assertFalse(DeviceCaps.granted(state, DeviceCaps.ACCESSIBILITY))
        state.special += DeviceCaps.ACCESSIBILITY
        assertTrue(DeviceCaps.granted(state, DeviceCaps.ACCESSIBILITY))
    }

    @Test
    fun phoneCallsAndBackgroundRemainAgentOnlyConsent() {
        val state = FakeState()
        assertTrue(DeviceCaps.granted(state, DeviceCaps.PHONE_CALLS))
        assertTrue(DeviceCaps.granted(state, DeviceCaps.BACKGROUND))
    }
}
