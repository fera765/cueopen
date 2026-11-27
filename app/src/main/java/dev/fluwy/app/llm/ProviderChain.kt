package dev.fluwy.app.llm

import dev.fluwy.app.data.Settings

/**
 * Cadeia de provedores: o ativo é o principal; os de [Settings.fallbackProviders] entram em ordem, cada um com o modelo
 * que foi escolhido nele. Quando o principal volta a responder, ele reassume (ver ModelFallback).
 */
object ProviderChain {
    /** Modelo configurado num provedor (agente ou rápido), ou o padrão do preset. */
    fun modelOf(s: Settings, id: String, fast: Boolean = false): String {
        if (id == ProviderCatalog.activeId(s)) return if (fast) s.fastModel else s.model
        val saved = s.savedProviders[id].orEmpty()
        return (if (fast) saved.getOrNull(3) else saved.getOrNull(2))?.takeIf { it.isNotBlank() }
            ?: saved.getOrNull(2)?.takeIf { it.isNotBlank() } ?: ProviderCatalog.byId(id)?.defaultModel.orEmpty()
    }

    /** Lista "id::modelo,..." usada como alternativos do ModelFallback (inclui alternativos antigos por modelo). */
    fun fallback(s: Settings, fast: Boolean = false): String {
        val active = ProviderCatalog.activeId(s)
        val fromProviders = s.fallbackProviders.filter { it != active }.mapNotNull { id ->
            modelOf(s, id, fast).takeIf { it.isNotBlank() }?.let { ProviderRoute.encode(id, it) }
        }
        return (fromProviders + s.fallbackModel.split(',').map { it.trim() }.filter { it.isNotBlank() }).distinct().joinToString(",")
    }
}
