package dev.cueopen.app.device

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Operações locais pontuais. Nada é salvo pelo app além do resultado entregue ao agente na sessão. */
object DeviceActions {
    private fun requireGrant(ctx: Context, id: String) {
        check(DeviceCaps.granted(ctx, id)) { deniedMessage(id) }
    }

    private fun deniedMessage(id: String) = when (id) {
        DeviceCaps.CALL_LOG -> "O acesso ao histórico de ligações não está concedido pelo Android ou é restrito pela função/política do dispositivo."
        else -> "A permissão Android para '${DeviceCaps.all.firstOrNull { it.id == id }?.title ?: id}' foi revogada ou ainda não foi concedida. Habilite-a no perfil deste agente e nas Configurações do Android."
    }

    fun calendar(ctx: Context, days: Int = 7): String {
        requireGrant(ctx, DeviceCaps.CALENDAR)
        val start = System.currentTimeMillis()
        val end = start + days.coerceIn(1, 30) * 24L * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath(end.toString()).build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val cursor = ctx.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?: return "O provedor de calendário não retornou dados."
        val fmt = SimpleDateFormat("EEE dd/MM HH:mm", Locale.getDefault())
        return cursor.use { c ->
            val title = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val begin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val finish = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val location = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val rows = mutableListOf<String>()
            while (c.moveToNext() && rows.size < 40) {
                rows += "${fmt.format(Date(c.getLong(begin)))}–${fmt.format(Date(c.getLong(finish)))} · ${c.getString(title).orEmpty().ifBlank { "(sem título)" }}" +
                    c.getString(location)?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            }
            rows.ifEmpty { listOf("Nenhum evento encontrado nos próximos ${days.coerceIn(1, 30)} dias.") }.joinToString("\n")
        }
    }

    fun createCalendarEvent(ctx: Context, title: String, startMillis: Long, endMillis: Long, description: String, location: String): String {
        require(title.isNotBlank()) { "Informe o título do evento." }
        require(endMillis > startMillis) { "O fim do evento deve ser posterior ao início." }
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title.take(160))
            .putExtra(CalendarContract.Events.DESCRIPTION, description.take(500))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location.take(160))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        launch(ctx, intent)
        return "Editor de calendário aberto com um rascunho. Revise e salve no Android; nenhum evento foi criado automaticamente."
    }

    fun contacts(ctx: Context, query: String = ""): String {
        requireGrant(ctx, DeviceCaps.CONTACTS)
        val term = DeviceContactsPolicy.normalizeQuery(query)
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\'"
        val pattern = DeviceContactsPolicy.likePattern(term)
        val args = arrayOf(pattern, pattern)
        val cursor = ctx.contentResolver.query(uri, projection, selection, args, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
            ?: return "O provedor de contatos não retornou dados."
        return cursor.use { c ->
            val name = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val number = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val rows = mutableListOf<String>()
            while (c.moveToNext() && rows.size < DeviceContactsPolicy.MAX_RESULTS)
                rows += "${c.getString(name).orEmpty()} · ${c.getString(number).orEmpty()}"
            rows.ifEmpty { listOf("Nenhum contato correspondente foi encontrado.") }.joinToString("\n")
        }
    }

    fun callLog(ctx: Context, limit: Int = 20): String {
        requireGrant(ctx, DeviceCaps.CALL_LOG)
        val projection = arrayOf(CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION)
        val cursor = ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")
            ?: return "O provedor de chamadas não retornou dados."
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        return cursor.use { c ->
            val date = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val number = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val type = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val duration = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val rows = mutableListOf<String>()
            while (c.moveToNext() && rows.size < limit.coerceIn(1, 50)) {
                val kind = when (c.getInt(type)) {
                    CallLog.Calls.INCOMING_TYPE -> "recebida"
                    CallLog.Calls.OUTGOING_TYPE -> "realizada"
                    CallLog.Calls.MISSED_TYPE -> "perdida"
                    CallLog.Calls.REJECTED_TYPE -> "recusada"
                    else -> "outra"
                }
                rows += "${fmt.format(Date(c.getLong(date)))} · ${c.getString(number).orEmpty()} · $kind · ${c.getLong(duration)} s"
            }
            rows.ifEmpty { listOf("Nenhum registro de chamada encontrado.") }.joinToString("\n")
        }
    }

    fun location(ctx: Context): String {
        requireGrant(ctx, DeviceCaps.LOCATION)
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = runCatching {
            manager.getProviders(true).asSequence().mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        }.getOrNull() ?: return "Não há localização conhecida disponível. O app não iniciou rastreamento contínuo."
        val ageMinutes = ((System.currentTimeMillis() - location.time).coerceAtLeast(0) / 60_000)
        return "Última localização conhecida: latitude ${location.latitude}, longitude ${location.longitude}; precisão aproximada ${location.accuracy.toInt()} m; há ${ageMinutes} min. Não é uma posição em tempo real."
    }

    @Suppress("DEPRECATION")
    fun bluetoothDevices(ctx: Context): String {
        requireGrant(ctx, DeviceCaps.BLUETOOTH)
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Este aparelho não possui Bluetooth."
        if (!adapter.isEnabled) return "Bluetooth está desligado. Use device_bluetooth_pair para abrir as Configurações e ativá-lo manualmente."
        val devices = adapter.bondedDevices.orEmpty().take(50)
        return devices.map { "${runCatching { it.name }.getOrNull().orEmpty().ifBlank { "Dispositivo" }} · ${it.address}" }
            .ifEmpty { listOf("Nenhum dispositivo pareado. Use device_bluetooth_pair para abrir as Configurações do Android.") }
            .joinToString("\n")
    }

    fun openDialer(ctx: Context, phone: String): String {
        val number = phone.filter { it.isDigit() || it in "+*#,;" }.take(40)
        require(number.isNotBlank()) { "Informe um número de telefone válido." }
        launch(ctx, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        return "Discador aberto. Revise o número e toque em Ligar no Android; o Cue não iniciou a chamada."
    }

    fun openBluetoothSettings(ctx: Context): String {
        launch(ctx, Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        return "Configurações de Bluetooth abertas. Pareie ou altere dispositivos manualmente no Android."
    }

    suspend fun microphone(ctx: Context): String {
        requireGrant(ctx, DeviceCaps.MICROPHONE)
        return DeviceVoiceInput.recognize(ctx)
    }

    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        check(intent.resolveActivity(ctx.packageManager) != null) { "Não há um app do sistema compatível com esta ação." }
        ctx.startActivity(intent)
    }
}
