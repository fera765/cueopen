package dev.cueopen.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CancellationSafeResultTest {
    @Test
    fun cancellationIsRethrownInsteadOfBecomingARecoverableFailure() = runBlocking {
        val cancellation = CancellationException("watchdog")
        val thrown = runCatching {
            runCatchingCancellable<Int> { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun ordinaryFailuresRemainAvailableToToolErrorHandling() = runBlocking {
        val failure = IOException("HTTP 429")

        val result = runCatchingCancellable<Int> { throw failure }

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
    }
}
