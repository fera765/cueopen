package dev.fluwy.app.browser

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

    @Test fun tabStateKeyIsStableUniqueAndPathSafe() {
        val first = BrowserTabPersistenceIdentity.stateKey("tab-a")
        assertEquals(first, BrowserTabPersistenceIdentity.stateKey("tab-a"))
        assertNotEquals(first, BrowserTabPersistenceIdentity.stateKey("../tab-a"))
        assertTrue(first.matches(Regex("[a-f0-9]{32}")))
    }
}
