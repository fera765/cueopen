package dev.cueopen.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionPersistenceTest {
    @Test fun profileNameIsStableAndUniquePerChat() {
        val first = BrowserProfileIdentity.profileName("chat-a")
        assertEquals(first, BrowserProfileIdentity.profileName("chat-a"))
        assertNotEquals(first, BrowserProfileIdentity.profileName("chat-b"))
        assertTrue(first.startsWith("fluwy_chat_"))
    }
}
