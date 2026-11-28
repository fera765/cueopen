package dev.fluwy.app.agent

import dev.fluwy.app.agent.ScriptBuilderPolicy.Gate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptBuilderPolicyTest {
    @Test fun `discovery is required before the first test`() {
        val p = ScriptBuilderPolicy()
        assertTrue(p.beforeTest("a") is Gate.Deny)
        p.onInspect()
        assertTrue(p.beforeTest("a") is Gate.Allow)
    }

    @Test fun `two failures force a new discovery and failed code is never retried`() {
        val p = ScriptBuilderPolicy(); p.onInspect()
        p.afterTest("a", false)
        assertTrue(p.beforeTest("a") is Gate.Deny)      // mesmo código
        assertTrue(p.beforeTest("b") is Gate.Allow)
        p.afterTest("b", false)
        assertTrue(p.beforeTest("c") is Gate.Deny)      // 2 falhas: redescobrir
        p.onInspect()
        assertTrue(p.beforeTest("c") is Gate.Allow)
    }

    @Test fun `only the tested passing code can be proposed`() {
        val p = ScriptBuilderPolicy(); p.onInspect()
        assertFalse(p.canPropose("x"))
        p.afterTest("x", true)
        assertTrue(p.canPropose(" x \n"))
        assertFalse(p.canPropose("x2"))
    }

    @Test fun `tests are capped and the cap stops the run`() {
        val p = ScriptBuilderPolicy()
        repeat(ScriptBuilderPolicy.MAX_TESTS) { i -> p.onInspect(); assertTrue(p.beforeTest("c$i") is Gate.Allow); p.afterTest("c$i", false) }
        p.onInspect()
        val g = p.beforeTest("novo")
        assertTrue(g is Gate.Deny && g.stop)
        p.newRound(); p.onInspect()
        assertTrue(p.beforeTest("novo") is Gate.Allow)
        assertEquals(0, p.testsRun)
    }

    @Test fun `verdict needs clean run and true verify`() {
        assertTrue(ScriptBuilderPolicy.passed("ok", emptyList(), true))
        assertFalse(ScriptBuilderPolicy.passed("ok", emptyList(), false))
        assertFalse(ScriptBuilderPolicy.passed("ok", listOf("TypeError"), true))
        assertFalse(ScriptBuilderPolicy.passed("err:x", emptyList(), true))
    }

    @Test fun `bad regex in the shell gets an actionable diagnosis`() {
        assertEquals(ToolOutcomeAdvisor.Reason.BAD_REGEX, ToolOutcomeAdvisor.classify("shell", "grep: bad regex '.\\{0,700\\}': Invalid contents of {}\n[exit 2]"))
    }
}
