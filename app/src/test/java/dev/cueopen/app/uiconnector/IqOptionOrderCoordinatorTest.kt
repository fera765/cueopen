package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqOptionOrderCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IqOptionOrderCoordinatorTest {
    @Test fun `reserva compartilhada impede sobreposicao ate chegar ACK`() = runBlocking {
        val coordinator = IqOptionOrderCoordinator()
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            coordinator.runReserved {
                peak.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                firstEntered.complete(Unit)
                ack.await()
                inFlight.decrementAndGet()
            }
        }
        firstEntered.await()
        val second = launch(Dispatchers.Default) {
            secondStarted.complete(Unit)
            coordinator.runReserved {
                peak.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                secondEntered.complete(Unit)
                inFlight.decrementAndGet()
            }
        }
        secondStarted.await()
        delay(60)
        assertFalse("a segunda execução entrou antes do ACK da primeira", secondEntered.isCompleted)
        ack.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
        assertEquals(1, peak.get())
    }

    @Test fun `falha libera reserva para operacao seguinte`() = runBlocking {
        val coordinator = IqOptionOrderCoordinator()
        val failed = runCatching { coordinator.runReserved<Unit> { error("ACK falhou") } }
        assertFalse(failed.isSuccess)
        var acquired = false
        coordinator.runReserved { acquired = true }
        assertTrue(acquired)
    }
}
