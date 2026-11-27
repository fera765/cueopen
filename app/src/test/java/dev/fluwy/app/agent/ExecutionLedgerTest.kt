package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExecutionLedgerTest {
    @Test fun recordsReusesAndPersistsRuns() {
        val dir = Files.createTempDirectory("ledger").toFile()
        ExecutionLedger.dirResolver = { dir }
        val r = ExecutionLedger.begin("s1", "task1", "Pesquisar preços", listOf("web_fetch", "show_cards"))
        assertEquals(r.runId, ExecutionLedger.begin("s1", "task1", "x").runId)          // mesma tarefa: retoma
        val h = ExecutionLedger.hash("web_fetch|{url:a}")
        assertNull(ExecutionLedger.cached("s1", "web_fetch", h))
        ExecutionLedger.record("s1", "web_fetch", h, true, 120, "conteúdo A")
        assertEquals("conteúdo A", ExecutionLedger.cached("s1", "web_fetch", h))         // leitura idêntica reaproveitada
        ExecutionLedger.record("s1", "file_write", ExecutionLedger.hash("w"), true, 5, "ok")
        assertNull(ExecutionLedger.cached("s1", "file_write", ExecutionLedger.hash("w"))) // escrita nunca é reaproveitada
        ExecutionLedger.end("s1", "DONE", "Feito")
        assertNull(ExecutionLedger.cached("s1", "web_fetch", h))                          // execução fechada: cache limpo
        val saved = ExecutionLedger.history("s1").last()
        assertEquals("DONE", saved.state); assertEquals(2, saved.ops.size); assertTrue(saved.endedAt != null)
        assertTrue(ExecutionLedger.begin("s1", "task2", "nova").runId != r.runId)
        dir.deleteRecursively()
    }
}
