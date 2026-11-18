package dev.cueopen.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HubGuardTest {
    private val pfx = "mcp_activepieces_hub"
    private fun st(auto: Boolean, created: Int = 0, fails: Int = 0) = HubGuard.TaskState(auto, created, fails)
    private fun obj(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })

    @Test fun oneShotTaskRefusesFlowBuildingAndPointsToRunAction() {
        val d = HubGuard.check("${pfx}__ap_build_flow", obj("flowName" to "tmp_gmail"), pfx, st(auto = false))
        assertTrue(d is HubGuard.Decision.Refuse)
        assertTrue((d as HubGuard.Decision.Refuse).message.contains("${pfx}__ap_run_action"))
        assertTrue(HubGuard.check("${pfx}__ap_delete_step", obj(), pfx, st(false)) is HubGuard.Decision.Refuse)
        assertTrue(HubGuard.check("${pfx}__ap_run_action", obj("pieceName" to "gmail"), pfx, st(false)) is HubGuard.Decision.Allow)
        assertTrue(HubGuard.check("${pfx}__ap_list_flows", obj(), pfx, st(false)) is HubGuard.Decision.Allow)
    }

    @Test fun automationAllowsOneFlowWithFluwyPrefix() {
        val d = HubGuard.check("${pfx}__ap_build_flow", obj("flowName" to "Resumo diário"), pfx, st(auto = true)) as HubGuard.Decision.Allow
        assertEquals("Fluwy · Resumo diário", d.args["flowName"]!!.jsonPrimitive.content)
        assertTrue(HubGuard.check("${pfx}__ap_build_flow", obj("flowName" to "Outro"), pfx, st(true, created = 1)) is HubGuard.Decision.Refuse)
        assertTrue(HubGuard.check("${pfx}__ap_update_step", obj(), pfx, st(true, created = 1)) is HubGuard.Decision.Allow)
    }

    @Test fun sameFailingCallIsStoppedAfterTwoFailures() {
        assertTrue(HubGuard.check("${pfx}__ap_run_action", obj(), pfx, st(false, fails = 2)) is HubGuard.Decision.Refuse)
        assertTrue(HubGuard.check("${pfx}__ap_run_action", obj(), pfx, st(false, fails = 1)) is HubGuard.Decision.Allow)
    }

    @Test fun flowToolsHiddenUnlessAutomationIntent() {
        assertTrue(HubGuard.hideTool("${pfx}__ap_build_flow", pfx, automation = false))
        assertFalse(HubGuard.hideTool("${pfx}__ap_build_flow", pfx, automation = true))
        assertFalse(HubGuard.hideTool("${pfx}__ap_run_action", pfx, automation = false))
        assertFalse(HubGuard.hideTool("mcp_github__create_flow", pfx, automation = false))
        assertTrue(HubGuard.automationIntent(ToolContextPlanner.norm("Todo dia às 8h me mande um resumo dos e-mails")))
        assertTrue(HubGuard.automationIntent(ToolContextPlanner.norm("crie uma automação para quando chegar e-mail")))
        assertFalse(HubGuard.automationIntent(ToolContextPlanner.norm("leia meus e-mails de hoje")))
    }

    @Test fun cleanupPicksTestDraftsAndDuplicatesButNeverLiveFlows() {
        val text = """
            ✅ Listed 6 flow(s):
            - tmp_gmail_hoje (N64jNOp27yvxeL9OHdUQR) | DISABLED | draft | trigger: @activepieces/piece-webhook
            - Leitor de Email (teste) (hVpybFCMxBgalsCgAyTZb) | DISABLED | draft | trigger: @activepieces/piece-webhook
            - Leitor de Email (teste) (hadUgfEOZHmn8KmPzRJgd) | DISABLED | draft | trigger: @activepieces/piece-webhook
            - Fluwy · Resumo diário (AAAAAAAAbbbbbb11) | ENABLED | published | trigger: @activepieces/piece-schedule
            - Fluwy · Resumo diário (CCCCCCCCdddddd22) | DISABLED | draft | trigger: @activepieces/piece-schedule
            - Vendas (EEEEEEEEffffff33) | DISABLED | draft | trigger: @activepieces/piece-schedule
        """.trimIndent()
        val flows = HubGuard.parseFlows(text)
        assertEquals(6, flows.size)
        val ids = HubGuard.cleanupCandidates(flows).map { it.id }.toSet()
        assertTrue("N64jNOp27yvxeL9OHdUQR" in ids)
        assertTrue("hVpybFCMxBgalsCgAyTZb" in ids && "hadUgfEOZHmn8KmPzRJgd" in ids)
        assertTrue("CCCCCCCCdddddd22" in ids)
        assertFalse("AAAAAAAAbbbbbb11" in ids)
        assertFalse("EEEEEEEEffffff33" in ids)
    }
}

class HubRecipesTest {
    private val cat by lazy {
        val f = listOf(File("src/main/assets/hub/recipes.json"), File("app/src/main/assets/hub/recipes.json")).first { it.exists() }
        HubRecipes.parse(f.readText())
    }

    @Test fun catalogParsesAndActionsAreComplete() {
        assertTrue(cat.actions.size >= 8)
        cat.actions.forEach { assertTrue(it.id, it.piece.startsWith("@activepieces/piece-") && it.action.isNotBlank() && it.keywords.isNotEmpty()) }
        assertTrue(cat.triggers.any { it.trigger == "every_day" })
    }

    @Test fun readingEmailGetsTheGmailRecipeAndNoTriggers() {
        val lines = HubRecipes.promptLines(cat, ToolContextPlanner.norm("leia meus e-mails de hoje"), automation = false, hubPrefix = "mcp_activepieces_hub")
        val all = lines.joinToString("\n")
        assertTrue(all.contains("gmail_search_mail"))
        assertTrue(all.contains("mcp_activepieces_hub__ap_run_action"))
        assertFalse(all.contains("Gatilhos prontos"))
    }

    @Test fun automationAddsTriggersAndUnrelatedGetsNothing() {
        assertTrue(HubRecipes.promptLines(cat, ToolContextPlanner.norm("todo dia me mande os e-mails"), true, "p").joinToString().contains("every_day"))
        assertTrue(HubRecipes.promptLines(cat, ToolContextPlanner.norm("crie um site bonito"), false, "p").isEmpty())
    }

    @org.junit.Test fun archivedFlowsLeaveCleanup() {
        val flows = listOf(
            HubGuard.FlowLine("teste envio", "a", "DISABLED", false),
            HubGuard.FlowLine(HubGuard.archivedName("teste antigo"), "b", "DISABLED", false),
        )
        org.junit.Assert.assertEquals(listOf("a"), HubGuard.cleanupCandidates(flows).map { it.id })
        org.junit.Assert.assertEquals("🗑 x", HubGuard.archivedName(HubGuard.archivedName("x")))
    }
}
