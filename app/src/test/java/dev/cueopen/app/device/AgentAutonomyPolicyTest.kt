package dev.cueopen.app.device

import dev.cueopen.app.data.AgentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAutonomyPolicyTest {
    @Test
    fun enablingAutonomyAlsoGrantsBackground() {
        val p = AgentAutonomyPolicy.setAutonomous(AgentProfile(name = "a"), true)
        assertTrue(p.autonomous)
        assertTrue(DeviceCapPolicy.BACKGROUND in p.deviceCaps)
    }

    @Test
    fun disablingAutonomyKeepsAndroidAccess() {
        val on = AgentAutonomyPolicy.setAutonomous(AgentProfile(name = "a", deviceCaps = listOf("contacts")), true)
        val off = AgentAutonomyPolicy.setAutonomous(on, false)
        assertFalse(off.autonomous)
        assertEquals(on.deviceCaps, off.deviceCaps)
    }

    @Test
    fun savingStaleEditorCopyKeepsRuntimeChanges() {
        val original = AgentProfile(id = "x", name = "a")
        // enquanto a tela estava aberta, o agente se agendou pelo chat
        val latest = original.copy(autonomous = true, homeSessionId = "s1", heartbeatMinutes = 20, lastBeatAt = 99L,
            deviceCaps = listOf(DeviceCapPolicy.BACKGROUND), memory = listOf("fato"))
        val edited = original.copy(name = "novo nome")
        val saved = AgentAutonomyPolicy.mergeForSave(edited, original, latest)
        assertEquals("novo nome", saved.name)
        assertTrue(saved.autonomous)
        assertEquals("s1", saved.homeSessionId)
        assertEquals(20, saved.heartbeatMinutes)
        assertEquals(listOf(DeviceCapPolicy.BACKGROUND), saved.deviceCaps)
        assertEquals(listOf("fato"), saved.memory)
    }

    @Test
    fun explicitUserChangesWinOverLatest() {
        val original = AgentProfile(id = "x", name = "a", autonomous = true, deviceCaps = listOf(DeviceCapPolicy.BACKGROUND))
        val latest = original.copy(homeSessionId = "s1")
        val edited = AgentAutonomyPolicy.setAutonomous(original, false).copy(deviceCaps = emptyList())
        val saved = AgentAutonomyPolicy.mergeForSave(edited, original, latest)
        assertFalse(saved.autonomous)
        assertTrue(saved.deviceCaps.isEmpty())
        assertEquals("s1", saved.homeSessionId)
    }
}

class AgentAutonomyFatigueTest {
    @Test
    fun tenConsecutiveFailuresDisableAutonomy() {
        var p = AgentProfile(name = "a", autonomous = true)
        repeat(9) { p = AgentAutonomyPolicy.recordCycle(p, failed = true).first }
        assertEquals(9, p.consecutiveFailures); assertTrue(p.autonomous)
        val (off, tripped) = AgentAutonomyPolicy.recordCycle(p, failed = true)
        assertTrue(tripped); assertFalse(off.autonomous)
    }

    @Test
    fun successResetsCounter() {
        var p = AgentProfile(name = "a", autonomous = true)
        repeat(9) { p = AgentAutonomyPolicy.recordCycle(p, failed = true).first }
        p = AgentAutonomyPolicy.recordCycle(p, failed = false).first
        assertEquals(0, p.consecutiveFailures)
        assertFalse(AgentAutonomyPolicy.recordCycle(p, failed = true).second)
    }
}
