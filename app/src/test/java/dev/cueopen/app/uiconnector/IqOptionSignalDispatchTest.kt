package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqArmedSignal
import dev.cueopen.app.uiconnector.iqoption.IqDispatchAlarmPort
import dev.cueopen.app.uiconnector.iqoption.IqDispatchClock
import dev.cueopen.app.uiconnector.iqoption.IqDispatchOutcome
import dev.cueopen.app.uiconnector.iqoption.IqOptionSignalDispatcher
import dev.cueopen.app.uiconnector.iqoption.IqSignalDispatchPort
import dev.cueopen.app.uiconnector.iqoption.IqSignalLedger
import dev.cueopen.app.uiconnector.iqoption.IqSignalStore
import dev.cueopen.app.uiconnector.iqoption.IqSubmissionResult
import dev.cueopen.app.uiconnector.iqoption.KvStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IqOptionSignalDispatchTest {
    private class FakeClock(var now: Long?) : IqDispatchClock {
        override fun serverNowMs(): Long? = now
    }

    private class FakeAlarms(var permission: Boolean = true) : IqDispatchAlarmPort {
        val scheduled = mutableListOf<Pair<String, Long>>()
        val cancelled = mutableListOf<String>()
        override fun canScheduleExact(): Boolean = permission
        override fun schedule(signal: IqArmedSignal, atServerEpochMs: Long): Boolean {
            if (!permission) return false
            scheduled += signal.id to atServerEpochMs
            return true
        }
        override fun cancel(signal: IqArmedSignal) { cancelled += signal.id }
    }

    private class FakePort : IqSignalDispatchPort {
        var revalidationFailure: String? = null
        var submissionResult: IqSubmissionResult = IqSubmissionResult.Accepted("ACK mock")
        var duringRevalidation: (() -> Unit)? = null
        val validations = AtomicInteger()
        val submissions = AtomicInteger()
        override suspend fun revalidate(signal: IqArmedSignal): String? {
            validations.incrementAndGet()
            duringRevalidation?.invoke()
            return revalidationFailure
        }
        override suspend fun submit(signal: IqArmedSignal): IqSubmissionResult {
            submissions.incrementAndGet()
            return submissionResult
        }
    }

    private class MemoryKv : KvStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
    }

    private fun signal(target: Long = 1_800_000_000L, id: String = "iq-$target-7") = IqArmedSignal(
        id = id, asset = "EURUSD-OTC", activeId = 7, direction = "call", amount = 1.0,
        minutes = 1, targetOpenSec = target, timeframeSeconds = 60, entryWindowSeconds = 10,
        armedAtMs = (target - 120) * 1_000L,
    )

    private fun dispatcher(
        store: IqSignalStore,
        clock: FakeClock,
        alarms: FakeAlarms,
        port: FakePort,
    ) = IqOptionSignalDispatcher(store, clock, alarms, port)

    @Test fun `alarme revalida e envia sem acordar agente`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)

        assertEquals(IqArmedSignal.ARMED, dispatch.arm(sig).state)
        assertEquals(IqDispatchOutcome.VALIDATED, dispatch.wake(sig.id))
        clock.now = sig.targetOpenMs
        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))
        assertEquals(1, broker.validations.get())
        assertEquals(1, broker.submissions.get())
        assertEquals(IqArmedSignal.EXECUTED, store.get(sig.id)?.state)
    }

    @Test fun `revalidacao acordada 16 segundos tarde expira sem ordem`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L + 16_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(IqArmedSignal.EXPIRED, store.get(sig.id)?.state)
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `estado serializado sobrevive reinicio do processo`() = runBlocking {
        val sig = signal()
        val kv = MemoryKv()
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val firstProcess = dispatcher(IqSignalLedger(kv), clock, alarms, broker)
        firstProcess.arm(sig)

        val afterRestart = dispatcher(IqSignalLedger(kv), clock, alarms, broker)
        assertEquals(IqDispatchOutcome.VALIDATED, afterRestart.wake(sig.id))
        clock.now = sig.targetOpenMs
        val afterSecondRestart = dispatcher(IqSignalLedger(kv), clock, alarms, broker)
        assertEquals(IqDispatchOutcome.EXECUTED, afterSecondRestart.wake(sig.id))
        assertEquals(1, broker.submissions.get())
        assertEquals(IqArmedSignal.EXECUTED, IqSignalLedger(kv).get(sig.id)?.state)
    }

    @Test fun `entregas duplicadas nao enviam mais de uma ordem`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        dispatch.wake(sig.id)
        clock.now = sig.targetOpenMs

        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))
        assertEquals(IqDispatchOutcome.DUPLICATE, dispatch.wake(sig.id))
        assertEquals(1, broker.submissions.get())
    }

    @Test fun `novo armamento no mesmo candle nao sobrescreve ordem executada`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        dispatch.wake(sig.id)
        clock.now = sig.targetOpenMs
        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))

        val secondArming = dispatch.arm(signal(id = "different-id-same-candle"))

        assertEquals(IqArmedSignal.EXECUTED, secondArming.state)
        assertEquals(IqDispatchOutcome.DUPLICATE, dispatch.wake(sig.id))
        assertEquals(1, broker.submissions.get())
    }

    @Test fun `novo armamento no mesmo candle nao remove cancelamento explicito`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, FakeClock(sig.targetOpenMs - 20_000L), alarms, broker)
        dispatch.arm(sig)
        assertTrue(dispatch.cancel(sig.id))

        val secondArming = dispatch.arm(signal(id = "different-id-same-candle"))

        assertEquals(IqArmedSignal.CANCELLED, secondArming.state)
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `segundo sinal do mesmo candle nao e inserido no ledger`() = runBlocking {
        val sig = signal()
        val duplicate = signal(id = "another-id-same-target")
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        assertEquals(sig.id, dispatch.arm(duplicate).id)
        dispatch.wake(sig.id)
        clock.now = sig.targetOpenMs

        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))
        assertEquals(IqDispatchOutcome.NOT_FOUND, dispatch.wake(duplicate.id))
        assertEquals(1, broker.submissions.get())
    }

    @Test fun `candles stale na revalidacao bloqueiam envio`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort().apply { revalidationFailure = "candle stale" }
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
        assertTrue(store.get(sig.id)?.detail?.contains("candle stale") == true)
    }

    @Test fun `cotacao stale no gate final bloqueia envio`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort().apply { submissionResult = IqSubmissionResult.Rejected("cotação stale") }
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        assertEquals(IqDispatchOutcome.VALIDATED, dispatch.wake(sig.id))
        clock.now = sig.targetOpenMs

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(1, broker.submissions.get())
        assertEquals(IqArmedSignal.EXPIRED, store.get(sig.id)?.state)
    }

    @Test fun `janela T ultrapassada nunca envia ordem atrasada`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        dispatch.wake(sig.id)
        clock.now = sig.entryDeadlineMs + 1L

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `cancelamento explicito remove apenas sinal ainda armado`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 20_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)

        assertTrue(dispatch.cancel(sig.id))
        assertEquals(IqDispatchOutcome.CANCELLED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
        assertTrue(alarms.cancelled.contains(sig.id))
    }

    @Test fun `cancelamento durante revalidacao nao e sobrescrito por VALIDATED`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        broker.duringRevalidation = { assertTrue(dispatch.cancel(sig.id)) }

        assertEquals(IqDispatchOutcome.CANCELLED, dispatch.wake(sig.id))
        assertEquals(IqArmedSignal.CANCELLED, store.get(sig.id)?.state)
        clock.now = sig.targetOpenMs
        assertEquals(IqDispatchOutcome.CANCELLED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `falta de permissao de alarme exato falha fechada`() {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val alarms = FakeAlarms(permission = false)
        val broker = FakePort()
        val dispatch = dispatcher(store, FakeClock(sig.targetOpenMs - 10_000L), alarms, broker)

        assertEquals(IqArmedSignal.EXPIRED, dispatch.arm(sig).state)
        assertNull(store.get(sig.id)?.prevalidatedAtMs)
        assertTrue(alarms.scheduled.isEmpty())
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `revogacao da permissao depois do armamento expira sem enviar`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val alarms = FakeAlarms()
        val broker = FakePort()
        val clock = FakeClock(sig.targetOpenMs - 10_000L)
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        alarms.permission = false

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `ordem em estado ambiguo nao pode ser cancelada nem reenviada`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv()).apply { put(sig.copy(state = IqArmedSignal.SUBMITTING)) }
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, FakeClock(sig.targetOpenMs), alarms, broker)

        assertFalse(dispatch.cancel(sig.id))
        assertEquals(IqDispatchOutcome.UNKNOWN, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }
}
