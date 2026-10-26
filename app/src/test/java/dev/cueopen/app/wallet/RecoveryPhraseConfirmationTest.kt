package dev.cueopen.app.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPhraseConfirmationTest {
    @Test
    fun normalizesCaseAndWhitespace() {
        val candidate = "  abandon   ability\table ".toCharArray()
        val expected = "abandon ability able".toCharArray()
        try {
            assertTrue(RecoveryPhraseConfirmation.matches(candidate, expected))
        } finally {
            candidate.fill('\u0000')
            expected.fill('\u0000')
        }
    }

    @Test
    fun rejectsDifferentWordsAndWordCounts() {
        val candidate = "abandon ability able".toCharArray()
        val different = "abandon ability about".toCharArray()
        val shorter = "abandon ability".toCharArray()
        try {
            assertFalse(RecoveryPhraseConfirmation.matches(candidate, different))
            assertFalse(RecoveryPhraseConfirmation.matches(candidate, shorter))
        } finally {
            candidate.fill('\u0000')
            different.fill('\u0000')
            shorter.fill('\u0000')
        }
    }
}