package dev.cueopen.app.wallet

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class SolanaRpcClientTest {
    private val signature =
        "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"

    private fun client(vararg responses: String): SolanaRpcClient {
        val queue = ArrayDeque(responses.toList())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val payload = queue.removeFirst()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }.build()
        return SolanaRpcClient(endpoint = "https://rpc.test", client = http)
    }

    @Test
    fun getFeeForMessageReadsValueEnvelope() = runBlocking {
        val rpc = client("""{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":7},"value":5000}}""")
        assertEquals(5000L, rpc.getFeeForMessageLamports(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun unavailableFeeReturnsNullRatherThanZero() = runBlocking {
        val rpc = client("""{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":7},"value":null}}""")
        assertNull(rpc.getFeeForMessageLamports(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun simulationReadsNestedValueAndRejectsExecutionErrors() = runBlocking {
        val success = client(
            """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":7},"value":{"err":null,"logs":["ok"],"unitsConsumed":120}}}"""
        ).simulateSerializedTransaction(byteArrayOf(1, 2, 3))
        assertTrue(success.success)
        assertEquals(listOf("ok"), success.logs)
        assertEquals(120L, success.unitsConsumed)

        val failure = client(
            """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":7},"value":{"err":{"InstructionError":[0,"Custom"]},"logs":["failed"],"unitsConsumed":12}}}"""
        ).simulateSerializedTransaction(byteArrayOf(1, 2, 3))
        assertFalse(failure.success)
        assertTrue(failure.error.orEmpty().contains("InstructionError"))
    }

    @Test
    fun signatureStatusDistinguishesFinalizedAndFailed() = runBlocking {
        val finalized = client(
            """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":9},"value":[{"slot":8,"confirmations":null,"err":null,"confirmationStatus":"finalized"}]}}"""
        ).getSignatureStatus(signature)
        assertEquals(8L, finalized?.slot)
        assertTrue(finalized?.isFinalized == true)
        assertFalse(finalized?.failedOnChain == true)

        val failed = client(
            """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":9},"value":[{"slot":8,"confirmations":0,"err":{"InstructionError":[0,"Custom"]},"confirmationStatus":"confirmed"}]}}"""
        ).getSignatureStatus(signature)
        assertFalse(failed?.isFinalized == true)
        assertTrue(failed?.failedOnChain == true)
    }

    @Test
    fun latestBlockhashParsesExpiryAndRejectsMalformedBlockhash() = runBlocking {
        val blockhash = "11111111111111111111111111111111"
        val rpc = client(
            """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":99},"value":{"blockhash":"$blockhash","lastValidBlockHeight":1234}}}"""
        )
        assertEquals(SolanaBlockhash(blockhash, 1234L), rpc.getLatestBlockhash())

        val malformed = client(
            """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":99},"value":{"blockhash":"not-a-blockhash","lastValidBlockHeight":1234}}}"""
        )
        assertThrows(IOException::class.java) { runBlocking { malformed.getLatestBlockhash() } }
    }

    @Test
    fun invalidRpcEndpointFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaRpcClient(endpoint = "http://rpc.test", client = OkHttpClient())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SolanaRpcClient(endpoint = "https://user:password@rpc.test", client = OkHttpClient())
        }
    }
}
