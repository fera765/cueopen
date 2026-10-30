package dev.cueopen.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAvatarCatalogTest {
    @Test
    fun catalogHasThirtyDistinctAvatarModels() {
        assertEquals(30, agentFaces.size)
        assertEquals(30, agentFaces.toSet().size)
    }

    @Test
    fun existingStoredFaceIdsRemainAvailable() {
        assertTrue(agentFaces.containsAll(listOf("blob", "hex", "square", "circle", "triangle", "pill", "drop", "cloud")))
    }
}
