package dev.cueopen.app.agent

/** Título e tipo do card ao vivo ("Navegando", "Editando arquivo"…) para cada ferramenta. */
object ToolActivityLabels {
    fun of(tool: String): Pair<String, Activity.Kind> = when (tool) {
        "shell" -> "Executando comando" to Activity.Kind.TERMINAL
        "file_write", "file_edit" -> "Editando arquivo" to Activity.Kind.EDITOR
        "file_read" -> "Lendo arquivo" to Activity.Kind.EDITOR
        "file_list" -> "Listando arquivos" to Activity.Kind.EDITOR
        "multi_agents" -> "Delegando subtarefas em paralelo" to Activity.Kind.THINKING
        "browser_navigate" -> "Navegando" to Activity.Kind.BROWSER
        "browser_new_tab" -> "Abrindo nova aba" to Activity.Kind.BROWSER
        "browser_select_tab" -> "Trocando de aba" to Activity.Kind.BROWSER
        "browser_close_tab" -> "Fechando aba" to Activity.Kind.BROWSER
        "browser_read", "browser_snapshot" -> "Lendo snapshot da página" to Activity.Kind.BROWSER
        "browser_click" -> "Clicando" to Activity.Kind.BROWSER
        "browser_type" -> "Digitando" to Activity.Kind.BROWSER
        "browser_scroll" -> "Rolando página" to Activity.Kind.BROWSER
        "browser_back" -> "Voltando" to Activity.Kind.BROWSER
        "browser_js" -> "Executando script na página" to Activity.Kind.BROWSER
        "remember", "memory_save" -> "Salvando na memória" to Activity.Kind.THINKING
        "memory_search" -> "Buscando na memória" to Activity.Kind.THINKING
        "notes_write" -> "Anotando" to Activity.Kind.EDITOR
        "browser_network" -> "Inspecionando rede" to Activity.Kind.BROWSER
        "complete_step" -> "Concluindo fase" to Activity.Kind.THINKING
        "set_schedule" -> "Agendando autonomia" to Activity.Kind.THINKING
        "device_notifications" -> "Lendo notificações" to Activity.Kind.THINKING
        "device_calendar_read" -> "Lendo calendário" to Activity.Kind.THINKING
        "device_call_log" -> "Lendo histórico de ligações" to Activity.Kind.THINKING
        "device_contacts" -> "Pesquisando contatos" to Activity.Kind.THINKING
        "device_location" -> "Consultando última localização" to Activity.Kind.THINKING
        "device_bluetooth", "device_bluetooth_pair" -> "Acessando Bluetooth" to Activity.Kind.THINKING
        "device_dial" -> "Abrindo discador" to Activity.Kind.THINKING
        "device_microphone" -> "Abrindo reconhecimento de voz" to Activity.Kind.THINKING
        "device_screenshot" -> "Capturando tela" to Activity.Kind.THINKING
        "device_accessibility_open_app" -> "Abrindo aplicativo" to Activity.Kind.THINKING
        "device_accessibility_read_screen" -> "Lendo controles visíveis" to Activity.Kind.THINKING
        "device_accessibility_inspect_image" -> "Analisando recorte visual" to Activity.Kind.THINKING
        "device_accessibility_click", "device_accessibility_tap" -> "Tocando no aplicativo" to Activity.Kind.THINKING
        "device_notification_reply" -> "Respondendo pela notificação" to Activity.Kind.THINKING
        "send_file" -> "Enviando arquivo" to Activity.Kind.THINKING
        "device_share_file" -> "Abrindo app para enviar arquivo" to Activity.Kind.THINKING
        "device_open_link" -> "Abrindo link no app" to Activity.Kind.THINKING
        "device_accessibility_type" -> "Preenchendo campo" to Activity.Kind.THINKING
        "device_accessibility_scroll" -> "Rolando aplicativo" to Activity.Kind.THINKING
        "device_accessibility_back" -> "Voltando no aplicativo" to Activity.Kind.THINKING
        "device_notify", "device_overlay" -> "Avisando no aparelho" to Activity.Kind.THINKING
        "browser_console" -> "Lendo console" to Activity.Kind.BROWSER
        "browser_wait_for" -> "Aguardando a página" to Activity.Kind.BROWSER
        "create_skill" -> "Criando skill" to Activity.Kind.THINKING
        "file_search" -> "Procurando nos arquivos" to Activity.Kind.EDITOR
        "page_create" -> "Criando página" to Activity.Kind.EDITOR
        "page_update" -> "Atualizando página" to Activity.Kind.EDITOR
        "page_read", "page_list" -> "Lendo páginas" to Activity.Kind.EDITOR
        "web_search" -> "Buscando na web" to Activity.Kind.SEARCH
        "web_fetch" -> "Lendo página" to Activity.Kind.SEARCH
        else -> (if (tool.startsWith("mcp_")) "Conector · " + tool.substringAfter("__") else tool) to Activity.Kind.THINKING
    }

    /** Resumo curto dos argumentos mostrado no passo (nunca o texto digitado em campos). */
    fun preview(tool: String, s: (String) -> String, i: (String) -> Int?, cwd: String): String = when (tool) {
        "shell" -> s("command")
        "file_write", "file_edit", "file_read", "file_list" -> s("path").ifBlank { cwd }
        "browser_navigate" -> s("url")
        "browser_new_tab" -> s("url")
        "browser_select_tab", "browser_close_tab" -> s("tab_id")
        "browser_type" -> s("text")
        "browser_click" -> "#${i("index")}"
        "remember", "memory_save" -> s("fact")
        "memory_search" -> s("query")
        "notes_write" -> s("content").take(60)
        "device_accessibility_open_app" -> s("app").take(60)
        "device_accessibility_click", "device_accessibility_scroll" -> "element ${i("element_id")?.toString() ?: "?"}"
        "device_accessibility_type" -> "element ${i("element_id")?.toString() ?: "?"} · texto oculto"
        "device_accessibility_inspect_image" -> "element ${i("element_id")?.toString() ?: "?"} · recorte efêmero"
        "create_skill" -> s("name")
        "file_search" -> s("pattern").take(60)
        "page_create" -> s("title").take(60)
        "page_update", "page_read" -> s("page_id")
        "web_search" -> s("query").take(80)
        "web_fetch" -> s("url").take(80)
        "send_file" -> s("channel") + " · " + s("path").substringAfterLast('/')
        "device_share_file" -> s("app") + " · " + s("path").substringAfterLast('/')
        "device_open_link" -> s("url").take(80)
        else -> ""
    }
}
