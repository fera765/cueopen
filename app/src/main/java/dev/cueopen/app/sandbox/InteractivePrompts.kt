package dev.cueopen.app.sandbox

/**
 * Comandos do agente rodam SEM interação: ninguém responde "y", senha ou menu. Aqui ficam o ambiente
 * não interativo, a detecção de um comando parado numa pergunta e a dica de como rodá-lo de novo.
 */
object InteractivePrompts {
    /** Variáveis que fazem npm/npx, pip, apt, git, composer etc. seguirem sem perguntar. */
    const val ENV = "export CI=1 npm_config_yes=true NPM_CONFIG_YES=true npm_config_fund=false npm_config_audit=false " +
        "DEBIAN_FRONTEND=noninteractive PIP_NO_INPUT=1 PIP_DISABLE_PIP_VERSION_CHECK=1 GIT_TERMINAL_PROMPT=0 " +
        "COMPOSER_NO_INTERACTION=1 HOMEBREW_NO_AUTO_UPDATE=1 NEXT_TELEMETRY_DISABLED=1 ASTRO_TELEMETRY_DISABLED=1"

    private val PROMPT = Regex(
        "(?i)(\\(y/n\\)|\\[y/n\\]|\\(y\\)|\\[y\\]|\\(s/n\\)|\\[s/n\\]|\\(yes/no\\)|\\[yes/no\\]|ok to proceed\\??|proceed\\?|continue\\?|" +
            "are you sure|overwrite\\??|do you want to|how (do you want )?to proceed|deseja continuar|press (enter|any key|return)|password\\s*(for [^:]*)?:|passphrase|" +
            "username for|enter (a |the )?(name|value|token)|select an? |choose (an? )?|use arrow-keys|\\(use arrow keys\\)|›|◆|◇|❯|^\\?\\s.+)\\s*\\S{0,12}\\s*$"
    )

    /** Última linha que parece uma pergunta esperando resposta (ou null). */
    fun waitingPrompt(outputTail: String): String? {
        val lines = outputTail.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val last = lines.lastOrNull() ?: return null
        if (last.length > 240) return null
        return last.takeIf { PROMPT.containsMatchIn(it) }
    }

    /** Saída que indica que uma pergunta foi cancelada por falta de resposta (stdin fechado). */
    private val CANCELLED = Regex("(?i)(operation cancell?ed|prompt.*(cancel|abort)|user force closed|EOF when reading|input stream.*closed|" +
        "inappropriate ioctl|not a tty|no tty present|stdin is not a tty|ERR_USE_AFTER_CLOSE|aborted by user|cancell?ed by user)")

    fun looksCancelled(output: String): Boolean = CANCELLED.containsMatchIn(output.takeLast(3_000))

    /** Como refazer o comando sem pergunta, conforme a ferramenta. */
    fun hint(command: String, prompt: String?): String {
        val c = command.lowercase()
        val tips = mutableListOf<String>()
        if (Regex("\\bnpm (create|init)\\b|\\bnpx\\b|\\bpnpm (create|dlx)\\b|\\byarn create\\b|\\bbun create\\b").containsMatchIn(c)) {
            tips += "npm/npx: use `npx --yes …` (ou `npm create <pacote>@latest <pasta> -- <opções>`); passe TODAS as escolhas por flag (ex.: `npm create vite@latest app -- --template react`) e crie numa pasta NOVA/vazia — em pasta com arquivos o criador pergunta se pode sobrescrever."
        }
        if (Regex("\\bapt(-get)?\\b").containsMatchIn(c)) tips += "apt: `apt-get install -y …`."
        if (Regex("\\bpip3?\\b").containsMatchIn(c)) tips += "pip: `pip install --no-input …`; para desinstalar `pip uninstall -y …`."
        if (Regex("\\bgit\\b").containsMatchIn(c)) tips += "git: comandos que pedem usuário/senha não funcionam aqui; use URL pública ou token configurado pelo usuário. Em merge/commit, passe `-m \"…\"` e `--no-edit`."
        if (Regex("\\b(rm|cp|mv)\\b").containsMatchIn(c)) tips += "rm/cp/mv: use `-f` só se tiver certeza de que pode sobrescrever/apagar."
        if (Regex("\\bsudo\\b").containsMatchIn(c)) tips += "sudo: não há senha; você já é root aqui, rode sem sudo."
        if (Regex("\\b(composer|cargo|dotnet|flutter|expo|ng|vue|nest|astro|svelte|remix|django-admin|rails)\\b").containsMatchIn(c))
            tips += "CLIs de projeto: use a flag não interativa da ferramenta (ex.: `--yes`, `-y`, `--no-interaction`, `--defaults`, `--skip-install`, `--force`) e passe nome/opções por argumento."
        if (tips.isEmpty()) tips += "Rode de novo com a opção não interativa da ferramenta (`--yes`, `-y`, `--force`, `--non-interactive`, `--defaults`) e passe por argumento o que ela perguntaria; " +
            "só se a pergunta for uma confirmação simples e segura, use `yes | <comando>` (ou `printf 'y\\n' | <comando>`)."
        return (prompt?.let { "O comando parou esperando uma resposta no terminal: \"$it\". " } ?: "O comando foi cancelado porque pediu uma resposta interativa (aqui ninguém responde). ") +
            "Não repita o mesmo comando. " + tips.joinToString(" ")
    }
}
