package dev.fluwy.app.agent

import dev.fluwy.app.agent.SectionPlanEngine.Draft
import dev.fluwy.app.agent.SectionPlanEngine.State
import dev.fluwy.app.agent.SectionPlanEngine.Verdict
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionPlanEngineTest {
    private val team = setOf("Pesquisador", "Separador", "Qualificador", "Paginas", "Emails")

    private fun fiveAgentPlan() = listOf(
        SectionTask("T1", "a1", "Pesquisador", "pesquisar"),
        SectionTask("T2", "a2", "Separador", "separar leads", listOf("T1")),
        SectionTask("T3", "a3", "Qualificador", "qualificar", listOf("T2")),
        SectionTask("T4", "a4", "Paginas", "criar páginas", listOf("T3")),
        SectionTask("T5", "a5", "Emails", "criar e-mails", listOf("T3")),
    )

    /** Simula o ciclo completo: entrega → aprovação → próximas etapas prontas, sem mensagem manual. */
    @Test fun `five agent pipeline advances automatically until completed`() {
        var plan = fiveAgentPlan()
        assertEquals(listOf("T1"), SectionPlanEngine.ready(plan).map { it.id })
        val order = mutableListOf<String>()
        var guard = 0
        while (SectionPlanEngine.state(plan) != State.COMPLETED && guard++ < 20) {
            val ready = SectionPlanEngine.ready(plan)
            ready.forEach { plan = SectionPlanEngine.started(plan, it.id, 1) }
            assertEquals(State.WAITING_FOR_AGENT, SectionPlanEngine.state(plan))
            ready.forEach { t ->
                order += t.id
                plan = SectionPlanEngine.finished(plan, t.id, ok = true, result = "resultado ${t.id}", now = 2)
            }
            assertEquals(State.VALIDATING, SectionPlanEngine.state(plan))
            ready.forEach { plan = SectionPlanEngine.review(plan, it.id, Verdict.APPROVE, "ok").first }
        }
        assertEquals(State.COMPLETED, SectionPlanEngine.state(plan))
        assertEquals(listOf("T1", "T2", "T3", "T4", "T5"), order)
        // páginas e e-mails ficaram prontos juntos (paralelo) depois do qualificador
        val afterT3 = fiveAgentPlan().map { if (it.id in setOf("T1", "T2", "T3")) it.copy(status = TaskStatus.DONE) else it }
        assertEquals(setOf("T4", "T5"), SectionPlanEngine.ready(afterT3).map { it.id }.toSet())
        // a etapa seguinte recebe a entrega aprovada como insumo
        assertTrue(SectionPlanEngine.handoff(plan, plan.first { it.id == "T2" }).contains("resultado T1"))
    }

    @Test fun `no activity is never treated as completion`() {
        val plan = SectionPlanEngine.started(fiveAgentPlan(), "T1", 1)
        assertEquals(State.WAITING_FOR_AGENT, SectionPlanEngine.state(plan))
        assertEquals(State.IDLE, SectionPlanEngine.state(emptyList()))
    }

    @Test fun `validation rejects cycles unknown agents and dangling deps`() {
        assertNotNull(SectionPlanEngine.validate(listOf(Draft("A", "Separador", "x", listOf("B")), Draft("B", "Separador", "y", listOf("A"))), team))
        assertNotNull(SectionPlanEngine.validate(listOf(Draft("A", "Ninguém", "x", emptyList())), team))
        assertNotNull(SectionPlanEngine.validate(listOf(Draft("A", "Separador", "x", listOf("Z"))), team))
        assertNotNull(SectionPlanEngine.validate(listOf(Draft("A", "Separador", "x", emptyList()), Draft("A", "Separador", "y", emptyList())), team))
        assertNull(SectionPlanEngine.validate(listOf(Draft("A", "pesquisador", "x", emptyList()), Draft("B", "Separador", "y", listOf("A"))), team))
        assertEquals(listOf("A", "B", "C", "A"), SectionPlanEngine.cycle(mapOf("A" to listOf("B"), "B" to listOf("C"), "C" to listOf("A"))))
    }

    @Test fun `redo and failures are bounded`() {
        var plan = SectionPlanEngine.started(fiveAgentPlan(), "T1", 1)
        plan = SectionPlanEngine.finished(plan, "T1", ok = true, result = "fraco", now = 2)
        repeat(SectionPlanEngine.MAX_ATTEMPTS - 1) {
            plan = SectionPlanEngine.review(plan, "T1", Verdict.REDO, "mais fontes").first
            assertEquals(TaskStatus.PENDING, plan.first().status)
            plan = SectionPlanEngine.finished(SectionPlanEngine.started(plan, "T1", 3), "T1", ok = true, result = "ainda fraco", now = 4)
        }
        plan = SectionPlanEngine.review(plan, "T1", Verdict.REDO, "de novo").first
        assertEquals(TaskStatus.FAILED, plan.first().status)
        // dependentes de uma etapa falhada nunca ficam esperando para sempre
        assertEquals(setOf("T2", "T3", "T4", "T5"), SectionPlanEngine.starved(plan).map { it.id }.toSet())
        assertEquals(State.FAILED, SectionPlanEngine.state(plan))

        var crash = SectionPlanEngine.started(fiveAgentPlan(), "T1", 1)
        repeat(SectionPlanEngine.MAX_ATTEMPTS) { crash = SectionPlanEngine.finished(SectionPlanEngine.started(crash, "T1", 1), "T1", ok = false, result = "erro", now = 2) }
        assertEquals(TaskStatus.FAILED, crash.first().status)
    }

    @Test fun `cannot approve work that was not delivered`() {
        val (plan, err) = SectionPlanEngine.review(fiveAgentPlan(), "T1", Verdict.APPROVE, "")
        assertNotNull(err)
        assertEquals(TaskStatus.PENDING, plan.first().status)
        assertNotNull(SectionPlanEngine.review(fiveAgentPlan(), "T9", Verdict.APPROVE, "").second)
    }

    @Test fun `signature changes only with progress`() {
        val a = fiveAgentPlan()
        assertEquals(SectionPlanEngine.signature(a), SectionPlanEngine.signature(a.map { it.copy(note = "x") }))
        assertTrue(SectionPlanEngine.signature(a) != SectionPlanEngine.signature(SectionPlanEngine.started(a, "T1", 1)))
    }
}
