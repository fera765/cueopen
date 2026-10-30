package dev.cueopen.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.app.role.RoleManager
import androidx.compose.runtime.mutableStateListOf
import dev.cueopen.app.data.now
import kotlinx.serialization.Serializable

/** Capacidades de aparelho configuradas individualmente em cada perfil de agente. */
object DeviceCaps {
    data class Cap(val id: String, val title: String, val desc: String)

    const val BLUETOOTH = DeviceCapPolicy.BLUETOOTH
    const val CALENDAR = DeviceCapPolicy.CALENDAR
    const val CALL_LOG = DeviceCapPolicy.CALL_LOG
    const val CONTACTS = DeviceCapPolicy.CONTACTS
    const val LOCATION = DeviceCapPolicy.LOCATION
    const val SMS = DeviceCapPolicy.SMS
    const val MICROPHONE = DeviceCapPolicy.MICROPHONE
    const val PHONE_CALLS = DeviceCapPolicy.PHONE_CALLS
    const val NOTIF_READ = DeviceCapPolicy.PHONE_NOTIFICATIONS
    const val NOTIF_POST = DeviceCapPolicy.POST_NOTIFICATIONS
    const val OVERLAY = DeviceCapPolicy.OVERLAY
    const val SCREEN = DeviceCapPolicy.SCREEN
    const val BACKGROUND = DeviceCapPolicy.BACKGROUND

    val all = listOf(
        Cap(BLUETOOTH, "Bluetooth / dispositivos próximos", "Ver dispositivos já pareados. Para parear outro, o Android abre as Configurações e você confirma lá."),
        Cap(CALENDAR, "Calendário", "Ler próximos eventos; criar evento abre o editor do Android para você revisar e salvar."),
        Cap(CALL_LOG, "Histórico de ligações", "Ler registros recentes de chamadas. O Android pode restringir esta permissão por função/política."),
        Cap(CONTACTS, "Contatos", "Pesquisar nomes e telefones quando o agente pedir. Não copiamos a agenda para o armazenamento do app."),
        Cap(LOCATION, "Localização", "Consultar a última localização conhecida quando solicitada; não fica monitorando em segundo plano."),
        Cap(SMS, "Mensagens / SMS", "Ler mensagens somente se o Android permitir e o app for o padrão de SMS. Enviar abre o app de mensagens; você revisa e toca em Enviar."),
        Cap(MICROPHONE, "Microfone", "Entrada de voz somente após abrir a interface de reconhecimento do Android; sem gravação silenciosa ou contínua."),
        Cap(PHONE_CALLS, "Fazer ligações", "Abre o discador do Android com o número. Você revisa e toca em Ligar; o agente não inicia chamadas sozinho."),
        Cap(NOTIF_READ, "Notificações do telefone", "Ler notificações recentes requer habilitar o Acesso a notificações nas Configurações do Android."),
        Cap(BACKGROUND, "Executar em segundo plano", "Autoriza este agente a usar os ciclos em segundo plano existentes. O serviço usa notificação persistente; o Android pode limitar ou interromper a execução."),
        Cap(NOTIF_POST, "Enviar notificações", "Permitir que o agente mostre avisos na barra de status."),
        Cap(OVERLAY, "Sobrepor à tela", "Mostrar um balão flutuante; exige autorização especial nas Configurações."),
        Cap(SCREEN, "Capturar a tela", "Captura pontual após a confirmação do diálogo de projeção do Android."),
    )

    private fun hasPermission(ctx: Context, permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun usesNearbyRuntimePermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ctx.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.S

    /** Permissões de runtime que precisam ser solicitadas por uma Activity/Compose launcher. */
    fun runtimePermissions(ctx: Context, id: String): List<String> = when (id) {
        BLUETOOTH -> if (usesNearbyRuntimePermission(ctx)) listOf(android.Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        CALENDAR -> listOf(android.Manifest.permission.READ_CALENDAR)
        CALL_LOG -> listOf(android.Manifest.permission.READ_CALL_LOG)
        CONTACTS -> listOf(android.Manifest.permission.READ_CONTACTS)
        LOCATION -> listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)
        SMS -> if (hasSmsRole(ctx)) listOf(android.Manifest.permission.READ_SMS) else emptyList()
        MICROPHONE -> listOf(android.Manifest.permission.RECORD_AUDIO)
        NOTIF_POST -> if (Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        else -> emptyList()
    }

    fun hasSmsRole(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = ctx.getSystemService(RoleManager::class.java)
            roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true && roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
        }
    }.getOrDefault(false)

    /** Revalida permissões do sistema em tempo real; não confia apenas no consentimento persistido do agente. */
    fun granted(ctx: Context, id: String): Boolean = when (id) {
        BLUETOOTH -> if (usesNearbyRuntimePermission(ctx)) hasPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
            else hasPermission(ctx, android.Manifest.permission.BLUETOOTH)
        CALENDAR -> hasPermission(ctx, android.Manifest.permission.READ_CALENDAR)
        CALL_LOG -> hasPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
        CONTACTS -> hasPermission(ctx, android.Manifest.permission.READ_CONTACTS)
        LOCATION -> hasPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
        SMS -> hasSmsRole(ctx) && hasPermission(ctx, android.Manifest.permission.READ_SMS)
        MICROPHONE -> hasPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
        PHONE_CALLS, BACKGROUND -> true // São consentimentos por agente, não permissões perigosas do Android.
        NOTIF_READ -> {
            val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            enabled.split(':').mapNotNull(ComponentName::unflattenFromString)
                .any { it.packageName == ctx.packageName && it.className == CueNotificationListener::class.java.name }
        }
        NOTIF_POST -> if (Build.VERSION.SDK_INT >= 33) hasPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) else true
        OVERLAY -> Settings.canDrawOverlays(ctx)
        SCREEN -> ScreenCapture.hasPermission()
        else -> false
    }

    fun status(ctx: Context, id: String): String = when {
        id == SMS && !hasSmsRole(ctx) -> "O Android exige que o app seja o padrão de SMS; esta função pode não estar disponível para este app."
        granted(ctx, id) && id == BACKGROUND -> "Autorizada para este agente. Toque para revisar otimização da bateria; o Android ainda pode interromper ciclos."
        granted(ctx, id) && id == PHONE_CALLS -> "Autorizada. Cada chamada abre o discador para sua confirmação."
        granted(ctx, id) && id == SMS -> "Autorizada. O envio continua manual no app de mensagens."
        granted(ctx, id) -> "Concedida no Android"
        runtimePermissions(ctx, id).isNotEmpty() -> "Toque para conceder na janela do Android →"
        id == BLUETOOTH -> "Permissão de Bluetooth indisponível; verifique a versão do Android."
        id == BACKGROUND -> "Ativa para este agente; o sistema pode impor limites de bateria."
        id == PHONE_CALLS -> "Cada chamada será confirmada no discador do Android."
        id == NOTIF_READ -> "Toque para habilitar em Acesso a notificações →"
        id == OVERLAY -> "Toque para autorizar nas Configurações →"
        id == SCREEN -> "Toque para iniciar a captura e confirmar no Android →"
        else -> "Toque para conceder no Android →"
    }

    /** Abre somente fluxos especiais do sistema; permissões perigosas usam o launcher runtime da UI. */
    fun request(ctx: Context, id: String) {
        val intent = when (id) {
            NOTIF_READ -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            NOTIF_POST -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            SCREEN -> Intent(ctx, ScreenCapture.RequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SMS -> smsRoleIntent(ctx) ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            BACKGROUND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            else -> return
        }
        runCatching {
            if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    private fun smsRoleIntent(ctx: Context): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = ctx.getSystemService(RoleManager::class.java) ?: return null
            if (!roles.isRoleAvailable(RoleManager.ROLE_SMS)) return null
            roles.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
        }
    }.getOrNull()
}

/** Notificações capturadas somente em memória (sem persistir o conteúdo sensível). */
@Serializable
data class CapturedNotif(val time: Long, val pkg: String, val app: String, val title: String, val text: String)

object NotifStore {
    val items = mutableStateListOf<CapturedNotif>()
    fun add(n: CapturedNotif) { items.add(0, n); while (items.size > 500) items.removeAt(items.size - 1) }
    fun recent(filter: String, limit: Int): List<CapturedNotif> =
        items.filter { filter.isBlank() || it.app.contains(filter, true) || it.pkg.contains(filter, true) || it.text.contains(filter, true) }.take(limit)
}
