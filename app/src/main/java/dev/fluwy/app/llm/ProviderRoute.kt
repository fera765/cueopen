package dev.fluwy.app.llm

import dev.fluwy.app.data.Settings

/**
 * Roteamento entre provedores (como os "combos" de gateways tipo OmniRoute): um modelo pode apontar para outro
 * provedor conectado com o formato "<id do provedor>::<modelo>", ex.: "groq::llama-3.3-70b-versatile".
 * Sem prefixo, usa o provedor ativo. Assim o Modelo alternativo pode ficar num provedor diferente do principal.
 */
object ProviderRoute {
    const val SEP = "::"

    fun encode(providerId: String, model: String): String = if (providerId.isBlank()) model else "$providerId$SEP$model"

    /** (id do provedor ou null, modelo puro). */
    fun split(model: String): Pair<String?, String> {
        val i = model.indexOf(SEP)
        if (i <= 0) return null to model
        val id = model.substring(0, i)
        return if (ProviderCatalog.byId(id) != null || id == "custom") id to model.substring(i + SEP.length) else null to model
    }

    /** Settings com a Base URL/chave do provedor do [model] e o nome puro do modelo. */
    fun route(s: Settings, model: String): Pair<Settings, String> {
        val (id, bare) = split(model)
        if (id == null || id == ProviderCatalog.activeId(s)) return s to bare
        val saved = s.savedProviders[id]
        val base = saved?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ProviderCatalog.byId(id)?.baseUrl ?: return s to bare
        return s.copy(baseUrl = base, apiKey = saved?.getOrNull(0).orEmpty()) to bare
    }

    /** Rótulo legível: "Groq · llama-3.3" ou só o modelo. */
    fun label(model: String): String {
        val (id, bare) = split(model)
        return if (id == null) bare else (ProviderCatalog.byId(id)?.name ?: "Personalizado") + " · " + bare
    }
}
