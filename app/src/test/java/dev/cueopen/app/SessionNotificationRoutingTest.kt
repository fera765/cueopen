package dev.cueopen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationRoutingTest {
    @Test
    fun `IDs estaveis permanecem iguais entre execucoes e nao usam hashCode`() {
        val a = SessionNotificationRouting.stableId("session-alpha")
        assertEquals(a, SessionNotificationRouting.stableId("session-alpha"))
        assertNotEquals(a, SessionNotificationRouting.stableId("session-beta"))
        assertTrue(a > 0)
    }

    @Test
    fun `entrada vazia usa fallback estavel`() {
        assertEquals(
            SessionNotificationRouting.FALLBACK_NOTIFICATION_ID,
            SessionNotificationRouting.stableId(" "),
        )
        assertEquals(
            SessionNotificationRouting.FALLBACK_NOTIFICATION_ID,
            SessionNotificationRouting.stableId(""),
        )
    }

    @Test
    fun `extra preserva prioridade sobre deep link e IDs invalidos sao ignorados`() {
        assertEquals("session-1", SessionNotificationRouting.candidateId("session-1", "session-2"))
        assertEquals("session-2", SessionNotificationRouting.candidateId(null, "session-2"))
        assertNull(SessionNotificationRouting.candidateId("  ", null))
        assertNull(SessionNotificationRouting.candidateId(null, ""))
    }

    @Test
    fun `cold start abre sessao e chat ja aberto nao duplica destino`() {
        assertTrue(SessionNotificationRouting.shouldOpen(null, "session-1"))
        assertTrue(SessionNotificationRouting.shouldOpen("session-1", "session-2"))
        assertFalse(SessionNotificationRouting.shouldOpen("session-1", "session-1"))
        assertFalse(SessionNotificationRouting.shouldOpen(null, null))
    }

    @Test
    fun `deep link codifica ID estavel`() {
        assertEquals("cueopen://chat/session%2F1", SessionNotificationRouting.deepLink("session/1"))
    }

    @Test
    fun `deep link codifica espaco e separadores como um unico segmento`() {
        assertEquals("cueopen://chat/session%20one%2Ftwo", SessionNotificationRouting.deepLink("session one/two"))
    }
}
