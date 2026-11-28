package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HeartbeatDispatchGateTest {
    @Test
    fun concurrentDispatchesForAnActiveCycleOnlyStartOnce() {
        val gate = HeartbeatDispatchGate()
        val busy = AtomicBoolean(false)
        val starts = AtomicInteger()
        val ready = CountDownLatch(24)
        val start = CountDownLatch(1)
        val done = CountDownLatch(24)
        repeat(24) {
            thread {
                ready.countDown()
                start.await()
                gate.dispatchIfIdle(isBusy = { busy.get() }) {
                    starts.incrementAndGet()
                    busy.set(true)
                }
                done.countDown()
            }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, starts.get())
    }
}
