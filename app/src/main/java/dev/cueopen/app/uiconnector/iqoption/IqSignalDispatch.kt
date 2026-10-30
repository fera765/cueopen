package dev.cueopen.app.uiconnector.iqoption

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class IqArmedSignal(
    val id: String,
    val asset: String,
    val activeId: Int,
    val direction: String,
    val amount: Double,
    val minutes: Int,
    val targetOpenSec: Long,
    val timeframeSeconds: Int,
    val entryWindowSeconds: Int,
    val armedAtMs: Long,
    val state: String = ARMED,
    val prevalidatedAtMs: Long? = null,
    val detail: String = "",
) {
    companion object {
        const val ARMED = "ARMED"
        const val VALIDATED = "VALIDATED"
        const val SUBMITTING = "SUBMITTING"
        const val EXECUTED = "EXECUTED"
        const val EXPIRED = "EXPIRED"
        const val CANCELLED = "CANCELLED"
        const val FAILED = "FAILED"
        const val INVALIDATED = "INVALIDATED"

        val ACTIVE_STATES = setOf(ARMED, VALIDATED)
        val TERMINAL_STATES = setOf(EXECUTED, EXPIRED, CANCELLED, FAILED, INVALIDATED)
    }

    val targetOpenMs: Long get() = targetOpenSec * 1_000L
    val entryDeadlineMs: Long get() = targetOpenMs + entryWindowSeconds.coerceAtLeast(0) * 1_000L
}

@Serializable
private data class IqSignalLedgerSnapshot(val version: Int = 1, val records: List<IqArmedSignal> = emptyList())

/** Operações de ledger que precisam ser atômicas, inclusive entre alarmes duplicados. */
interface IqSignalStore {
    fun all(): List<IqArmedSignal>
    fun get(id: String): IqArmedSignal?
    fun put(signal: IqArmedSignal)
    /** Insert-only: preserva o estado terminal e impede mais de um sinal por candle. */
    fun putIfNoSignalForTarget(signal: IqArmedSignal): Boolean
    fun update(id: String, transform: (IqArmedSignal) -> IqArmedSignal): IqArmedSignal?
    /** Grava SUBMITTING antes do primeiro envio; bloqueia uma segunda intenção para o mesmo candle. */
    fun claimSubmission(id: String): Boolean
}

class InMemoryIqSignalStore : IqSignalStore {
    private val lock = Any()
    private val rows = linkedMapOf<String, IqArmedSignal>()
    override fun all(): List<IqArmedSignal> = synchronized(lock) { rows.values.toList() }
    override fun get(id: String): IqArmedSignal? = synchronized(lock) { rows[id] }
    override fun put(signal: IqArmedSignal) = synchronized(lock) { rows[signal.id] = signal }
    override fun putIfNoSignalForTarget(signal: IqArmedSignal): Boolean = synchronized(lock) {
        if (rows.containsKey(signal.id) || rows.values.any { it.targetOpenSec == signal.targetOpenSec }) return@synchronized false
        rows[signal.id] = signal
        true
    }
    override fun update(id: String, transform: (IqArmedSignal) -> IqArmedSignal): IqArmedSignal? = synchronized(lock) {
        val current = rows[id] ?: return@synchronized null
        transform(current).also { rows[id] = it }
    }
    override fun claimSubmission(id: String): Boolean = synchronized(lock) {
        val current = rows[id] ?: return@synchronized false
        if (current.state != IqArmedSignal.VALIDATED) return@synchronized false
        if (rows.values.any { it.id != id && it.targetOpenSec == current.targetOpenSec && it.state in setOf(IqArmedSignal.SUBMITTING, IqArmedSignal.EXECUTED) })
            return@synchronized false
        rows[id] = current.copy(state = IqArmedSignal.SUBMITTING, detail = "Envio iniciado; não repetir automaticamente")
        true
    }
}

/** Ledger pequeno versionado no KvStore já usado pelo conector. O adaptador Android usa commit síncrono. */
class IqSignalLedger(private val kv: KvStore) : IqSignalStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun loadUnsafe(): MutableList<IqArmedSignal> =
        runCatching {
            val raw = kv.get(KEY) ?: return@runCatching mutableListOf()
            json.decodeFromString(IqSignalLedgerSnapshot.serializer(), raw).records.toMutableList()
        }.getOrDefault(mutableListOf())

    private fun saveUnsafe(rows: List<IqArmedSignal>) {
        kv.put(KEY, json.encodeToString(IqSignalLedgerSnapshot.serializer(), IqSignalLedgerSnapshot(records = rows)))
    }

    override fun all(): List<IqArmedSignal> = synchronized(lock) { loadUnsafe().toList() }
    override fun get(id: String): IqArmedSignal? = synchronized(lock) { loadUnsafe().firstOrNull { it.id == id } }

    override fun put(signal: IqArmedSignal) = synchronized(lock) {
        val rows = loadUnsafe()
        val i = rows.indexOfFirst { it.id == signal.id }
        if (i < 0) rows.add(signal) else rows[i] = signal
        saveUnsafe(rows)
    }

    override fun putIfNoSignalForTarget(signal: IqArmedSignal): Boolean = synchronized(lock) {
        val rows = loadUnsafe()
        if (rows.any { it.id == signal.id || it.targetOpenSec == signal.targetOpenSec }) return@synchronized false
        rows.add(signal)
        saveUnsafe(rows)
        true
    }

    override fun update(id: String, transform: (IqArmedSignal) -> IqArmedSignal): IqArmedSignal? = synchronized(lock) {
        val rows = loadUnsafe()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = transform(rows[index])
        rows[index] = updated
        saveUnsafe(rows)
        updated
    }

    override fun claimSubmission(id: String): Boolean = synchronized(lock) {
        val rows = loadUnsafe()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0 || rows[index].state != IqArmedSignal.VALIDATED) return@synchronized false
        val target = rows[index].targetOpenSec
        if (rows.any { it.id != id && it.targetOpenSec == target && it.state in setOf(IqArmedSignal.SUBMITTING, IqArmedSignal.EXECUTED) })
            return@synchronized false
        rows[index] = rows[index].copy(state = IqArmedSignal.SUBMITTING, detail = "Envio iniciado; não repetir automaticamente")
        saveUnsafe(rows)
        true
    }

    companion object { const val KEY = "iq_signal_dispatch_ledger_v1" }
}

fun interface IqDispatchClock {
    /** Deve devolver tempo epoch do servidor somente quando a sincronização estiver fresca. */
    fun serverNowMs(): Long?
}

interface IqDispatchAlarmPort {
    fun canScheduleExact(): Boolean
    fun schedule(signal: IqArmedSignal, atServerEpochMs: Long): Boolean
    fun cancel(signal: IqArmedSignal)
}

sealed interface IqSubmissionResult {
    data class Accepted(val acknowledgement: String) : IqSubmissionResult
    data class Rejected(val reason: String) : IqSubmissionResult
}

interface IqSignalDispatchPort {
    /** Revalida velas/estratégia e conexão em T−10. Null significa que todos os gates passaram. */
    suspend fun revalidate(signal: IqArmedSignal): String?
    /** Usa o mesmo caminho de buyReserved e seus gates finais; somente ACK inequívoco é Accepted. */
    suspend fun submit(signal: IqArmedSignal): IqSubmissionResult
}

enum class IqDispatchOutcome { WAITING, VALIDATED, EXECUTED, EXPIRED, CANCELLED, DUPLICATE, UNKNOWN, NOT_FOUND }

/**
 * Motor independente do loop/LLM do agente. Os alarmes o acordam em T−90, T−10 e em T; cada transição
 * persistida é reentrante e o claim SUBMITTING garante no máximo uma tentativa por candle.
 */
class IqOptionSignalDispatcher(
    private val store: IqSignalStore,
    private val clock: IqDispatchClock,
    private val alarms: IqDispatchAlarmPort,
    private val port: IqSignalDispatchPort,
) {
    private val mutex = Mutex()

    fun signals(): List<IqArmedSignal> = store.all()

    fun arm(signal: IqArmedSignal): IqArmedSignal {
        val armed = signal.copy(state = IqArmedSignal.ARMED, prevalidatedAtMs = null, detail = "Aguardando revalidação T−10")
        store.get(signal.id)?.let { return it }
        store.all().firstOrNull { it.targetOpenSec == signal.targetOpenSec }?.let { return it }
        if (!alarms.canScheduleExact()) {
            val expired = armed.copy(state = IqArmedSignal.EXPIRED, detail = "permissão de alarme exato ausente; nenhuma ordem")
            return if (store.putIfNoSignalForTarget(expired)) expired
                else store.get(signal.id) ?: store.all().first { it.targetOpenSec == signal.targetOpenSec }
        }
        if (!store.putIfNoSignalForTarget(armed))
            return store.get(signal.id) ?: store.all().first { it.targetOpenSec == signal.targetOpenSec }
        val scheduled = runCatching { alarms.schedule(armed, IqSignalTiming.preconnectAtMs(armed.targetOpenSec)) }.getOrDefault(false)
        if (!scheduled) return store.update(armed.id) {
            it.copy(state = IqArmedSignal.EXPIRED, detail = "não foi possível programar alarme exato; nenhuma ordem")
        } ?: armed.copy(state = IqArmedSignal.EXPIRED)
        return armed
    }

    suspend fun wake(id: String): IqDispatchOutcome = mutex.withLock {
        var signal = store.get(id) ?: return@withLock IqDispatchOutcome.NOT_FOUND
        when (signal.state) {
            IqArmedSignal.SUBMITTING -> return@withLock IqDispatchOutcome.UNKNOWN
            IqArmedSignal.EXECUTED -> return@withLock IqDispatchOutcome.DUPLICATE
            IqArmedSignal.CANCELLED -> return@withLock IqDispatchOutcome.CANCELLED
            IqArmedSignal.EXPIRED, IqArmedSignal.FAILED, IqArmedSignal.INVALIDATED -> return@withLock IqDispatchOutcome.EXPIRED
        }
        if (!alarms.canScheduleExact()) return@withLock expire(signal.id, "permissão de alarme exato revogada; nenhuma ordem")
        val now = clock.serverNowMs() ?: return@withLock expire(signal.id, "relógio de servidor indisponível/stale; nenhuma ordem")
        if (signal.state == IqArmedSignal.ARMED) {
            val revalidateAt = IqSignalTiming.revalidateAtMs(signal.targetOpenSec)
            if (now < revalidateAt) {
                if (!alarms.schedule(signal, revalidateAt)) return@withLock expire(signal.id, "falha ao reagendar T−10; nenhuma ordem")
                return@withLock IqDispatchOutcome.WAITING
            }
            if (now > revalidateAt + REVALIDATION_LATE_TOLERANCE_MS || now >= signal.targetOpenMs)
                return@withLock expire(signal.id, "T−10 atrasado; sem ordem")
            val failure = runCatching { port.revalidate(signal) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                "revalidação T−10 falhou: ${it.message ?: it.javaClass.simpleName}"
            }
            val completedAt = clock.serverNowMs()
            if (failure != null || completedAt == null || completedAt >= signal.targetOpenMs)
                return@withLock expire(signal.id, failure ?: "revalidação terminou tarde/relógio stale; nenhuma ordem")
            signal = store.update(signal.id) {
                if (it.state == IqArmedSignal.ARMED)
                    it.copy(state = IqArmedSignal.VALIDATED, prevalidatedAtMs = completedAt, detail = "Revalidado; aguardando T")
                else it
            } ?: return@withLock IqDispatchOutcome.NOT_FOUND
            if (signal.state == IqArmedSignal.CANCELLED) {
                alarms.cancel(signal)
                return@withLock IqDispatchOutcome.CANCELLED
            }
            if (signal.state != IqArmedSignal.VALIDATED) return@withLock IqDispatchOutcome.EXPIRED
            if (!alarms.schedule(signal, signal.targetOpenMs))
                return@withLock expire(signal.id, "falha ao programar alarme de execução; nenhuma ordem")
            val afterSchedule = store.get(signal.id) ?: return@withLock IqDispatchOutcome.NOT_FOUND
            if (afterSchedule.state != IqArmedSignal.VALIDATED) {
                alarms.cancel(afterSchedule)
                return@withLock when (afterSchedule.state) {
                    IqArmedSignal.CANCELLED -> IqDispatchOutcome.CANCELLED
                    IqArmedSignal.SUBMITTING -> IqDispatchOutcome.UNKNOWN
                    IqArmedSignal.EXECUTED -> IqDispatchOutcome.DUPLICATE
                    else -> IqDispatchOutcome.EXPIRED
                }
            }
            return@withLock IqDispatchOutcome.VALIDATED
        }
        if (signal.state != IqArmedSignal.VALIDATED) return@withLock IqDispatchOutcome.EXPIRED
        if (now < signal.targetOpenMs) {
            if (!alarms.schedule(signal, signal.targetOpenMs)) return@withLock expire(signal.id, "falha ao reagendar abertura; nenhuma ordem")
            return@withLock IqDispatchOutcome.WAITING
        }
        if (now > signal.entryDeadlineMs) return@withLock expire(signal.id, "janela de entrada terminou; nenhuma ordem atrasada")
        if (!store.claimSubmission(signal.id)) {
            val current = store.get(signal.id) ?: return@withLock IqDispatchOutcome.NOT_FOUND
            if (current.state == IqArmedSignal.VALIDATED) {
                val invalidated = store.update(signal.id) {
                    if (it.state == IqArmedSignal.VALIDATED)
                        it.copy(state = IqArmedSignal.INVALIDATED, detail = "outra intenção já foi enviada para este candle")
                    else it
                }
                invalidated?.let(alarms::cancel)
                return@withLock when (invalidated?.state) {
                    IqArmedSignal.CANCELLED -> IqDispatchOutcome.CANCELLED
                    IqArmedSignal.SUBMITTING -> IqDispatchOutcome.UNKNOWN
                    IqArmedSignal.EXECUTED, IqArmedSignal.INVALIDATED -> IqDispatchOutcome.DUPLICATE
                    IqArmedSignal.EXPIRED, IqArmedSignal.FAILED -> IqDispatchOutcome.EXPIRED
                    else -> IqDispatchOutcome.NOT_FOUND
                }
            }
            return@withLock when (current.state) {
                IqArmedSignal.CANCELLED -> IqDispatchOutcome.CANCELLED
                IqArmedSignal.SUBMITTING -> IqDispatchOutcome.UNKNOWN
                IqArmedSignal.EXECUTED -> IqDispatchOutcome.DUPLICATE
                IqArmedSignal.ARMED -> IqDispatchOutcome.WAITING
                else -> IqDispatchOutcome.EXPIRED
            }
        }
        signal = store.get(signal.id) ?: return@withLock IqDispatchOutcome.NOT_FOUND
        val sent = try { port.submit(signal) } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // Não sabemos se o broker recebeu a mensagem; preservamos SUBMITTING para impedir duplicação.
            store.update(signal.id) { it.copy(detail = "resultado de envio incerto; não repetir automaticamente") }
            return@withLock IqDispatchOutcome.UNKNOWN
        }
        when (sent) {
            is IqSubmissionResult.Accepted -> {
                store.update(signal.id) { it.copy(state = IqArmedSignal.EXECUTED, detail = sent.acknowledgement.take(240)) }
                IqDispatchOutcome.EXECUTED
            }
            is IqSubmissionResult.Rejected -> expire(signal.id, sent.reason, submissionRejected = true)
        }
    }

    fun cancel(id: String): Boolean {
        val changed = store.update(id) { current ->
            if (current.state in IqArmedSignal.ACTIVE_STATES)
                current.copy(state = IqArmedSignal.CANCELLED, detail = "cancelado explicitamente; nenhuma ordem")
            else current
        } ?: return false
        if (changed.state == IqArmedSignal.CANCELLED) {
            alarms.cancel(changed)
            return true
        }
        return false
    }

    /** Se o pré-aquecimento falhar, conserva o sinal e tenta novamente no T−10, nunca depois dele. O horário local só decide se ainda cabe reagendar; a ordem exige relógio fresco do servidor. */
    fun deferUntilRevalidation(id: String, nowEpochMs: Long, reason: String): Boolean {
        val current = store.get(id) ?: return false
        if (current.state != IqArmedSignal.ARMED) return false
        val retryAt = IqSignalTiming.revalidateAtMs(current.targetOpenSec)
        if (nowEpochMs >= retryAt || !alarms.canScheduleExact()) return false
        val updated = store.update(id) {
            if (it.state == IqArmedSignal.ARMED)
                it.copy(detail = "Pré-conexão falhou; tentará em T−10: ${reason.take(120)}")
            else it
        } ?: return false
        if (updated.state != IqArmedSignal.ARMED) return false
        if (runCatching { alarms.schedule(updated, retryAt) }.getOrDefault(false)) return true
        expire(id, "falha ao reagendar T−10 após pré-conexão; nenhuma ordem")
        return false
    }

    fun rescheduleAll() {
        for (signal in store.all().filter { it.state in IqArmedSignal.ACTIVE_STATES }) {
            if (!alarms.canScheduleExact()) {
                store.update(signal.id) { it.copy(state = IqArmedSignal.EXPIRED, detail = "permissão de alarme exato ausente após reinício; nenhuma ordem") }
                alarms.cancel(signal)
            } else {
                val next = if (signal.state == IqArmedSignal.ARMED) IqSignalTiming.preconnectAtMs(signal.targetOpenSec) else signal.targetOpenMs
                if (!alarms.schedule(signal, next))
                    store.update(signal.id) { it.copy(state = IqArmedSignal.EXPIRED, detail = "alarme exato não pôde ser restaurado; nenhuma ordem") }
            }
        }
    }

    fun failBeforeDispatch(id: String, reason: String) {
        store.update(id) { current ->
            if (current.state in IqArmedSignal.ACTIVE_STATES)
                current.copy(state = IqArmedSignal.EXPIRED, detail = reason.take(240))
            else current
        }?.let(alarms::cancel)
    }

    private fun expire(id: String, reason: String, submissionRejected: Boolean = false): IqDispatchOutcome {
        val updated = store.update(id) { current ->
            if (current.state in IqArmedSignal.ACTIVE_STATES || (submissionRejected && current.state == IqArmedSignal.SUBMITTING))
                current.copy(state = IqArmedSignal.EXPIRED, detail = reason.take(240))
            else current
        } ?: return IqDispatchOutcome.NOT_FOUND
        alarms.cancel(updated)
        return when (updated.state) {
            IqArmedSignal.CANCELLED -> IqDispatchOutcome.CANCELLED
            IqArmedSignal.SUBMITTING -> IqDispatchOutcome.UNKNOWN
            IqArmedSignal.EXECUTED -> IqDispatchOutcome.DUPLICATE
            else -> IqDispatchOutcome.EXPIRED
        }
    }

    companion object { const val REVALIDATION_LATE_TOLERANCE_MS = 5_000L }
}
