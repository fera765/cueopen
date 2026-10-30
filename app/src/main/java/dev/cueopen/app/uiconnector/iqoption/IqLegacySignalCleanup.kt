package dev.cueopen.app.uiconnector.iqoption

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Cancela alarmes da versão anterior e remove somente o ledger de sinais obsoleto. */
object IqLegacySignalCleanup {
    private const val PREFS = "iq_state"
    private const val RECEIVER = "dev.cueopen.app.uiconnector.iqoption.IqSignalAlarmReceiver"
    private const val ACTION = "dev.cueopen.IQ_SIGNAL_DISPATCH"
    private const val EXTRA_ID = "signal_id"

    fun cancelAndClear(context: Context): Int {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = IqLegacySignalLedger.ids(prefs.getString(IqLegacySignalLedger.KEY, null))
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        ids.forEach { id ->
            val intent = Intent().setClassName(app, RECEIVER)
                .setAction(ACTION)
                .setData(Uri.parse("cueopen://iqoption/signal/${Uri.encode(id)}"))
                .putExtra(EXTRA_ID, id)
            val pending = PendingIntent.getBroadcast(
                app, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms?.cancel(pending)
            pending.cancel()
        }
        prefs.edit().remove(IqLegacySignalLedger.KEY).commit()
        return ids.size
    }
}
