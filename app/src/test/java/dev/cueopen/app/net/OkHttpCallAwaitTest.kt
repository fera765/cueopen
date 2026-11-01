package dev.cueopen.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpCallAwaitTest {
    @Test
    fun cancellingAwaitCancelsTheUnderlyingHttpCall() = runBlocking {
        val interceptorEntered = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            interceptorEntered.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("cancelled by test")
        }.build()
        val call = client.newCall(
            Request.Builder().url("http://127.0.0.1:1/cancel-test").build()
        )
        val waiter = async(Dispatchers.IO) { call.await().close() }

        assertTrue(interceptorEntered.await(2, TimeUnit.SECONDS))
        waiter.cancelAndJoin()

        assertTrue(call.isCanceled())
    }
}
