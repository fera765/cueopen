package dev.cueopen.app.agent

import dev.cueopen.app.pages.FluwyPage
import dev.cueopen.app.pages.PageOps
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFixesTest {
    @Test fun missingArchiveIsNotAFileFailure() {
        assertTrue(ContextArchive.isArchivePath(".cue/out/oacb403f_301.txt"))
        assertTrue(ContextArchive.isArchivePath("/root/workspace/.cue/out/o6cb4946_3899.txt"))
        assertFalse(ContextArchive.isArchivePath("notes/plan.md"))
        assertTrue(AgentFileToolFailureGuard().record("file_read", "a", ContextArchive.MISSING) == null)
        val g = AgentFileToolFailureGuard()
        repeat(5) { assertEquals(null, g.record("file_read", "a$it", ContextArchive.MISSING)) }
    }

    private fun board(): FluwyPage {
        val p = PageOps.create("s", "Quadro", FluwyPage.KIND_KANBAN, "u").getOrThrow()
        return PageOps.apply(p, listOf(buildJsonObject { put("op", JsonPrimitive("add_card")); put("title", JsonPrimitive("Enviar proposta")) }), "u").getOrThrow()
    }

    @Test fun pageOpsAcceptTypeAliasAndTitleFallback() {
        val p = board()
        val moved = PageOps.apply(p, listOf(buildJsonObject {
            put("type", JsonPrimitive("move_card")); put("card_id", JsonPrimitive("c_inventado")); put("title", JsonPrimitive("enviar proposta"))
            put("column", JsonPrimitive(p.columns.last()))
        }), "u").getOrThrow()
        assertEquals(p.columns.last(), moved.cards.single().column)
    }

    @Test fun pageOpsErrorsExplainWhatExists() {
        val p = board()
        val noOp = PageOps.apply(p, listOf(buildJsonObject { put("title", JsonPrimitive("x")) }), "u").exceptionOrNull()!!.message!!
        assertTrue(noOp.contains("faltou o campo") && noOp.contains("add_card"))
        val missing = PageOps.apply(p, listOf(buildJsonObject { put("op", JsonPrimitive("update_card")); put("card_id", JsonPrimitive("zzz")) }), "u").exceptionOrNull()!!.message!!
        assertTrue(missing.contains("Enviar proposta"))
    }

    @Test fun flowNotFoundPointsToListFlows() {
        assertEquals(ToolOutcomeAdvisor.Reason.HUB_FLOW, ToolOutcomeAdvisor.classify("mcp_activepieces_hub__ap_test_flow", "❌ Flow not found"))
    }

    @Test fun repeatedIdenticalFailureWarnsThenStops() {
        val g = RepeatedToolFailureGuard()
        val v = (1..5).map { g.record("web_fetch", "{url:x}", "ERRO: HTTP 404 em x") }
        assertEquals(RepeatedToolFailureGuard.Verdict.WARN, v[2])
        assertEquals(RepeatedToolFailureGuard.Verdict.STOP, v[4])
        val h = RepeatedToolFailureGuard()
        repeat(4) { h.record("web_fetch", "{url:x}", "ERRO: x") }
        assertEquals(RepeatedToolFailureGuard.Verdict.OK, h.record("web_fetch", "{url:x}", "ok"))
    }

    @Test fun creditMessagesAreCredits() {
        assertEquals(dev.cueopen.app.llm.LlmRecovery.Category.CREDITS, dev.cueopen.app.llm.LlmRecovery.classify("You have reached your monthly spend limit", false))
        assertTrue(dev.cueopen.app.scheduler.QuietRun.isQuiet("Nada mudou. [SEM_NOVIDADES]"))
    }
}
