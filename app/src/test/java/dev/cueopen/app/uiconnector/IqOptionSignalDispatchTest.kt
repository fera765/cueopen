package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqArmedSignal
import dev.cueopen.app.uiconnector.iqoption.IqDispatchAlarmPort
import dev.cueopen.app.uiconnector.iqoption.IqDispatchClock
import dev.cueopen.app.uiconnector.iqoption.IqDispatchOutcome
import dev.cueopen.app.uiconnector.iqoption.IqOptionSignalDispatcher
import dev.cueopen.app.uiconnector.iqoption.IqSignalDispatchPort
import dev.cueopen.app.uiconnector.iqoption.IqSignalLedger
import dev.cueopen.app.uiconnector.iqoption.IqSignalTiming
import dev.cueopen.app.uiconnector.iqoption.IqSignalStore
import dev.cueopen.app.uiconnector.iqoption.IqSignalValidationResult
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
        override suspend fun revalidate(signal: IqArmedSignal): IqSignalValidationResult {
            validations.incrementAndGet()
            duringRevalidation?.invoke()
            return if (revalidationFailure != null) IqSignalValidationResult(failure = revalidationFailure)
            else IqSignalValidationResult(strategyFingerprint = "mock-strategy")
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

    private fun stageAt(signal: IqArmedSignal, stage: Int) =
        IqSignalTiming.revalidationStageAtMs(signal.targetOpenSec, stage)

    private fun dispatcher(
        store: IqSignalStore,
        clock: FakeClock,
        alarms: FakeAlarms,
        port: FakePort,
    ) = IqOptionSignalDispatcher(store, clock, alarms, port)

    @Test fun `alarme revalida e envia sem acordar agente`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0))
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

    @Test fun `sinal validado executa em T mais um mesmo sem novo ciclo do agente`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0))
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)
        assertEquals(IqDispatchOutcome.VALIDATED, dispatch.wake(sig.id))

        // O LLM/agente não participa do relógio: um alarme atrasado dentro da janela pode executar.
        clock.now = sig.targetOpenMs + 1_000L
        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))
        assertEquals(1, broker.submissions.get())
    }

    @Test fun `preconexao em T menos noventa agenda primeira revalidacao T menos cinquenta`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 90_000L)
        val alarms = FakeAlarms()
        val dispatch = dispatcher(store, clock, alarms, FakePort())
        dispatch.arm(sig)

        assertEquals(IqSignalTiming.preconnectAtMs(sig.targetOpenSec), alarms.scheduled.last().second)
        assertEquals(IqDispatchOutcome.WAITING, dispatch.wake(sig.id))
        assertEquals(stageAt(sig, 0), alarms.scheduled.last().second)
        clock.now = stageAt(sig, 0)
        assertEquals(IqDispatchOutcome.VALIDATED, dispatch.wake(sig.id))
    }

    @Test fun `falha no pre aquecimento escolhe proxima etapa futura`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val alarms = FakeAlarms()
        val dispatch = dispatcher(store, FakeClock(stageAt(sig, 0)), alarms, FakePort())
        dispatch.arm(sig)

        assertTrue(dispatch.deferUntilRevalidation(sig.id, sig.targetOpenMs - 30_000L, "connect timeout"))
        assertEquals(1, store.get(sig.id)?.revalidationStage)
        assertTrue(store.get(sig.id)?.detail?.contains("connect timeout") == true)
        assertEquals(stageAt(sig, 1), alarms.scheduled.last().second)
    }

    @Test fun `falhas de candle tentam no maximo T menos cinquenta vinte e cinco e dez`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0))
        val broker = FakePort().apply { revalidationFailure = "dois candles fechados indisponíveis" }
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)

        assertEquals(IqDispatchOutcome.WAITING, dispatch.wake(sig.id))
        assertEquals(1, store.get(sig.id)?.revalidationStage)
        clock.now = stageAt(sig, 1)
        assertEquals(IqDispatchOutcome.WAITING, dispatch.wake(sig.id))
        assertEquals(2, store.get(sig.id)?.revalidationStage)
        clock.now = stageAt(sig, 2)
        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))

        assertEquals(3, broker.validations.get())
        assertEquals(0, broker.submissions.get())
        assertEquals(IqArmedSignal.EXPIRED, store.get(sig.id)?.state)
        assertTrue(store.get(sig.id)?.detail?.contains("candles fechados indisponíveis") == true)
    }

    @Test fun `etapa perdida salta para proxima tentativa futura mas nao estende alem do prazo de entrada`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs - 30_000L)
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)

        assertEquals(IqDispatchOutcome.WAITING, dispatch.wake(sig.id))
        assertEquals(stageAt(sig, 1), alarms.scheduled.last().second)
        clock.now = stageAt(sig, 1)
        assertEquals(IqDispatchOutcome.VALIDATED, dispatch.wake(sig.id))
        clock.now = sig.entryDeadlineMs + 1L
        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `atraso de ate cinco segundos pode revalidar mas atraso maior salta etapa`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0) + 4_000L)
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)
        assertEquals(IqDispatchOutcome.VALIDATED, dispatch.wake(sig.id))
        assertEquals(1, broker.validations.get())

        val other = signal(target = sig.targetOpenSec + 300L, id = "late-stage")
        val otherClock = FakeClock(stageAt(other, 0) + 6_000L)
        val otherAlarms = FakeAlarms()
        val otherDispatch = dispatcher(IqSignalLedger(MemoryKv()), otherClock, otherAlarms, FakePort())
        otherDispatch.arm(other)
        assertEquals(IqDispatchOutcome.WAITING, otherDispatch.wake(other.id))
        assertEquals(stageAt(other, 1), otherAlarms.scheduled.last().second)
    }

    @Test fun `perda de todas as etapas expira sem ordem`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(sig.targetOpenMs + 6_000L)
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.validations.get())
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `relogio de servidor stale nunca valida nem envia`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val broker = FakePort()
        val dispatch = dispatcher(store, FakeClock(null), FakeAlarms(), broker)
        dispatch.arm(sig)

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.validations.get())
        assertEquals(0, broker.submissions.get())
        assertTrue(store.get(sig.id)?.detail?.contains("relógio de servidor") == true)
    }

    @Test fun `estado serializado sobrevive reinicio do processo`() = runBlocking {
        val sig = signal()
        val kv = MemoryKv()
        val clock = FakeClock(stageAt(sig, 0))
        val alarms = FakeAlarms()
        val broker = FakePort()
        dispatcher(IqSignalLedger(kv), clock, alarms, broker).arm(sig)

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
        val clock = FakeClock(stageAt(sig, 0))
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)
        dispatch.wake(sig.id)
        clock.now = sig.targetOpenMs

        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))
        assertEquals(IqDispatchOutcome.DUPLICATE, dispatch.wake(sig.id))
        assertEquals(1, broker.submissions.get())
    }

    @Test fun `segundo armamento no mesmo candle nao sobrescreve sinal terminal`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0))
        val dispatch = dispatcher(store, clock, FakeAlarms(), FakePort())
        dispatch.arm(sig)
        dispatch.wake(sig.id)
        clock.now = sig.targetOpenMs
        assertEquals(IqDispatchOutcome.EXECUTED, dispatch.wake(sig.id))

        val duplicate = dispatch.arm(signal(id = "different-id-same-candle"))
        assertEquals(IqArmedSignal.EXECUTED, duplicate.state)
        assertEquals(IqDispatchOutcome.DUPLICATE, dispatch.wake(sig.id))
    }

    @Test fun `cancelamento explicito nao e sobrescrito por novo armamento no candle`() = runBlocking {
        val sig = signal()
        val dispatch = dispatcher(IqSignalLedger(MemoryKv()), FakeClock(stageAt(sig, 0)), FakeAlarms(), FakePort())
        dispatch.arm(sig)
        assertTrue(dispatch.cancel(sig.id))
        assertEquals(IqArmedSignal.CANCELLED, dispatch.arm(signal(id = "different-id-same-candle")).state)
        assertEquals(0, dispatch.signals().count { it.state == IqArmedSignal.SUBMITTING })
    }

    @Test fun `segundo sinal do mesmo candle nao e inserido no ledger`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val dispatch = dispatcher(store, FakeClock(stageAt(sig, 0)), FakeAlarms(), FakePort())
        dispatch.arm(sig)
        val duplicate = dispatch.arm(signal(id = "another-id-same-target"))
        assertEquals(sig.id, duplicate.id)
        assertNull(store.get("another-id-same-target"))
    }

    @Test fun `cotacao stale no gate final bloqueia envio`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0))
        val broker = FakePort().apply { submissionResult = IqSubmissionResult.Rejected("cotação stale") }
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
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
        val clock = FakeClock(stageAt(sig, 0))
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)
        dispatch.wake(sig.id)
        clock.now = sig.entryDeadlineMs + 1L

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `cancelamento explicito remove apenas sinal ainda armado`() = runBlocking {
        val sig = signal()
        val alarms = FakeAlarms()
        val broker = FakePort()
        val dispatch = dispatcher(IqSignalLedger(MemoryKv()), FakeClock(stageAt(sig, 0)), alarms, broker)
        dispatch.arm(sig)

        assertTrue(dispatch.cancel(sig.id))
        assertEquals(IqDispatchOutcome.CANCELLED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
        assertTrue(alarms.cancelled.contains(sig.id))
    }

    @Test fun `cancelamento durante revalidacao nao e sobrescrito por VALIDATED`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val clock = FakeClock(stageAt(sig, 0))
        val broker = FakePort()
        val dispatch = dispatcher(store, clock, FakeAlarms(), broker)
        dispatch.arm(sig)
        broker.duringRevalidation = { assertTrue(dispatch.cancel(sig.id)) }

        assertEquals(IqDispatchOutcome.CANCELLED, dispatch.wake(sig.id))
        assertEquals(IqArmedSignal.CANCELLED, store.get(sig.id)?.state)
        clock.now = sig.targetOpenMs
        assertEquals(IqDispatchOutcome.CANCELLED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `limpar historico remove terminais e preserva sinais ativos e ambiguos`() {
        val store = IqSignalLedger(MemoryKv())
        val base = signal(target = 1_800_000_000L)
        val active = base.copy(state = IqArmedSignal.ARMED)
        val validated = base.copy(id = "validated", targetOpenSec = base.targetOpenSec + 60, state = IqArmedSignal.VALIDATED)
        val submitting = base.copy(id = "submitting", targetOpenSec = base.targetOpenSec + 120, state = IqArmedSignal.SUBMITTING)
        val terminalStates = listOf(IqArmedSignal.EXECUTED, IqArmedSignal.EXPIRED, IqArmedSignal.CANCELLED, IqArmedSignal.FAILED, IqArmedSignal.INVALIDATED)
        store.put(active)
        store.put(validated)
        store.put(submitting)
        terminalStates.forEachIndexed { index, state ->
            store.put(base.copy(id = "terminal-$index", targetOpenSec = base.targetOpenSec + 180 + index * 60L, state = state))
        }
        val dispatcher = dispatcher(store, FakeClock(base.targetOpenMs), FakeAlarms(), FakePort())

        assertEquals(terminalStates.size, dispatcher.clearTerminalSignals())
        assertEquals(setOf(active.id, validated.id, submitting.id), dispatcher.signals().map { it.id }.toSet())
    }

    @Test fun `falta de permissao de alarme exato falha fechada`() {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv())
        val alarms = FakeAlarms(permission = false)
        val broker = FakePort()
        val dispatch = dispatcher(store, FakeClock(stageAt(sig, 0)), alarms, broker)

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
        val clock = FakeClock(stageAt(sig, 0))
        val dispatch = dispatcher(store, clock, alarms, broker)
        dispatch.arm(sig)
        alarms.permission = false

        assertEquals(IqDispatchOutcome.EXPIRED, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }

    @Test fun `ordem em estado ambiguo nao pode ser cancelada nem reenviada`() = runBlocking {
        val sig = signal()
        val store = IqSignalLedger(MemoryKv()).apply { put(sig.copy(state = IqArmedSignal.SUBMITTING)) }
        val broker = FakePort()
        val dispatch = dispatcher(store, FakeClock(sig.targetOpenMs), FakeAlarms(), broker)

        assertFalse(dispatch.cancel(sig.id))
        assertEquals(IqDispatchOutcome.UNKNOWN, dispatch.wake(sig.id))
        assertEquals(0, broker.submissions.get())
    }
}
