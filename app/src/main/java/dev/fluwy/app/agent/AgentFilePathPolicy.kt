package dev.fluwy.app.agent

import java.io.File

/** Resolve paths visíveis às ferramentas de arquivo sem expor o restante do filesystem Android/proot. */
internal object AgentFilePathPolicy {
    private val connectorIdPattern = Regex("^[a-z][a-z0-9]{1,19}$")
    private val taskAliases = listOf("/root/workspace", "/home/workspace", "/public/workspace")

    /** Caminhos relativos e aliases explícitos do workspace da tarefa; nunca caminhos absolutos arbitrários. */
    fun taskWorkspace(root: File, rawPath: String, allowRoot: Boolean = false): File? {
        val path = normalize(rawPath)
        if (path.isEmpty()) return if (allowRoot) canonical(root) else null
        val relative = when {
            taskAliases.any { path == it } -> ""
            taskAliases.any { path.startsWith("$it/") } -> {
                val alias = taskAliases.first { path.startsWith("$it/") }
                path.removePrefix("$alias/")
            }
            path.startsWith("/") -> hostRelative(root, path) ?: return null
            else -> path
        }
        return confined(root, relative, allowRoot)
    }

    /**
     * Formas comuns que o modelo usa para "a raiz do workspace" ou caminhos relativos a ela:
     * ".", "./x", "~", "~/workspace/x", "/root", "workspace/x" (o cwd do shell é /root/workspace).
     */
    fun collapseDots(path: String): String {
        val abs = path.startsWith("/")
        val out = ArrayList<String>()
        for (seg in path.split('/')) when {
            seg.isEmpty() || seg == "." -> {}
            seg == ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else out.add("..")
            else -> out.add(seg)
        }
        return (if (abs) "/" else "") + out.joinToString("/")
    }

    fun normalize(rawPath: String): String {
        // URIs (file://, content://…) seguem intactas e são recusadas mais adiante. Um ":" comum (ex.: "nota 10:30.md") é só nome de arquivo.
        if ("://" in rawPath) return rawPath.trim()
        var p = rawPath.trim().trim('"', '\'', '`').trim().replace(Regex("/{2,}"), "/")
        if (p == "~" || p == "~/" || p == "/root" || p == "/root/" || p == "." || p == "./" || p == "workspace" || p == "workspace/") return ""
        if (p.startsWith("~/workspace")) p = "/root/workspace" + p.removePrefix("~/workspace")
        else if (p.startsWith("~/")) p = p.removePrefix("~/")
        if (p.startsWith("workspace/")) p = p.removePrefix("workspace/")
        while (p.startsWith("./")) p = p.removePrefix("./")
        if (p.length > 1) p = p.trimEnd('/')
        // "a/../b" e ".fluwy/out/../x": resolve os ".." aqui; o que ainda sair da raiz continua com ".." e é recusado depois.
        if (p.split('/').any { it == ".." || it == "." }) p = collapseDots(p)
        return p
    }

    /** Leitura/listagem do workspace próprio ou dos workspaces de conectores montados como somente leitura. */
    fun readableWorkspace(
        taskRoot: File,
        connectorsRoot: File,
        rawPath: String,
        allowRoot: Boolean = false,
    ): File? {
        val path = connectorPath(connectorsRoot, rawPath) ?: return taskWorkspace(taskRoot, rawPath, allowRoot)
        if (path == "/root/connectors") return if (allowRoot) canonical(connectorsRoot) else null
        val prefix = "/root/connectors/"

        val segments = path.removePrefix(prefix).split('/')
        if (segments.size < 2 || segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val id = segments[0]
        if (!connectorIdPattern.matches(id) || segments[1] != "workspace") return null

        val connectorsBase = canonical(connectorsRoot)
        val workspace = canonical(File(connectorsRoot, "$id/workspace"))
        if (!isWithin(connectorsBase, workspace)) return null
        val relative = segments.drop(2).joinToString("/")
        return confined(workspace, relative, allowRoot)
    }

    /** Caminho absoluto do Android que aponta para DENTRO da própria raiz (o app mostra esses caminhos ao agente) → relativo. */
    private fun hostRelative(root: File, path: String): String? {
        for (base in setOf(root.absolutePath.trimEnd('/'), canonical(root).path.trimEnd('/'))) {
            if (base.isEmpty()) continue
            if (path == base) return ""
            if (path.startsWith("$base/")) return path.removePrefix("$base/")
        }
        return null
    }

    /** Normaliza as formas de "/root/connectors/..." (aspas, barras repetidas/finais, ~, /home, /public, caminho Android). */
    private fun connectorPath(connectorsRoot: File, rawPath: String): String? {
        if ("://" in rawPath) return null
        var p = rawPath.trim().trim('"', '\'', '`').trim().replace(Regex("/{2,}"), "/")
        if (p.length > 1) p = p.trimEnd('/')
        hostRelative(connectorsRoot, p)?.let { return if (it.isEmpty()) "/root/connectors" else "/root/connectors/$it" }
        for (alias in listOf("/root/connectors", "~/connectors", "/home/connectors", "/public/connectors")) {
            if (p == alias) return "/root/connectors"
            if (p.startsWith("$alias/")) return "/root/connectors/" + p.removePrefix("$alias/")
        }
        return null
    }

    private fun confined(root: File, relativePath: String, allowRoot: Boolean): File? {
        if (relativePath.contains('\\') || relativePath.contains('\u0000') || "://" in relativePath) return null
        if (relativePath.startsWith('/')) return null
        val segments = if (relativePath.isEmpty()) emptyList() else relativePath.split('/')
        if (segments.any { it.isEmpty() || it == ".." }) return null
        val normalized = segments.filter { it != "." }.joinToString("/")
        val rootFile = canonical(root)
        if (normalized.isEmpty()) return if (allowRoot) rootFile else null
        val target = canonical(File(rootFile, normalized))
        return target.takeIf { isWithin(rootFile, it) }
    }

    private fun isWithin(root: File, target: File): Boolean =
        target.path == root.path || target.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)

    private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
}
