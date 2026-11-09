package dev.cueopen.app.agent

/**
 * Regras de comando do terminal do agente (puras, testáveis):
 *  - servidores (http.server, serve, npm start/dev…) iniciados em primeiro plano prendem o terminal e
 *    quebram a captura de saída ("stdout/stderr capturados vazios" no log) → rodam em segundo plano;
 *  - busca no sistema inteiro (`find /`, `grep -r … /`) varre /vendor e /proc por horas sem achar nada:
 *    os arquivos do agente ficam sempre no workspace.
 */
object ShellCommandPolicy {
    private val server = Regex(
        "(?i)(python3?\\s+-m\\s+http\\.server|\\bnpx\\s+(-y\\s+)?(serve|http-server|live-server)\\b|\\bnpm\\s+exec\\s+serve\\b|" +
            "\\bnpm\\s+(run\\s+)?(start|dev|serve|preview)\\b|\\byarn\\s+(start|dev|serve)\\b|\\bphp\\s+-S\\b|\\bhttp-server\\b|\\blive-server\\b|" +
            "\\bnode\\s+\\S*(server|app|index)\\.m?js\\b|\\bflask\\s+run\\b|\\buvicorn\\b|\\bgunicorn\\b|\\bvite(\\s|$))"
    )

    /** Comando que sobe um servidor e não termina sozinho, sem já estar em segundo plano. */
    fun isLongRunningServer(command: String): Boolean {
        val c = command.trim()
        if (!server.containsMatchIn(c)) return false
        if (Regex("&\\s*$").containsMatchIn(c) || c.contains("nohup") || c.contains("timeout ") || c.contains("pkill") || c.contains("kill ")) return false
        return true
    }

    private val wholeSystem = Regex(
        "(?:^|[;&|(]\\s*)(?:find|grep\\s+-[a-zA-Z]*r[a-zA-Z]*(?:\\s+-\\S+)*\\s+(?:\"[^\"]*\"|'[^']*'|\\S+)|du|ls\\s+-[a-zA-Z]*R[a-zA-Z]*)\\s+(/|/vendor|/system|/proc|/sys|/data|/sdcard|/storage|/usr|/home\\s+/root)(?=\\s|$|/\\s)"
    )

    /** Varredura do sistema inteiro (ou de pastas do Android) em vez do workspace. */
    fun isWholeSystemSearch(command: String): Boolean = wholeSystem.containsMatchIn(command)
}
