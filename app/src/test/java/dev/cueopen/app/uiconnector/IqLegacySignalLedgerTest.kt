package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqLegacySignalLedger
import org.junit.Assert.assertEquals
import org.junit.Test

class IqLegacySignalLedgerTest {
    @Test fun `le somente ids distintos para migracao de alarmes antigos`() {
        assertEquals(listOf("old-1", "old-2"), IqLegacySignalLedger.ids("""{"records":[{"id":"old-1"},{"id":"old-1"},{"id":"old-2","state":"DONE"}],"extra":true}"""))
        assertEquals(emptyList<String>(), IqLegacySignalLedger.ids("broken"))
        assertEquals(emptyList<String>(), IqLegacySignalLedger.ids(null))
    }
}
