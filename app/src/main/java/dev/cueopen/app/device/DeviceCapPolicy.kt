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

    /** Ferramentas disponíveis apenas se o agente pediu a capability e o sistema ainda a concede. */
    fun availableTools(agentCapabilities: Collection<String>, systemGrants: Collection<String>): Set<String> {
        val enabled = agentCapabilities.toSet().intersect(systemGrants.toSet())
        return enabled.flatMapTo(linkedSetOf()) { toolsByCapability[it].orEmpty() }
    }

    fun requiredCapability(tool: String): String? =
        toolsByCapability.entries.firstOrNull { tool in it.value }?.key

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
