package dev.fluwy.app.ui

import dev.fluwy.app.ui.mascot.Mascots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAvatarCatalogTest {
    @Test
    fun catalogHasTwelveDistinctBuiltinMascots() {
        assertEquals(12, agentFaces.size)
        assertEquals(12, agentFaces.toSet().size)
    }

    @Test
    fun legacyStoredFaceIdsResolveToABuiltinMascot() {
        val legacy = listOf("wink", "smile", "side", "dot", "blob", "hex", "square", "circle", "triangle", "pill", "drop", "cloud",
            "star", "diamond", "heart", "flower", "moon", "shield", "gear", "sun", "cat", "robot", "ghost", "leaf",
            "rocket", "butterfly", "crown", "comet", "spiral", "turtle", "octagon", "spark", "bear", "owl", "qualquer-coisa")
        legacy.forEach { assertTrue(it, Mascots.resolveBuiltin(it) in Mascots.builtinIds) }
        assertEquals("jelly", Mascots.resolveBuiltin("wink"))
    }

    @Test
    fun customFacesAreRecognized() {
        assertTrue(Mascots.isCustom("custom:abc"))
        assertEquals("abc", Mascots.customId("custom:abc"))
    }
}
