package dev.cueopen.app.uiconnector.iqoption

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Leitor transitório do ledger antigo, usado apenas para cancelar alarmes após atualização. */
object IqLegacySignalLedger {
    const val KEY = "iq_signal_dispatch_ledger_v1"

    fun ids(raw: String?): List<String> = runCatching {
        val root = Json.parseToJsonElement(raw ?: return emptyList()) as? JsonObject ?: return emptyList()
        val rows = root["records"] as? JsonArray ?: return emptyList()
        rows.mapNotNull { row ->
            runCatching { row.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }.getOrNull()
        }.distinct()
    }.getOrDefault(emptyList())
}
