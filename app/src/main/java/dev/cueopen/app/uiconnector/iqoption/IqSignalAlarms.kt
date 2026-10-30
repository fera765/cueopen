package dev.cueopen.app.uiconnector.iqoption

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import dev.cueopen.app.CueApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Adapta o ledger do conector ao AlarmManager; falha fechada se alarmes exatos não estiverem disponíveis. */
object IqSignalAlarms {
    const val ACTION = "dev.cueopen.IQ_SIGNAL_DISPATCH"
    const val EXTRA_SIGNAL_ID = "signal_id"
    private const val NOTIFICATION_ID = 46_210

    fun store(context: Context): IqSignalLedger {
        val prefs = context.applicationContext.getSharedPreferences("iq_state", Context.MODE_PRIVATE)
        return IqSignalLedger(object : KvStore {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) {
                check(prefs.edit().putString(key, value).commit()) { "Não foi possível persistir o ledger de sinais." }
            }
        })
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun schedule(context: Context, signal: IqArmedSignal, atEpochMs: Long): Boolean {
        if (!canScheduleExact(context)) return false
        return runCatching {
            val alarm = context.getSystemService(AlarmManager::class.java)
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pendingIntent(context, signal.id))
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, signalId: String) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, signalId)) }
    }

    private fun pendingIntent(context: Context, signalId: String): PendingIntent {
        val intent = Intent(context, IqSignalAlarmReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("cueopen://iqoption/signal/${Uri.encode(signalId)}"))
            .putExtra(EXTRA_SIGNAL_ID, signalId)
        return PendingIntent.getBroadcast(
            context,
            signalId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** AlarmManager apaga alarmes no reboot; reconstroi somente os estados ainda executáveis. */
    fun rescheduleAll(context: Context) {
        val ledger = store(context)
        for (signal in ledger.all().filter { it.state in IqArmedSignal.ACTIVE_STATES }) {
            if (!canScheduleExact(context)) {
                ledger.update(signal.id) {
                    it.copy(state = IqArmedSignal.EXPIRED, detail = "permissão de alarme exato ausente após reinício; nenhuma ordem")
                }
                cancel(context, signal.id)
                continue
            }
            val whenMs = if (signal.state == IqArmedSignal.ARMED)
                IqSignalTiming.preconnectAtMs(signal.targetOpenSec) else signal.targetOpenMs
            if (!schedule(context, signal, whenMs)) {
                ledger.update(signal.id) {
                    it.copy(state = IqArmedSignal.EXPIRED, detail = "alarme exato não pôde ser restaurado; nenhuma ordem")
                }
            }
        }
    }

    fun expire(context: Context, signalId: String, reason: String) {
        val ledger = store(context)
        ledger.update(signalId) { current ->
            if (current.state in IqArmedSignal.ACTIVE_STATES)
                current.copy(state = IqArmedSignal.EXPIRED, detail = reason.take(240))
            else current
        }
        cancel(context, signalId)
    }

    /** RTC local só decide se ainda cabe reagendar; o dispatcher exige timeSync do servidor antes de qualquer ordem. */
    fun deferUntilRevalidation(context: Context, signalId: String, nowEpochMs: Long, reason: String): Boolean {
        val ledger = store(context)
        val current = ledger.get(signalId) ?: return false
        if (current.state != IqArmedSignal.ARMED) return false
        val stage = IqSignalTiming.nextRevalidationStageAfter(current.targetOpenSec, current.revalidationStage, nowEpochMs)
            ?: return false
        if (!canScheduleExact(context)) return false
        val retryAt = IqSignalTiming.revalidationStageAtMs(current.targetOpenSec, stage)
        val updated = ledger.update(signalId) {
            if (it.state == IqArmedSignal.ARMED)
                it.copy(revalidationStage = stage, detail = "Pré-conexão falhou; tentará em T−${IqSignalTiming.REVALIDATION_STAGE_LEADS_SECONDS[stage]}: ${reason.take(100)}")
            else it
        } ?: return false
        if (updated.state != IqArmedSignal.ARMED) return false
        if (schedule(context, updated, retryAt)) return true
        expire(context, signalId, "falha ao reagendar revalidação após pré-conexão; nenhuma ordem")
        return false
    }
}

class IqSignalAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IqSignalAlarms.ACTION) return
        val id = intent.getStringExtra(IqSignalAlarms.EXTRA_SIGNAL_ID) ?: return
        if (!IqSignalAlarms.canScheduleExact(context)) {
            IqSignalAlarms.expire(context, id, "permissão de alarme exato revogada; nenhuma ordem")
            return
        }
        runCatching {
            val service = Intent(context, IqSignalDispatchService::class.java)
                .setAction(IqSignalAlarms.ACTION)
                .putExtra(IqSignalAlarms.EXTRA_SIGNAL_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service) else context.startService(service)
        }.onFailure {
            val reason = "Android não permitiu iniciar o serviço de pré-conexão"
            if (!IqSignalAlarms.deferUntilRevalidation(context, id, System.currentTimeMillis(), reason))
                IqSignalAlarms.expire(context, id, "$reason; nenhuma ordem")
        }
    }
}

/** Serviço curto e visível: executa apenas revalidação/envio de um sinal já armado, nunca chama o LLM. */
class IqSignalDispatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val id = intent?.getStringExtra(IqSignalAlarms.EXTRA_SIGNAL_ID)
        if (id.isNullOrBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            dispatchMutex.withLock {
                try {
                    val app = applicationContext as CueApp
                    val connector = app.uiConnectors.get("iqoption") as? IqOptionConnector
                    if (connector == null) {
                        IqSignalAlarms.expire(this@IqSignalDispatchService, id, "conector IQ Option indisponível; nenhuma ordem")
                    } else if (!app.uiConnectors.enabled("iqoption")) {
                        connector.failSignalBeforeDispatch(id, "conector desativado pelo usuário; nenhuma ordem")
                    } else {
                        val prepared = withTimeoutOrNull(75_000L) {
                            val login = app.uiConnectors.ensureLoggedIn("iqoption")
                            if (login.isFailure) login else connector.prepareSignalDispatch()
                        }
                        if (prepared == null) {
                            val reason = "pré-conexão/sincronização excedeu 75 s"
                            if (!connector.deferSignalUntilRevalidation(id, System.currentTimeMillis(), reason))
                                connector.failSignalBeforeDispatch(id, "$reason; nenhuma ordem")
                        } else if (prepared.isFailure) {
                            val reason = "conexão/relógio da corretora indisponível: ${prepared.exceptionOrNull()?.message ?: "sincronização não confirmada"}"
                            if (!connector.deferSignalUntilRevalidation(id, System.currentTimeMillis(), reason))
                                connector.failSignalBeforeDispatch(id, "$reason; nenhuma ordem")
                        } else {
                            connector.dispatchSignalAlarm(id)
                        }
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    IqSignalAlarms.expire(this@IqSignalDispatchService, id, "falha do dispatcher: ${t.message ?: t.javaClass.simpleName}; nenhuma ordem")
                } finally {
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification = Notification.Builder(this, CueApp.CH_TASKS)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Cue · sinal IQ Option")
        .setContentText("Revalidando/encaminhando um sinal já armado")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val NOTIFICATION_ID = 46_210 }
}
