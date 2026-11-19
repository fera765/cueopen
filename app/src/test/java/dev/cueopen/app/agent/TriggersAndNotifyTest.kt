package dev.cueopen.app.agent

import dev.cueopen.app.notify.NotificationCenter
import dev.cueopen.app.notify.NotificationCenter.Priority
import dev.cueopen.app.triggers.TriggerLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggersAndNotifyTest {
    private val out = "✅ Find Email completed (run r1).\n\n{\"messages\":[{\"id\":\"m1\",\"subject\":\"Proposta cliente\"},{\"id\":\"m2\",\"subject\":\"Newsletter\"}]}"

    @Test fun extractsItemsAndDetectsOnlyNewOnes() {
        val items = TriggerLogic.items(TriggerLogic.payload(out))
        assertEquals(2, items.size)
        val first = TriggerLogic.diff(items, emptyList(), "")
        assertEquals(2, first.fresh.size)
        val again = TriggerLogic.diff(items, first.seen, "")
        assertTrue(again.fresh.isEmpty())                        // nada novo = nada acorda
        val filtered = TriggerLogic.diff(items, emptyList(), "cliente")
        assertEquals(1, filtered.fresh.size)
        assertEquals("id:m1", TriggerLogic.itemKey(items[0]))
    }

    @Test fun notificationDecisions() {
        val now = 1_000_000L
        assertEquals(true to false, NotificationCenter.decide(Priority.SILENT, false, null, null, now))      // só histórico
        assertEquals(true to true, NotificationCenter.decide(Priority.HIGH, true, null, null, now))          // aprovação aparece mesmo com app aberto
        assertEquals(true to false, NotificationCenter.decide(Priority.NORMAL, true, null, null, now))       // app aberto: sem interromper
        assertFalse(NotificationCenter.decide(Priority.HIGH, false, now - 60_000, null, now).first)          // duplicata recente
        assertEquals(true to false, NotificationCenter.decide(Priority.NORMAL, false, null, now - 5_000, now)) // limite de frequência
    }
}
