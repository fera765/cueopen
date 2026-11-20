package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildSquadTest {
    @Test fun cadaTarefaGanhaUmAgenteComNomeUnico() {
        val tasks = (1..30).map { "" to "Ajuste a seção $it" } + listOf("Design" to "cores do tema", "QA" to "teste a página")
        val p = BuildSquad.assign(tasks)
        assertEquals(tasks.size, p.size)
        assertEquals(p.size, p.map { it.name }.toSet().size)
    }

    @Test fun papelEPalavrasEscolhemOAgenteCerto() {
        val p = BuildSquad.assign(listOf("" to "Crie o endpoint REST de pedidos", "SEO" to "meta tags", "" to "Escreva o texto da headline"))
        assertEquals("Rex", p[0].name)
        assertEquals("Milo", p[1].name)
        assertEquals("Scout", p[2].name)
    }

    @Test fun cursorSegueOElementoEditado() {
        assertEquals("#pricing", BuildSquad.selectorFromEdit("<section id=\"pricing\" class=\"x\">"))
        assertEquals(".chip", BuildSquad.selectorFromEdit("<span class='chip big'>Codex</span>"))
        assertEquals(".hero-title", BuildSquad.selectorFromEdit("  .hero-title {\n color: red }"))
        assertNull(BuildSquad.selectorFromEdit("só texto"))
    }

    @Test fun overlaySoMostraQuemTrabalhaOuTerminou() {
        val ps = BuildSquad.assign(listOf("" to "a", "" to "b", "" to "c"))
        val sq = BuildSquad.Squad("s", "p", "g", null, listOf(
            BuildSquad.Worker(0, ps[0], "a", "A", "x0", "#a", BuildSquad.St.RUNNING),
            BuildSquad.Worker(1, ps[1], "b", "B", "x1", status = BuildSquad.St.QUEUED),
            BuildSquad.Worker(2, ps[2], "c", "C", "x2", status = BuildSquad.St.DONE),
        ))
        val j = kotlinx.serialization.json.Json.parseToJsonElement(BuildSquad.overlayJson(sq))
            .let { (it as kotlinx.serialization.json.JsonObject)["w"] as kotlinx.serialization.json.JsonArray }
        assertEquals(2, j.size)
        assertEquals("\"#a\"", (j[0] as kotlinx.serialization.json.JsonObject)["s"].toString())
        assertTrue(BuildSquad.overlayScript(BuildSquad.overlayJson(sq)).contains("__fluwySquad"))
    }

    @Test fun rotuloCurtoTiraOVerbo() {
        assertEquals("Chip do Codex", BuildSquad.shortLabel("Crie o chip do Codex"))
    }

    @Test fun pedidoDeSoftwareOfereceOExercito() {
        listOf("crie uma landing page para minha loja", "desenvolva uma api de pedidos", "faça o backend do app").forEach {
            val g = ToolContextPlanner.plan(ToolContextPlanner.Input("b-" + System.nanoTime(), it, emptySet(), false)).groups
            assertTrue(it, "build" in g)
        }
    }

    @Test fun reactClassNameEArquivoGuiamOCursor() {
        assertEquals(".pricing-card", BuildSquad.selectorFromEdit("<div className=\"pricing-card\">"))
        assertEquals(".hero", BuildSquad.selectorFromEdit("<section className={`hero \${x}`}>"))
        assertEquals("pricing-table", BuildSquad.hintFromPath("src/components/PricingTable.jsx"))
        assertEquals("footer", BuildSquad.hintFromPath("/root/workspace/app/src/components/Footer.tsx"))
        assertEquals("", BuildSquad.hintFromPath("src/App.jsx"))
    }
}
