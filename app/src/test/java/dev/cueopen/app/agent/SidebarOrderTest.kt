package dev.cueopen.app.agent

import dev.cueopen.app.ui.SidebarOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarOrderTest {
    @Test fun dragCarrosselToRadarPosition() {
        val ids = listOf("radar", "gancho", "carrossel", "legenda", "editor")
        assertEquals(listOf("carrossel", "radar", "gancho", "legenda", "editor"), SidebarOrder.move(ids, 2, 0))
        assertEquals(listOf("gancho", "carrossel", "radar", "legenda", "editor"), SidebarOrder.move(ids, 0, 2))
    }

    @Test fun savedOrderSurvivesNewAndRemovedAgents() {
        assertEquals(listOf("c", "a", "d"), SidebarOrder.apply(listOf("a", "c", "d"), listOf("c", "x", "a")))
    }
}
