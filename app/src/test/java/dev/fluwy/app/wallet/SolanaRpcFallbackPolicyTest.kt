package dev.fluwy.app.wallet

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaRpcFallbackPolicyTest {
    @Test
    fun rateLimitPayloadAndPermissionResponsesNeverTriggerEndpointFallback() {
        listOf(401, 403, 413, 429).forEach { code ->
            assertFalse("HTTP $code must not be retried on another public endpoint", isReadEndpointFallbackAllowed(SolanaRpcHttpException(code)))
        }
    }

    @Test
    fun transientServerAndNetworkFailuresCanUseOneAlternativeReadEndpoint() {
        assertTrue(isReadEndpointFallbackAllowed(SolanaRpcHttpException(503)))
        assertTrue(isReadEndpointFallbackAllowed(SocketTimeoutException("read timeout")))
        assertFalse(isReadEndpointFallbackAllowed(IllegalArgumentException("invalid address")))
        assertFalse(isReadEndpointFallbackAllowed(IOException("non-HTTP RPC error")))
    }
}
