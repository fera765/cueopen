package dev.fluwy.app.agent

import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Uma chamada de ferramenta em execução: argumentos já validados, contexto da sessão e o passo
 * mostrado na UI. As famílias de ferramentas (`walletTools`, `browserTools`…) recebem este objeto.
 */
internal class ToolInvocation(
    val sessionId: String,
    val call: ToolCall,
    val a: JsonObject,
    val cwd: String,
    val step: ChatItem.Step,
    val title: String,
    val kind: Activity.Kind,
    val executionId: String,
    val model: String,
    val visualInspectionUsed: MutableSet<String>,
    val accessibilityReadScreenCompleted: MutableSet<String>,
) {
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun i(k: String) = a[k]?.jsonPrimitive?.intOrNull
    fun b(k: String) = a[k]?.jsonPrimitive?.booleanOrNull ?: false
    fun sl(k: String): List<String> = runCatching {
        (a[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** Rede salva da carteira (fail-closed: nunca roteia silenciosamente para a Mainnet). */
internal fun AgentRunner.walletNetwork(): SolanaNetwork? = runCatching {
    val stored = ctx.applicationContext
        .getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE)
        .getString("network", null) ?: return null
    SolanaNetwork.valueOf(stored)
}.getOrNull()
