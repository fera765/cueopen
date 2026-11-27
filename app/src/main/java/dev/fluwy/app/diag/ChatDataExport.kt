package dev.fluwy.app.diag

import android.content.Context
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Dados": JSON completo da interação dos chats para rastrear falhas — itens da conversa, mensagens enviadas ao modelo
 * (com tool calls e resultados), schemas das ferramentas, agentes, seções, times de build e o log de erros.
 * Fica de fora: configurações (chaves de API/provedores), cofre (cartões, Pix), carteira e logins salvos.
 */
object ChatDataExport {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun export(context: Context): File {
        val ctx = context.applicationContext
        val app = FluwyApp.instance
        val st = app.store.state.value
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val root = buildJsonObject {
            put("format", "fluwy-dados/1")
            put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            put("app", version ?: "?")
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            put("note", "Sem configurações, chaves de API, cofre, carteira ou logins salvos.")
            put("tools", runCatching { Json.parseToJsonElement(app.agent.toolSchemasForExport()) }.getOrElse { JsonPrimitive("erro ao gerar schemas: ${it.message}") })
            put("agents", JsonArray(st.agents.map { json.encodeToJsonElement(AgentProfile.serializer(), it) }))
            put("sections", JsonArray(st.sections.map { json.encodeToJsonElement(Section.serializer(), it) }))
            put("sessions", JsonArray(st.sessions.sortedBy { it.createdAt }.map { json.encodeToJsonElement(Session.serializer(), it) }))
            put("buildSquads", JsonArray(File(ctx.filesDir, "squads").listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.lastModified() }
                ?.mapNotNull { f -> runCatching { Json.parseToJsonElement(f.readText()) }.getOrNull() }.orEmpty()))
            put("errorLog", runCatching { ErrorLog.file(ctx).takeIf { it.exists() }?.readText()?.takeLast(400_000) }.getOrNull().orEmpty())
        }
        val out = File(ctx.filesDir, "fluwy-dados-chats.json")
        out.writeText(root.toString())
        return out
    }
}
