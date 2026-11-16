package dev.cueopen.app.pages

import dev.cueopen.app.data.Section
import dev.cueopen.app.data.SectionTask
import dev.cueopen.app.data.TaskStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOpsTest {
    private fun ops(s: String): List<JsonObject> = Json.parseToJsonElement(s).jsonArray.map { it.jsonObject }

    @Test fun `kanban batch is applied atomically by several agents`() {
        val p = PageOps.create("space", "Pesquisa", "kanban", "coord").getOrThrow()
        assertEquals(FluwyPage.DEFAULT_COLUMNS, p.columns)
        val a = PageOps.apply(p, ops("""[{"op":"add_card","title":"Ler Reddit","assignee":"Ana"},{"op":"add_card","title":"Ler X","column":"Fazendo"}]"""), "Ana").getOrThrow()
        assertEquals(2, a.cards.size); assertEquals(2, a.version)
        val b = PageOps.apply(a, ops("""[{"op":"move_card","title":"ler reddit","column":"feito"}]"""), "Bia").getOrThrow()
        assertEquals("Feito", b.cards.first { it.title == "Ler Reddit" }.column); assertEquals("Bia", b.updatedBy)
        val bad = PageOps.apply(b, ops("""[{"op":"add_card","title":"Novo"},{"op":"move_card","card_id":"nao_existe","column":"Feito"}]"""), "Ana")
        assertTrue(bad.isFailure); assertTrue(bad.exceptionOrNull()!!.message!!.contains("operação 2"))
        assertTrue(PageOps.render(b).contains("## Feito (1)"))
    }

    @Test fun `doc table checklist and html operations`() {
        val d = PageOps.create("s", "Relatório", "doc", "a", markdown = "# Título\nTexto velho").getOrThrow()
        assertEquals("# Título\nTexto novo", PageOps.apply(d, ops("""[{"op":"replace_text","find":"velho","replace":"novo"}]"""), "a").getOrThrow().markdown)
        val t = PageOps.create("s", "Preços", "table", "a", headers = listOf("Produto", "Preço")).getOrThrow()
        val t2 = PageOps.apply(t, ops("""[{"op":"add_row","cells":["A","10"]},{"op":"add_row","cells":["B","20"]},{"op":"update_row","index":2,"cells":["B","25"]}]"""), "a").getOrThrow()
        assertEquals(listOf("B", "25"), t2.rows[1])
        val c = PageOps.create("s", "Checklist", "checklist", "a").getOrThrow()
        val c2 = PageOps.apply(c, ops("""[{"op":"add_item","title":"Validar checkout"},{"op":"check","title":"Validar checkout"}]"""), "a").getOrThrow()
        assertEquals("Feito", c2.cards.single().column)
        assertTrue(PageOps.apply(d, ops("""[{"op":"set_html","html":"<p>x</p>"}]"""), "a").isFailure)
        assertTrue(PageOps.create("s", "x", "planilha", "a").isFailure)
        val h = PageOps.create("s", "Site", "html", "a", html = "<h1>Clínica   Sorriso</h1>").getOrThrow()
        assertEquals("<h1>Clínica Feliz</h1>", PageOps.apply(h, ops("""[{"op":"replace_text","find":"<h1>Clínica Sorriso</h1>","replace":"<h1>Clínica Feliz</h1>"}]"""), "a").getOrThrow().html)
        assertEquals("{\"n\":1}", PageOps.apply(h, ops("""[{"op":"set_data","data":{"n":1}}]"""), "a").getOrThrow().data)
        assertEquals(h, PageOps.sameTitle(listOf(h), "s", "site!"))
    }

    @Test fun `section plan becomes a synced team board`() {
        val sec = Section(id = "sec1", name = "Radar", coordinatorAgentId = "c", sessionId = "chat1",
            plan = listOf(SectionTask("t1", "a1", "Ana", "Pesquisar ofertas", status = TaskStatus.RUNNING), SectionTask("t2", "a2", "Bia", "Validar preços")))
        val pages = PageOps.syncBoard(emptyList(), sec)
        val board = pages.single()
        assertEquals("Fazendo", board.cards.first { it.id == "task_t1" }.column); assertEquals("chat1", board.spaceId)
        val withManual = listOf(PageOps.apply(board, ops("""[{"op":"add_card","title":"Ideia extra"}]"""), "Ana").getOrThrow())
        val done = PageOps.syncBoard(withManual, sec.copy(plan = sec.plan.map { it.copy(status = TaskStatus.DONE) }))
        val b2 = done.single()
        assertTrue(b2.cards.filter { it.id.startsWith("task_") }.all { it.column == "Feito" }); assertTrue(b2.cards.any { it.title == "Ideia extra" })
        assertEquals(done, PageOps.syncBoard(done, sec.copy(plan = sec.plan.map { it.copy(status = TaskStatus.DONE) })))
    }
}
