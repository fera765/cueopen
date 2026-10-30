package dev.cueopen.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenshotPolicyTest {
    @Test
    fun fullScreenScreenshotRequiresAnExplicitCaptureRequest() {
        assertTrue(DeviceScreenshotPolicy.explicitlyRequestsFullScreenCapture("Tire um screenshot da tela atual"))
        assertTrue(DeviceScreenshotPolicy.explicitlyRequestsFullScreenCapture("Capture a tela"))
        assertTrue(DeviceScreenshotPolicy.explicitlyRequestsFullScreenCapture("Take a screenshot of the screen"))
        assertFalse(DeviceScreenshotPolicy.explicitlyRequestsFullScreenCapture("Abra o Instagram e leia o post principal"))
    }
}
