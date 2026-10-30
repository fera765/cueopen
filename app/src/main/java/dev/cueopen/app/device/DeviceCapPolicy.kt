package dev.cueopen.app.device

/**
 * Regras puras de autorização por agente. A autorização do sistema Android é global ao app;
 * esta política exige também o opt-in individual salvo no perfil do agente.
 */
object DeviceCapPolicy {
    const val BLUETOOTH = "bluetooth"
    const val CALENDAR = "calendar"
    const val CALL_LOG = "call_log"
    const val CONTACTS = "contacts"
    const val LOCATION = "location"
    const val SMS = "sms"
    const val MICROPHONE = "microphone"
    const val PHONE_CALLS = "phone_calls"
    const val PHONE_NOTIFICATIONS = "notif_read"
    const val POST_NOTIFICATIONS = "notif_post"
    const val OVERLAY = "overlay"
    const val SCREEN = "screen"
    const val BACKGROUND = "background"

    private val toolsByCapability = linkedMapOf(
        BLUETOOTH to setOf("device_bluetooth", "device_bluetooth_pair"),
        CALENDAR to setOf("device_calendar_read", "device_calendar_create"),
        CALL_LOG to setOf("device_call_log"),
        CONTACTS to setOf("device_contacts"),
        LOCATION to setOf("device_location"),
        SMS to setOf("device_sms_read", "device_sms_compose"),
        MICROPHONE to setOf("device_microphone"),
        PHONE_CALLS to setOf("device_dial"),
        PHONE_NOTIFICATIONS to setOf("device_notifications"),
        POST_NOTIFICATIONS to setOf("device_notify"),
        SCREEN to setOf("device_screenshot"),
        OVERLAY to setOf("device_overlay"),
        BACKGROUND to emptySet(),
    )

    /** Operações que leem dados protegidos ou exigem autorização atual do Android. */
    private val systemGatedTools = setOf(
        "device_bluetooth", "device_calendar_read", "device_call_log", "device_contacts",
        "device_location", "device_sms_read", "device_microphone", "device_notifications",
        "device_notify", "device_screenshot", "device_overlay",
    )

    val allTools: Set<String> = toolsByCapability.values.flatten().toSet()

    /** Leituras exigem opt-in do agente e grant Android; handoffs visíveis exigem só opt-in. */
    fun availableTools(agentCapabilities: Collection<String>, systemGrants: Collection<String>): Set<String> {
        val optedIn = agentCapabilities.toSet()
        val granted = systemGrants.toSet()
        return optedIn.flatMapTo(linkedSetOf()) { capability ->
            toolsByCapability[capability].orEmpty().filterTo(linkedSetOf()) { tool ->
                !requiresSystemGrant(tool) || capability in granted
            }
        }
    }

    fun requiredCapability(tool: String): String? =
        toolsByCapability.entries.firstOrNull { tool in it.value }?.key

    fun requiresSystemGrant(tool: String): Boolean = tool in systemGatedTools

    /** Bluetooth CONNECT é permissão runtime no Android 12+, independente do targetSdk legado do app. */
    fun bluetoothConnectRuntimePermissionRequired(sdkInt: Int): Boolean = sdkInt >= 31

    /** Instruções incluídas no prompt apenas para ferramentas que realmente estão no schema atual. */
    fun agentGuidance(availableTools: Collection<String>): List<String> {
        val tools = availableTools.toSet()
        if (tools.isEmpty()) return emptyList()
        return buildList {
            add("Ferramentas Android disponíveis neste agente: ${tools.sorted().joinToString(", ")}. Use a ferramenta adequada quando o pedido corresponder; nunca improvise acesso a uma capacidade ausente.")
            if ("device_contacts" in tools) add("Contatos: use device_contacts para localizar uma pessoa por nome ou número quando o usuário pedir. A busca é específica e limitada; se pedirem a agenda inteira, pergunte qual pessoa ou subconjunto. Retorne somente os dados necessários; não faça exportação/listagem em massa nem divulgue contatos sem relação com o pedido.")
            if ("device_notifications" in tools) add("Notificações: nunca use device_notifications para ler SMS nem contornar as regras de acesso à caixa de entrada; notificações do app padrão de SMS são ocultadas.")
            if ("device_sms_read" in tools) add("SMS: device_sms_read só pode ser usado quando este app for o padrão de SMS e o Android conceder READ_SMS. Nunca leia mensagens por notificações, arquivos ou outra via alternativa.")
            if ("device_sms_compose" in tools) add("SMS: device_sms_compose abre apenas um rascunho no app de mensagens; o usuário revisa e toca em Enviar. Nunca envie SMS diretamente.")
            if ("device_bluetooth" in tools) add("Bluetooth: device_bluetooth lista somente dispositivos já pareados e não inicia varredura.")
            if ("device_bluetooth_pair" in tools) add("Bluetooth: device_bluetooth_pair apenas abre as Configurações; pareamento e alterações ficam sob controle do usuário.")
        }
    }

    /** Migra apenas uma vez o consentimento que agentes autônomos já tinham antes desta capability. */
    fun migrateLegacyBackgroundConsent(
        agentCapabilities: Collection<String>,
        autonomous: Boolean,
        migrationCompleted: Boolean,
    ): List<String> = if (!migrationCompleted && autonomous)
        (agentCapabilities + BACKGROUND).distinct()
    else agentCapabilities.toList()

    /** Estas ferramentas somente abrem uma interface visível do sistema; não executam a ação final. */
    fun requiresUserHandoff(tool: String): Boolean = tool in setOf(
        "device_dial", "device_sms_compose", "device_calendar_create", "device_bluetooth_pair", "device_microphone"
    )
}
