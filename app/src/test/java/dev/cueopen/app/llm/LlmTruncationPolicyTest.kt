package dev.cueopen.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTruncationPolicyTest {
    @Test
    fun distinctFragmentsArePreservedUntilTheBoundThenStop() {
        val policy = LlmTruncationPolicy(maxFragments = 3)

        assertEquals(LlmTruncationPolicy.Decision.CONTINUE, policy.accept("parte 1 "))
        assertEquals(LlmTruncationPolicy.Decision.CONTINUE, policy.accept("parte 2 "))
        assertEquals(LlmTruncationPolicy.Decision.STOP, policy.accept("parte 3"))
        assertEquals("parte 1 parte 2 parte 3", policy.partialText())
    }

    @Test
    fun identicalFragmentStopsWithoutDuplicatingTheSavedPrefix() {
        val policy = LlmTruncationPolicy()

        assertEquals(LlmTruncationPolicy.Decision.CONTINUE, policy.accept("mesmo trecho"))
        assertEquals(LlmTruncationPolicy.Decision.STOP, policy.accept("mesmo trecho"))
        assertEquals("mesmo trecho", policy.partialText())
    }

    @Test
    fun completedContinuationJoinsAndResetsTheTurn() {
        val policy = LlmTruncationPolicy()
        policy.accept("prefixo ")

        assertEquals("prefixo final", policy.complete("final"))
        assertTrue(policy.partialText().isEmpty())
        assertEquals(LlmTruncationPolicy.Decision.CONTINUE, policy.accept("resposta completa"))
    }
}
