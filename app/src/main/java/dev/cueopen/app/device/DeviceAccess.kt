package dev.cueopen.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AppOpsManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableStateListOf
import dev.cueopen.app.data.now
import kotlinx.serialization.Serializable

/** Estado observável do Android, separado do consentimento persistido de cada agente. */
interface AndroidCapabilityState {
    fun permissionGranted(permission: String): Boolean
    fun appOpAllowed(operation: String): Boolean
    fun specialAccessGranted(capability: String): Boolean
}

private class RealAndroidCapabilityState(private val ctx: Context) : AndroidCapabilityState {
    override fun permissionGranted(permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun appOpAllowed(operation: String): Boolean = runCatching {
        val appOps = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(operation, android.os.Process.myUid(), ctx.packageName)
        mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
    }.getOrDefault(false)

    override fun specialAccessGranted(capability: String): Boolean = when (capability) {
        DeviceCaps.NOTIF_READ -> {
            val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            enabled.split(':').mapNotNull(ComponentName::unflattenFromString)
                .any { it.packageName == ctx.packageName && it.className == CueNotificationListener::class.java.name }
        }
        DeviceCaps.ACCESSIBILITY -> {
            val enabledServices = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val expected = ComponentName(ctx, FluwyAccessibilityService::class.java)
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                enabledServices.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
        }
        DeviceCaps.OVERLAY -> Settings.canDrawOverlays(ctx)
        DeviceCaps.SCREEN -> ScreenCapture.hasPermission()
        else -> true
    }
}

/** Capacidades de aparelho configuradas individualmente em cada perfil de agente. */
object DeviceCaps {
    data class Cap(val id: String, val title: String, val desc: String)

    const val BLUETOOTH = DeviceCapPolicy.BLUETOOTH
    const val CALENDAR = DeviceCapPolicy.CALENDAR
    const val CALL_LOG = DeviceCapPolicy.CALL_LOG
    const val CONTACTS = DeviceCapPolicy.CONTACTS
    const val LOCATION = DeviceCapPolicy.LOCATION
    const val MICROPHONE = DeviceCapPolicy.MICROPHONE
    const val PHONE_CALLS = DeviceCapPolicy.PHONE_CALLS
    const val NOTIF_READ = DeviceCapPolicy.PHONE_NOTIFICATIONS
    const val NOTIF_POST = DeviceCapPolicy.POST_NOTIFICATIONS
    const val OVERLAY = DeviceCapPolicy.OVERLAY
    const val SCREEN = DeviceCapPolicy.SCREEN
    const val BACKGROUND = DeviceCapPolicy.BACKGROUND
    const val ACCESSIBILITY = DeviceCapPolicy.ACCESSIBILITY

    val all = listOf(
        Cap(BLUETOOTH, "Bluetooth / dispositivos próximos", "Ver dispositivos já pareados. Para parear outro, o Android abre as Configurações e você confirma lá."),
        Cap(CALENDAR, "Calendário", "Ler próximos eventos; criar evento abre o editor do Android para você revisar e salvar."),
        Cap(CALL_LOG, "Histórico de ligações", "Ler registros recentes de chamadas. O Android pode restringir esta permissão por função/política."),
        Cap(CONTACTS, "Contatos", "Pesquisar nomes e telefones quando o agente pedir. Não copiamos a agenda para o armazenamento do app."),
        Cap(LOCATION, "Localização", "Consultar a última localização conhecida quando solicitada; não fica monitorando em segundo plano."),
        Cap(MICROPHONE, "Microfone", "Entrada de voz somente após abrir a interface de reconhecimento do Android; sem gravação silenciosa ou contínua."),
        Cap(PHONE_CALLS, "Fazer ligações", "Abre o discador do Android com o número. Você revisa e toca em Ligar; o agente não inicia chamadas sozinho."),
        Cap(NOTIF_READ, "Notificações do telefone", "Ler notificações recentes requer habilitar o Acesso a notificações nas Configurações do Android."),
        Cap(BACKGROUND, "Executar em segundo plano", "Autoriza este agente a usar os ciclos em segundo plano existentes. O serviço usa notificação persistente; o Android pode limitar ou interromper a execução."),
        Cap(NOTIF_POST, "Enviar notificações", "Permitir que o agente mostre avisos na barra de status."),
        Cap(OVERLAY, "Sobrepor à tela", "Mostrar um balão flutuante; exige autorização especial nas Configurações."),
        Cap(SCREEN, "Capturar a tela", "Captura pontual após a confirmação do diálogo de projeção do Android."),
        Cap(ACCESSIBILITY, "Navegar em outros apps", "Permite ler controles/textos visíveis e abrir, tocar, rolar e preencher campos em outros apps. O Android dá o serviço ao app inteiro; somente agentes com esta opção poderão solicitar ações. A árvore da tela é contexto transitório enviado ao modelo configurado, mas não é salva no histórico local. Somente se você pedir para ler/analisar um post e a árvore acessível não bastar, um único recorte temporário do elemento pode ser enviado a um modelo com visão; a captura e a árvore bruta não são salvas no histórico local."),
    )

    private fun hasPermission(state: AndroidCapabilityState, permission: String, appOp: String? = null): Boolean =
        state.permissionGranted(permission) && (appOp == null || state.appOpAllowed(appOp))

    private fun androidState(ctx: Context): AndroidCapabilityState = RealAndroidCapabilityState(ctx)

    private fun appOpFor(permission: String): String? = when (permission) {
        android.Manifest.permission.READ_CALENDAR -> "android:read_calendar"
        android.Manifest.permission.READ_CALL_LOG -> "android:read_call_log"
        android.Manifest.permission.READ_CONTACTS -> "android:read_contacts"
        android.Manifest.permission.ACCESS_COARSE_LOCATION -> "android:coarse_location"
        android.Manifest.permission.ACCESS_FINE_LOCATION -> "android:fine_location"
        android.Manifest.permission.RECORD_AUDIO -> "android:record_audio"
        android.Manifest.permission.BLUETOOTH_CONNECT -> "android:bluetooth_connect"
        android.Manifest.permission.POST_NOTIFICATIONS -> "android:post_notification"
        else -> null
    }

    private fun usesNearbyRuntimePermission(): Boolean =
        DeviceCapPolicy.bluetoothConnectRuntimePermissionRequired(Build.VERSION.SDK_INT)

    /** Permissões de runtime que precisam ser solicitadas por uma Activity/Compose launcher. */
    fun runtimePermissions(ctx: Context, id: String): List<String> = when (id) {
        BLUETOOTH -> if (usesNearbyRuntimePermission()) listOf(android.Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        CALENDAR -> listOf(android.Manifest.permission.READ_CALENDAR)
        CALL_LOG -> listOf(android.Manifest.permission.READ_CALL_LOG)
        CONTACTS -> listOf(android.Manifest.permission.READ_CONTACTS)
        LOCATION -> listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)
        MICROPHONE -> listOf(android.Manifest.permission.RECORD_AUDIO)
        NOTIF_POST -> if (Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        else -> emptyList()
    }

    /** Revalida permissões do sistema em tempo real; não confia apenas no consentimento persistido do agente. */
    fun granted(ctx: Context, id: String): Boolean = granted(androidState(ctx), id)

    /** Pure decision function used by JVM tests; it never mutates or revokes Android state. */
    fun granted(state: AndroidCapabilityState, id: String): Boolean = when (id) {
        BLUETOOTH -> if (usesNearbyRuntimePermission()) hasPermission(state, android.Manifest.permission.BLUETOOTH_CONNECT, appOpFor(android.Manifest.permission.BLUETOOTH_CONNECT))
            else hasPermission(state, android.Manifest.permission.BLUETOOTH, appOpFor(android.Manifest.permission.BLUETOOTH))
        CALENDAR -> hasPermission(state, android.Manifest.permission.READ_CALENDAR, appOpFor(android.Manifest.permission.READ_CALENDAR))
        CALL_LOG -> hasPermission(state, android.Manifest.permission.READ_CALL_LOG, appOpFor(android.Manifest.permission.READ_CALL_LOG))
        CONTACTS -> hasPermission(state, android.Manifest.permission.READ_CONTACTS, appOpFor(android.Manifest.permission.READ_CONTACTS))
        LOCATION -> (hasPermission(state, android.Manifest.permission.ACCESS_COARSE_LOCATION, appOpFor(android.Manifest.permission.ACCESS_COARSE_LOCATION)) ||
            hasPermission(state, android.Manifest.permission.ACCESS_FINE_LOCATION, appOpFor(android.Manifest.permission.ACCESS_FINE_LOCATION)))
        MICROPHONE -> hasPermission(state, android.Manifest.permission.RECORD_AUDIO, appOpFor(android.Manifest.permission.RECORD_AUDIO))
        PHONE_CALLS, BACKGROUND -> true // São consentimentos por agente, não permissões perigosas do Android.
        NOTIF_READ -> state.specialAccessGranted(NOTIF_READ)
        NOTIF_POST -> if (Build.VERSION.SDK_INT >= 33) hasPermission(state, android.Manifest.permission.POST_NOTIFICATIONS, appOpFor(android.Manifest.permission.POST_NOTIFICATIONS)) else true
        OVERLAY -> state.specialAccessGranted(OVERLAY)
        SCREEN -> state.specialAccessGranted(SCREEN)
        ACCESSIBILITY -> state.specialAccessGranted(ACCESSIBILITY)
        else -> false
    }

    /** O app está fora da otimização de bateria (Doze não adia os ciclos do agente). */
    fun batteryUnrestricted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            runCatching { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true)

    /** Abre direto o diálogo do Android "Permitir execução em segundo plano?" (um toque). */
    fun requestBatteryUnrestricted(ctx: Context) {
        if (batteryUnrestricted(ctx)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(direct) }.isSuccess
        if (!ok) runCatching {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Consentimento ligado mas ainda exige ação no Android (ex.: bateria otimizada). */
    fun needsAction(ctx: Context, id: String): Boolean =
        !granted(ctx, id) || (id == BACKGROUND && !batteryUnrestricted(ctx))

    fun status(ctx: Context, id: String): String = when {
        id == BLUETOOTH && usesNearbyRuntimePermission() && !granted(ctx, id) -> "Android 12+: conceda Dispositivos próximos (BLUETOOTH_CONNECT) na janela do Android."
        id == BACKGROUND && !batteryUnrestricted(ctx) -> "Ativa, mas o Android ainda otimiza a bateria do app e pode pausar os ciclos. Toque para liberar →"
        granted(ctx, id) && id == BACKGROUND -> "Autorizada e sem restrição de bateria. Alguns fabricantes ainda podem limitar apps em segundo plano."
        granted(ctx, id) && id == PHONE_CALLS -> "Autorizada. Cada chamada abre o discador para sua confirmação."
        granted(ctx, id) && id == ACCESSIBILITY -> "Serviço ativo no Android; o opt-in deste perfil limita o uso a este agente."
        granted(ctx, id) -> "Concedida no Android"
        runtimePermissions(ctx, id).isNotEmpty() -> "Toque para conceder na janela do Android →"
        id == BLUETOOTH -> "Permissão de Bluetooth indisponível; verifique a versão do Android."
        id == BACKGROUND -> "Ativa para este agente; o sistema pode impor limites de bateria."
        id == PHONE_CALLS -> "Cada chamada será confirmada no discador do Android."
        id == NOTIF_READ -> "Toque para habilitar em Acesso a notificações →"
        id == ACCESSIBILITY -> "Toque para abrir Configurações → Acessibilidade e ativar Fluwy manualmente →"
        id == OVERLAY -> "Toque para autorizar nas Configurações →"
        id == SCREEN -> "Toque para iniciar a captura e confirmar no Android →"
        else -> "Toque para conceder no Android →"
    }

    /** Abre somente fluxos especiais do sistema; permissões perigosas usam o launcher runtime da UI. */
    fun request(ctx: Context, id: String) {
        val intent = when (id) {
            NOTIF_READ -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            NOTIF_POST -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            SCREEN -> Intent(ctx, ScreenCapture.RequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            BACKGROUND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestBatteryUnrestricted(ctx); return
            } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            else -> {
                // Runtime permission já concedida, mas AppOp revogado: só a tela oficial
                // de detalhes do app pode permitir ao usuário revisar esse estado.
                if (runtimePermissions(ctx, id).isNotEmpty())
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                else return
            }
        }
        runCatching {
            if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

}

/** Notificações capturadas somente em memória (sem persistir o conteúdo sensível). */
@Serializable
data class CapturedNotif(
    val time: Long, val pkg: String, val app: String, val title: String, val text: String,
    /** Id curto para device_notification_reply (n1, n2…). */
    val id: String = "",
    /** A notificação traz o botão "Responder" do próprio app (RemoteInput). */
    val replyable: Boolean = false,
    /** Últimas mensagens da conversa (MessagingStyle/InboxStyle), já no formato "Remetente: texto". */
    val conversation: List<String> = emptyList(),
)

object NotifStore {
    val items = mutableStateListOf<CapturedNotif>()
    fun add(n: CapturedNotif) {
        // A mesma conversa atualiza a notificação a cada mensagem: mantém só a versão mais recente dela.
        if (n.id.isNotBlank()) items.removeAll { it.id == n.id }
        items.add(0, n); while (items.size > 500) items.removeAt(items.size - 1)
    }
    /** Registra a resposta enviada pelo agente na conversa capturada (para a próxima leitura já mostrá-la). */
    fun addOutgoing(id: String, text: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = items[idx].let { it.copy(conversation = (it.conversation + "Você (enviado pelo agente): $text").takeLast(12)) }
    }
    fun recent(filter: String, limit: Int): List<CapturedNotif> =
        items.filter { filter.isBlank() || it.app.contains(filter, true) || it.pkg.contains(filter, true) || it.text.contains(filter, true) }.take(limit)
}
