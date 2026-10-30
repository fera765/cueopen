package dev.cueopen.app.device

import android.Manifest
import android.app.role.RoleManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessTest {
    private class FakeState(
        val permissions: MutableSet<String> = mutableSetOf(),
        val appOps: MutableSet<String> = mutableSetOf(),
        val roles: MutableSet<String> = mutableSetOf(),
        val special: MutableSet<String> = mutableSetOf(),
    ) : AndroidCapabilityState {
        override fun permissionGranted(permission: String) = permission in permissions
        override fun appOpAllowed(operation: String) = operation in appOps
        override fun roleHeld(role: String) = role in roles
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
    fun smsRequiresRolePermissionAndAppOpAndRevocationIsObserved() {
        val state = FakeState(
            permissions = mutableSetOf(Manifest.permission.READ_SMS),
            appOps = mutableSetOf("android:read_sms"),
            roles = mutableSetOf(RoleManager.ROLE_SMS),
        )
        assertTrue(DeviceCaps.granted(state, DeviceCaps.SMS))

        state.roles.clear()
        assertFalse(DeviceCaps.granted(state, DeviceCaps.SMS))
        state.roles += RoleManager.ROLE_SMS
        state.permissions.clear()
        assertFalse(DeviceCaps.granted(state, DeviceCaps.SMS))
        state.permissions += Manifest.permission.READ_SMS
        state.appOps.clear()
        assertFalse(DeviceCaps.granted(state, DeviceCaps.SMS))
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
    fun phoneCallsAndBackgroundRemainAgentOnlyConsent() {
        val state = FakeState()
        assertTrue(DeviceCaps.granted(state, DeviceCaps.PHONE_CALLS))
        assertTrue(DeviceCaps.granted(state, DeviceCaps.BACKGROUND))
    }
}
