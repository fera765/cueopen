package dev.fluwy.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySnapshotPolicyTest {
    @Test
    fun snapshotIsValidAtTtlBoundaryButExpiresAfterwardOrIfClockMovesBackward() {
        assertFalse(AccessibilitySnapshotPolicy.isExpired(createdAt = 100L, now = 130L, ttl = 30L))
        assertTrue(AccessibilitySnapshotPolicy.isExpired(createdAt = 100L, now = 131L, ttl = 30L))
        assertTrue(AccessibilitySnapshotPolicy.isExpired(createdAt = 101L, now = 100L, ttl = 30L))
    }

    @Test
    fun displayScreenshotFallbackRequiresAndroidEleven() {
        assertFalse(AccessibilitySnapshotPolicy.supportsDisplayScreenshot(29))
        assertTrue(AccessibilitySnapshotPolicy.supportsDisplayScreenshot(30))
        assertTrue(AccessibilitySnapshotPolicy.supportsDisplayScreenshot(35))
    }

    @Test
    fun benignWindowEventRetainsOnlyTheMatchingForegroundSnapshot() {
        assertTrue(AccessibilitySnapshotPolicy.keepAfterWindowEvent("com.example", 42, "com.example", 42))
        assertFalse(AccessibilitySnapshotPolicy.keepAfterWindowEvent("com.example", 42, "com.example", 43))
        assertFalse(AccessibilitySnapshotPolicy.keepAfterWindowEvent("com.example", 42, "com.other", 42))
        assertTrue(AccessibilitySnapshotPolicy.keepAfterWindowEvent("com.example", -1, "com.example", -1))
    }
}
