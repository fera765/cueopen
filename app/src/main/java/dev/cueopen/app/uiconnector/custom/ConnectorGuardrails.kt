package dev.cueopen.app.uiconnector.custom

/**
 * GUARDRAILS do criador de conectores com IA (Kotlin puro — testável na JVM).
 *
 * O parser ([CustomDefParser]) garante que o JSON tem o FORMATO certo. Isso não basta: conectores com
 * terminal/arquivos passavam no parser e quebravam ao rodar dentro de um agente (heredoc, `pip install`
 * bloqueado pelo Alpine, comando que não existe, `{{param}}` entre aspas, REPL que trava o PTY, `exit`
 * que mata a sessão…). Aqui ficam as regras que pegam esses defeitos ANTES da instalação:
 *
 *  - [staticCheck]: análise do que o conector vai executar (setup, comandos, scripts, arquivos);
 *  - [externalCommands]: comandos externos usados, conferidos de verdade no sandbox (`command -v`);
 *  - [syntaxCheck]: como validar a sintaxe de cada script gravado (bash/python/node);
 *  - [hiddenFailure]: falhas que aparecem na saída mesmo quando o exit code é 0;
 *  - [executableInValidation]: quais ferramentas podem rodar no teste (nunca as de ordem/efeito externo).
 *
 * Erros ([Level.ERROR]) BLOQUEIAM a proposta do conector; avisos ([Level.WARN]) só orientam.
 */
object ConnectorGuardrails {
    enum class Level { ERROR, WARN }
    data class Finding(val level: Level, val where: String, val message: String) {
        override fun toString() = (if (level == Level.ERROR) "✗ " else "⚠ ") + "$where: $message"
    }

    /** Palavras que indicam efeito EXTERNO (enviar, pagar, publicar…): a ferramenta de terminal não roda no teste. */
    private val SIDE_EFFECT = Regex("(?i)(buy|sell|order|trade|compra|vend|ordem|operar|opera[cç][aã]o|withdraw|saque|transfer|pagar|pagamento|\\bpay|enviar|envia\\b|\\bsend|publicar|publish|postar|\\bpost\\b|tweet|e-?mail|mensagem|whatsapp|telegram)")

    /** Builtins/palavras do shell que não precisam existir como binário. */
    private val BUILTINS = setOf(
        "cd", "echo", "printf", "test", "[", "[[", "export", "set", "unset", "true", "false", "exit", "return", "read",
        "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "until", "case", "esac", "in", "function",
        "local", "source", ".", "eval", "exec", "shift", "trap", "wait", "ulimit", "umask", "alias", "type", "command",
        "builtin", "declare", "typeset", "let", "pwd", "time", "nohup", "env", "!", "{", "}", "(", ")", "select", "break", "continue",
    )
    /** Programas que abrem tela/REPL interativo: no PTY do agente ficam esperando para sempre. */
    private val INTERACTIVE = setOf("vi", "vim", "nano", "less", "more", "top", "htop", "man", "ssh", "ftp", "telnet", "mysql", "psql", "sqlite3", "irb", "ipython")
    /** Interpretadores que, chamados sem argumentos, viram REPL (e travam). */
    private val REPL_IF_BARE = setOf("python", "python3", "node", "bash", "sh", "ash", "php", "ruby", "perl", "lua", "cat", "bc")

    // ------------------------------------------------------------------ análise léxica mínima de shell
    private enum class Q { NONE, SINGLE, DOUBLE }

    /** Resultado da varredura: quotes balanceadas? `{{…}}` dentro de aspas? heredoc fora de aspas? */
    private data class Scan(val unbalanced: Boolean, val templateInQuotes: List<String>, val heredoc: Boolean, val segments: List<String>)

    private fun scan(cmd: String): Scan {
        var q = Q.NONE
        var i = 0
        val inQuotes = ArrayList<String>()
        var heredoc = false
        val segs = ArrayList<String>()
        val cur = StringBuilder()
        fun cut() { if (cur.isNotBlank()) segs.add(cur.toString().trim()); cur.setLength(0) }
        while (i < cmd.length) {
            val ch = cmd[i]
            if (q != Q.NONE && cmd.startsWith("{{", i)) {
                val end = cmd.indexOf("}}", i)
                inQuotes.add(if (end > 0) cmd.substring(i, end + 2) else cmd.substring(i))
            }
            when (q) {
                Q.SINGLE -> { if (ch == '\'') q = Q.NONE; cur.append(ch) }
                Q.DOUBLE -> {
                    if (ch == '\\' && i + 1 < cmd.length) { cur.append(ch).append(cmd[i + 1]); i += 2; continue }
                    if (ch == '"') q = Q.NONE
                    cur.append(ch)
                }
                Q.NONE -> when {
                    ch == '\\' && i + 1 < cmd.length -> { cur.append(ch).append(cmd[i + 1]); i += 2; continue }
                    ch == '\'' -> { q = Q.SINGLE; cur.append(ch) }
                    ch == '"' -> { q = Q.DOUBLE; cur.append(ch) }
                    ch == '#' && (cur.isEmpty() || cur.last().isWhitespace()) -> { // comentário até o fim da linha
                        while (i < cmd.length && cmd[i] != '\n') i++
                        continue
                    }
                    cmd.startsWith("<<", i) && !cmd.startsWith("<<<", i) -> { heredoc = true; cur.append("<<"); i += 2; continue }
                    // redirecionamentos 2>&1, >&2, &> não separam comandos
                    ch == '&' && ((i > 0 && cmd[i - 1] == '>') || (i + 1 < cmd.length && cmd[i + 1] == '>')) -> cur.append(ch)
                    cmd.startsWith("&&", i) || cmd.startsWith("||", i) -> { cut(); i += 2; continue }
                    ch == ';' || ch == '|' || ch == '\n' || ch == '&' -> { cut(); i++; continue }
                    ch == '(' || ch == ')' || ch == '{' && !cmd.startsWith("{{", i) && (i + 1 >= cmd.length || cmd[i + 1].isWhitespace()) -> { cut(); i++; continue }
                    ch == '}' && (cur.isEmpty() || cur.last().isWhitespace()) && (i == 0 || cmd[i - 1] != '}') && !cmd.startsWith("}}", i) -> { cut(); i++; continue }
                    else -> cur.append(ch)
                }
            }
            i++
        }
        cut()
        return Scan(q != Q.NONE, inQuotes, heredoc, segs)
    }

    /** Divide um segmento simples em palavras (respeitando aspas; sem expansão). */
    private fun words(seg: String): List<String> {
        val out = ArrayList<String>(); val w = StringBuilder(); var q = Q.NONE
        for (ch in seg) {
            when {
                q == Q.SINGLE -> if (ch == '\'') q = Q.NONE else w.append(ch)
                q == Q.DOUBLE -> if (ch == '"') q = Q.NONE else w.append(ch)
                ch == '\'' -> { q = Q.SINGLE; w.append('\u0001') } // marca "veio de aspas"
                ch == '"' -> { q = Q.DOUBLE; w.append('\u0001') }
                ch.isWhitespace() -> { if (w.isNotEmpty()) out.add(w.toString()); w.setLength(0) }
                else -> w.append(ch)
            }
        }
        if (w.isNotEmpty()) out.add(w.toString())
        return out
    }

    private val ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")
    private val CMD_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")
    private val REDIR = Regex("^[0-9]*[<>].*|^&>.*")

    /** Primeira palavra "de verdade" de cada segmento (pula VAR=x, palavras-chave, nohup/env/time/sudo). */
    private fun heads(segments: List<String>): List<Pair<String, List<String>>> = segments.mapNotNull { seg ->
        var ws = words(seg.replace(Regex("\\{\\{[^}]*\\}\\}"), "\u0001T"))
        while (ws.isNotEmpty() && (ASSIGN.matches(ws[0]) || ws[0] in setOf("if", "then", "else", "elif", "do", "while", "until", "!", "time", "nohup", "env", "exec", "builtin"))) ws = ws.drop(1)
        if (ws.isEmpty()) null else ws[0] to ws.drop(1)
    }

    /** Comandos externos (binários) usados por um comando shell — conferidos com `command -v` no teste. */
    fun externalCommands(cmd: String): Set<String> {
        val s = scan(cmd)
        return heads(s.segments).mapNotNull { (h, _) ->
            if (h.startsWith("\u0001") || h.startsWith("$") || h.contains('/') || h in BUILTINS || !CMD_NAME.matches(h) || h.startsWith("-")) null else h
        }.toSet()
    }

    /** Scripts locais chamados pelo comando (python3 x.py, bash x.sh, node x.js, ./x.sh). */
    fun scriptsReferenced(cmd: String): Set<String> {
        val out = HashSet<String>()
        heads(scan(cmd).segments).forEach { (h, args) ->
            if (h.startsWith("./")) out.add(h.removePrefix("./"))
            if (h in setOf("python", "python3", "bash", "sh", "ash", "node", "php", "ruby", "perl", "lua")) {
                args.firstOrNull { !it.startsWith("-") && !REDIR.matches(it) }?.let { a ->
                    if (Regex("(?i).+\\.(py|sh|js|mjs|cjs|php|rb|pl|lua)$").matches(a) && !a.startsWith("/") && !a.contains("\u0001")) out.add(a.removePrefix("./"))
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ regras estáticas
    private val PIP_INSTALL = Regex("\\bpip3?\\s+install\\b|\\bpython3?\\s+-m\\s+pip\\s+install\\b")
    private val OTHER_PKG = Regex("(^|[\\s;&|(])(apt-get|apt|yum|dnf|brew|pacman|zypper)\\s+(-\\S+\\s+)*(install|update|upgrade)\\b")
    private val SUDO = Regex("(^|[\\s;&|(])sudo\\s")
    private val RM_ROOT = Regex("\\brm\\s+(-[a-zA-Z]*\\s+)*(/|/\\*|~|~/|\\\$HOME|\\.\\.|\\*)(\\s|$|;|&)")
    private val CURL_SH = Regex("\\b(curl|wget)\\b[^|;&]*\\|\\s*(sudo\\s+)?(ba)?sh\\b")
    private val HIDE_ALL = Regex(">\\s*/dev/null\\s+2>&1\\s*$|&>\\s*/dev/null\\s*$")
    private val OR_TRUE = Regex("\\|\\|\\s*(true|:)\\s*$")
    private val APK_ADD = Regex("\\bapk\\s+add\\b")

    /** Regras comuns a qualquer comando (setup ou ferramenta). */
    private enum class Mode { SETUP, RUN, SCRIPT }

    private fun commandRules(cmd: String, where: String, mode: Mode, out: MutableList<Finding>) {
        val s = scan(cmd)
        val isSetup = mode == Mode.SETUP
        val inScript = mode == Mode.SCRIPT
        if (s.unbalanced) out += Finding(Level.ERROR, where, if (inScript) "script com aspas não fechadas: o bash recusa o arquivo." else "aspas não fechadas — o shell fica esperando o fim do comando e a ferramenta trava até o tempo-limite.")
        if (s.heredoc && !inScript) out += Finding(Level.ERROR, where, "heredoc (<<EOF) no comando. Grave scripts/dados com \"files\" e só rode o arquivo no \"run\".")
        if (!inScript) s.templateInQuotes.distinct().forEach { t ->
            out += Finding(Level.ERROR, where, "$t está dentro de aspas. O app JÁ coloca aspas seguras no valor; entre aspas ele quebra em palavras ou leva aspas literais. Escreva só $t, sem aspas em volta.")
        }
        if (OTHER_PKG.containsMatchIn(cmd)) out += Finding(Level.ERROR, where, "o sandbox é Alpine Linux: use \"apk add --no-cache <pacote>\" (apt/yum/brew não existem).")
        if (SUDO.containsMatchIn(cmd)) out += Finding(Level.ERROR, where, "sudo não existe no sandbox (já roda como root). Remova o sudo.")
        if (PIP_INSTALL.containsMatchIn(cmd) && !cmd.contains("--break-system-packages") && !cmd.contains("PIP_BREAK_SYSTEM_PACKAGES") && !Regex("\\bvenv\\b|virtualenv|/bin/pip").containsMatchIn(cmd))
            out += Finding(Level.ERROR, where, "no Alpine o pip recusa instalar sem \"--break-system-packages\" (erro externally-managed-environment). Use: pip install --break-system-packages -q <pacote>.")
        if (RM_ROOT.containsMatchIn(cmd)) out += Finding(Level.ERROR, where, "rm apagando raiz/home/tudo. Apague só arquivos específicos do workspace.")
        if (CURL_SH.containsMatchIn(cmd)) out += Finding(Level.WARN, where, "baixar e executar script direto (curl | sh) é frágil e inseguro; prefira apk/pip.")
        for ((h, args) in heads(s.segments)) {
            if (h in INTERACTIVE) out += Finding(Level.ERROR, where, "\"$h\" é interativo e trava o terminal do agente. Use um comando não interativo.")
            val realArgs = args.filterNot { REDIR.matches(it) }
            if (!inScript && h in REPL_IF_BARE && realArgs.isEmpty() && !cmd.contains("|") && !s.heredoc)
                out += Finding(Level.WARN, where, "\"$h\" sem argumentos não recebe entrada no terminal do agente (stdin vazio) e não faz nada. Passe o arquivo/comando (ex.: python3 script.py).")
            if (h == "read" && !inScript) out += Finding(Level.ERROR, where, "\"read\" espera digitação, mas o terminal do agente não tem teclado (stdin vazio). Receba valores por parâmetros ({{param}}) ou configurações.")
        }
        if (inScript) return
        if (!isSetup && APK_ADD.containsMatchIn(cmd)) out += Finding(Level.WARN, where, "instalar pacotes a cada chamada deixa a ferramenta lenta e sujeita a tempo-limite. Mova para o bloco \"setup\".")
        if (!isSetup && PIP_INSTALL.containsMatchIn(cmd)) out += Finding(Level.WARN, where, "pip install em toda chamada: mova para o bloco \"setup\".")
        if (!isSetup && HIDE_ALL.containsMatchIn(cmd.trim())) out += Finding(Level.WARN, where, "a saída do comando principal vai para /dev/null: em caso de erro o agente não verá o motivo.")
        if (!isSetup && OR_TRUE.containsMatchIn(cmd.trim())) out += Finding(Level.ERROR, where, "\"|| true\" no fim esconde a falha: a ferramenta diria sucesso mesmo quebrando. Remova.")
        if (Regex("(^|[\\s;])nohup\\s").containsMatchIn(cmd) || (cmd.trim().endsWith("&") && !cmd.trim().endsWith("&&")))
            out += Finding(Level.ERROR, where, "comando em segundo plano (&): o processo termina junto com a chamada e o resultado se perde. Rode em primeiro plano.")
    }

    /** Ferramenta de terminal com efeito externo (enviar/pagar/publicar) ou ordem: nunca roda no teste. */
    fun hasExternalEffect(t: ToolDef): Boolean = t.intent != null || t.requiresSignal || t.limits.isNotEmpty() ||
        (t.shell != null && SIDE_EFFECT.containsMatchIn(t.name + " " + t.description))

    /**
     * Pode rodar no teste de validação? Terminal/arquivos/browser rodam no sandbox ISOLADO de validação
     * (o "risky" delas é por usar o aparelho, não por mexer em dinheiro). Nunca: ordens, envios, HTTP não-GET
     * sensível e tempo real de envio.
     */
    fun executableInValidation(t: ToolDef): Boolean = when {
        hasExternalEffect(t) -> false
        t.agent != null -> false
        t.shell != null || t.file != null || t.fetch != null -> true
        else -> !t.risky
    }

    fun staticCheck(def: CustomDef): List<Finding> {
        val out = ArrayList<Finding>()
        val usesTerminal = def.tools.any { it.shell != null } || def.setup != null
        def.setup?.let { su ->
            commandRules(su.run, "setup.run", Mode.SETUP, out)
            if (su.timeoutSec < 60 && (APK_ADD.containsMatchIn(su.run) || PIP_INSTALL.containsMatchIn(su.run) || su.run.contains("npm ")))
                out += Finding(Level.WARN, "setup.timeoutSec", "instalar pacotes costuma levar minutos; use 300–900 s.")
        }
        val setupScripts = def.setup?.let { scriptsReferenced(it.run) }.orEmpty()
        val writtenByAnyTool = def.tools.flatMap { t -> t.shell?.files.orEmpty().map { it.path.trim().removePrefix("./") } + listOfNotNull(t.file?.takeIf { it.op == "write" }?.path) }.toSet()
        def.tools.forEach { t ->
            val where = "ferramenta ${t.name}"
            if (t.agent != null) out += Finding(Level.WARN, "$where.agent", "a tarefa delegada só roda quando o conector estiver instalado e a capacidade agent for autorizada; connector_test usa resposta mock, sem chamada LLM real.")
            val sh = t.shell
            if (sh != null) {
                commandRules(sh.run, "$where.shell.run", Mode.RUN, out)
                val mine = sh.files.map { it.path.trim().removePrefix("./") }.toSet()
                scriptsReferenced(sh.run).filter { it !in mine && it !in writtenByAnyTool && it !in setupScripts }.forEach { f ->
                    out += Finding(Level.WARN, "$where.shell.run", "roda \"$f\", mas nenhum \"files\" cria esse arquivo (o teste confere se ele existe). Declare em shell.files [{\"path\":\"$f\",\"content\":\"…\"}].")
                }
                val dup = sh.files.groupBy { it.path.trim() }.filter { it.value.size > 1 }.keys
                dup.forEach { out += Finding(Level.ERROR, "$where.shell.files", "arquivo \"$it\" declarado mais de uma vez.") }
                sh.files.forEach { fw ->
                    val fp = "$where.shell.files[${fw.path}]"
                    if (fw.content.isBlank()) out += Finding(Level.ERROR, fp, "conteúdo vazio: o script não faria nada.")
                    if (fw.path.endsWith(".sh") || fw.path.endsWith(".bash")) {
                        if (fw.content.contains("{{")) out += Finding(Level.WARN, fp, "{{…}} dentro de script .sh entra SEM aspas. Passe valores como argumento no run (bash ${fw.path} {{param}}) e leia \"\$1\".")
                        commandRules(fw.content, fp, Mode.SCRIPT, out)
                    } else if (Regex("\\{\\{\\s*[a-z][A-Za-z0-9_]*\\s*(\\|[^}]*)?\\}\\}").containsMatchIn(fw.content) && !fw.content.contains("|json")) {
                        out += Finding(Level.WARN, fp, "parâmetros {{…}} inseridos direto no código do script podem quebrar a sintaxe (aspas, quebras de linha). Prefira passar por variável de ambiente no run (X={{param}} python3 ${fw.path}) ou usar o filtro |json.")
                    }
                }
                if (sh.timeoutSec < 20 && Regex("\\b(yt-dlp|ffmpeg|curl|wget|pip|npm|git|python3?|node)\\b").containsMatchIn(sh.run))
                    out += Finding(Level.WARN, "$where.shell.timeoutSec", "${sh.timeoutSec}s é pouco para esse comando; ele pode ser cortado no meio.")
                if (t.response.isBlank() && t.list == null && t.compute.isEmpty())
                    out += Finding(Level.WARN, "$where.response", "sem \"response\": o agente recebe a saída crua do terminal. Prefira um resumo com {{$.body|trunc:1500}}.")
            }
            val f = t.file
            if (f != null && f.op == "write" && f.content.isNullOrBlank()) out += Finding(Level.ERROR, "$where.file", "write sem \"content\": criaria um arquivo vazio.")
        }
        if (usesTerminal && def.setup == null) {
            val ext = def.tools.mapNotNull { it.shell }.flatMap { externalCommands(it.run) }.toSet() - BASE_TOOLS
            if (ext.isNotEmpty()) out += Finding(Level.WARN, "setup", "as ferramentas usam ${ext.joinToString()} mas não há \"setup\" para instalar. O teste confere se existem no sandbox; se não existirem, crie o setup.")
        }
        return out
    }

    /** Comandos que o Alpine base (busybox + bash) já tem. */
    val BASE_TOOLS = setOf(
        "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "touch", "chmod", "chown", "ln", "find", "grep", "egrep", "sed", "awk",
        "head", "tail", "wc", "sort", "uniq", "cut", "tr", "tee", "xargs", "date", "sleep", "basename", "dirname", "du", "df",
        "tar", "gzip", "gunzip", "zcat", "unzip", "wget", "sh", "ash", "bash", "busybox", "env", "which", "stat", "md5sum",
        "sha256sum", "base64", "seq", "expr", "realpath", "readlink", "printenv", "id", "whoami", "uname", "hostname", "kill",
        "ps", "nproc", "od", "hexdump", "diff", "cmp", "split", "fold", "rev", "yes", "nl", "comm", "paste", "install", "mktemp",
    )

    // ------------------------------------------------------------------ verificação em tempo de execução
    /** Comando que valida a sintaxe de um arquivo gravado, por extensão. null = sem verificador. */
    fun syntaxCheck(path: String): String? {
        val q = Template.shellQuote(path)
        return when {
            path.endsWith(".sh") || path.endsWith(".bash") -> "bash -n $q"
            path.endsWith(".py") -> "python3 -c 'import ast,sys; ast.parse(open(sys.argv[1]).read(), sys.argv[1])' $q"
            path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs") -> "node --check $q"
            path.endsWith(".json") -> "python3 -c 'import json,sys; json.load(open(sys.argv[1]))' $q 2>/dev/null || { command -v python3 >/dev/null 2>&1 || exit 0; exit 1; }"
            else -> null
        }
    }

    /** Interpretador exigido pelo verificador de sintaxe (para não acusar falso erro se não houver python/node). */
    fun syntaxInterpreter(path: String): String? = when {
        path.endsWith(".py") || path.endsWith(".json") -> "python3"
        path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs") -> "node"
        else -> null
    }

    private val HIDDEN = listOf(
        Regex("(?m)command not found"), Regex("(?m): not found\\s*$"), Regex("No such file or directory"),
        Regex("Traceback \\(most recent call last\\)"), Regex("ModuleNotFoundError"), Regex("ImportError:"),
        Regex("SyntaxError:"), Regex("Permission denied"), Regex("Segmentation fault"), Regex("externally-managed-environment"),
        Regex("npm ERR!"), Regex("Error: Cannot find module"), Regex("cannot execute"), Regex("(?m)^Killed\\s*$"),
        Regex("\\[timeout após"),
    )

    /** Falha "escondida" na saída (ex.: script engoliu o erro e saiu com 0). null = saída limpa. */
    fun hiddenFailure(output: String): String? = HIDDEN.firstOrNull { it.containsMatchIn(output) }?.let { re ->
        val line = output.lineSequence().firstOrNull { re.containsMatchIn(it) }?.trim().orEmpty()
        line.ifBlank { re.pattern }.take(240)
    }

    /**
     * Prepara o comando do conector para rodar no terminal persistente do agente com isolamento:
     * sempre a partir do workspace, num processo `bash` próprio (cd/export/exit não vazam para a
     * próxima chamada nem matam a sessão) e sem stdin (nada fica esperando digitação).
     */
    fun isolate(command: String): String =
        "cd /root/workspace 2>/dev/null; bash -c " + Template.shellQuote(command) + " < /dev/null"
}
