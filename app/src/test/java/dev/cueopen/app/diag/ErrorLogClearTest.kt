package dev.cueopen.app.diag

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogClearTest {
    @Test fun `limpa log e ultimo erro sem apagar dados do app ou logs de conectores`() {
        val directory = Files.createTempDirectory("cue-error-log-clear").toFile()
        try {
            val errorLog = File(directory, "error_log.txt").apply { writeText("erro") }
            val lastCrash = File(directory, "last_crash.txt").apply { writeText("crash") }
            val appState = File(directory, "cueopen_state.json").apply { writeText("estado") }
            val connectorDirectory = File(directory, "connector-debug").apply { mkdirs() }
            val unrelatedConnectorLog = File(connectorDirectory, "connector-other.debug.log").apply { writeText("diagnostico") }

            ErrorLog.clearFiles(directory)

            assertFalse(errorLog.exists())
            assertFalse(lastCrash.exists())
            assertTrue(appState.exists())
            assertTrue(unrelatedConnectorLog.exists())
            assertTrue(appState.readText() == "estado")
            assertTrue(unrelatedConnectorLog.readText() == "diagnostico")
        } finally {
            directory.deleteRecursively()
        }
    }
}
