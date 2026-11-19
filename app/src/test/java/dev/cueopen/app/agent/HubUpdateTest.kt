package dev.cueopen.app.agent

import dev.cueopen.app.connectors.HubImage
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HubUpdateTest {
    @Test fun reusesOnlyCompleteImage() {
        val d = File.createTempFile("rootfs", "").apply { delete(); mkdirs() }
        assertFalse(HubImage.ready(d))
        File(d, "usr/src/app").mkdirs(); File(d, "usr/src/app/docker-entrypoint.sh").writeText("x")
        assertFalse("sem marcador = extração incompleta", HubImage.ready(d))
        File(d, ".fluwy-image").writeText("{}")
        assertTrue(HubImage.ready(d))
        d.deleteRecursively()
    }
}
