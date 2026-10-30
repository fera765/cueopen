package dev.cueopen.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyPolicyTest {
    @Test fun hidesNotificationsFromTheSystemDefaultSmsApp() {
        assertFalse(NotificationPrivacyPolicy.shouldCapture(
            notificationPackage = "com.android.messaging",
            defaultSmsPackage = "com.android.messaging",
            isMessageCategory = false,
        ))
    }

    @Test fun hidesMessageCategoryWhenDefaultSmsAppCannotBeIdentified() {
        assertFalse(NotificationPrivacyPolicy.shouldCapture(
            notificationPackage = "com.example.messaging",
            defaultSmsPackage = null,
            isMessageCategory = true,
        ))
    }

    @Test fun preservesUnrelatedNotifications() {
        assertTrue(NotificationPrivacyPolicy.shouldCapture(
            notificationPackage = "com.example.mail",
            defaultSmsPackage = "com.android.messaging",
            isMessageCategory = false,
        ))
    }
}
