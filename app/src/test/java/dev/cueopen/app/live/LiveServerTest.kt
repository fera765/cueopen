package dev.cueopen.app.live

import dev.cueopen.app.agent.ToolContextPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveServerTest {
    @Test fun sameProjectNameAlwaysMapsToSameSlugAndPort() {
        assertEquals("loja-da-maria", LiveServer.slugOf("Loja da Maria"))
        assertEquals("loja-da-maria", LiveServer.slugOf("  loja DA maria!! "))
        assertEquals(LiveServer.staticPortOf("loja-da-maria"), LiveServer.staticPortOf("loja-da-maria"))
        assertTrue(LiveServer.staticPortOf("qualquer") in 8800..8999)
    }

    private fun groups(q: String) = ToolContextPlanner.plan(ToolContextPlanner.Input("live-" + System.nanoTime(), q, emptySet(), false)).groups

    @Test fun liveServerToolOnlyWhenUserAsksToPublish() {
        assertTrue("live" in groups("pode publicar o site agora?"))
        assertTrue("live" in groups("coloque no ar e me mande o link"))
        assertFalse("live" in groups("crie um site de uma página para minha padaria"))
    }
}
