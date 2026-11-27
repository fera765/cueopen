package dev.fluwy.app.prompts

/** Um prompt editável: [default] é o texto que vem com o app. */
data class PromptDef(val id: String, val group: String, val title: String, val description: String, val default: String) {
    /** Trecho de um prompt maior (id com ponto, ex.: `agent_system.3`). */
    val isFragment: Boolean get() = '.' in id
}

/** Catálogo de todos os prompts do app, na ordem em que aparecem em Configurações > Prompts. */
object PromptCatalog {
    val all: List<PromptDef> by lazy {
        listOf(DefaultsAgente.all, DefaultsMemoriaEContexto.all, DefaultsConectores.all, DefaultsFluxos.all, DefaultsVoz.all, DefaultsAgentesDoCatalogo.all, DefaultsBrowser.all).flatten()
    }
    private val index by lazy { all.associateBy { it.id } }

    fun byId(id: String): PromptDef? = index[id]
}
