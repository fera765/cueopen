package dev.fluwy.app.agent

/**
 * Nomes que os modelos inventam por hábito (grep, read_file, ls…) apontam para a ferramenta real, quando ela está exposta.
 * Evita a volta "RECUSADO → nova tentativa" que gastava chamadas.
 */
object ToolAliases {
    private val map = mapOf(
        "file_search" to setOf("grep", "rg", "ripgrep", "search", "search_files", "find_in_files", "file_grep", "grep_search", "search_in_files", "find_text"),
        "file_read" to setOf("read_file", "cat", "open_file", "view_file", "read"),
        "file_list" to setOf("ls", "list_files", "list_dir", "list_directory", "dir"),
        "file_write" to setOf("write_file", "create_file", "save_file"),
        "file_edit" to setOf("edit_file", "str_replace", "replace_in_file", "apply_edit"),
        "web_search" to setOf("search_web", "google", "google_search", "websearch", "internet_search", "bing_search"),
        "web_fetch" to setOf("fetch", "fetch_url", "http_get", "webfetch", "open_url", "get_url", "read_url"),
    )

    fun resolve(name: String, exposed: Set<String>): String {
        if (name in exposed) return name
        val n = name.trim().lowercase()
        return map.entries.firstOrNull { (real, aliases) -> n in aliases && real in exposed }?.key ?: name
    }
}
