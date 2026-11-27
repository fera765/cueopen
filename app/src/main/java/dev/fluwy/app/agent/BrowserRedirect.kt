package dev.fluwy.app.agent

import java.io.File

/**
 * O navegador do app (ferramentas browser_*) é a ÚNICA fonte de verdade para navegar.
 * Pacotes que pedem Chrome/Chromium/Firefox (puppeteer, playwright, selenium, cypress…) são redirecionados:
 *  - variáveis de ambiente evitam o download de navegadores no `npm/pip install`;
 *  - executáveis falsos (google-chrome, chromium, firefox, chromedriver…) explicam o caminho certo em vez de falhar mudos;
 *  - comandos que instalam navegadores são recusados antes de rodar;
 *  - erros de "navegador não encontrado" viram um diagnóstico apontando para browser_*.
 */
object BrowserRedirect {
    const val SHIM_DIR = "/root/.fluwy/bin"

    const val MESSAGE =
        "Este app não usa Chrome/Chromium/Firefox nem drivers (puppeteer, playwright, selenium). " +
            "O navegador do app é a única fonte de verdade: use as ferramentas browser_navigate, browser_read, browser_snapshot, " +
            "browser_click, browser_type e browser_screenshot. Para testar um site/servidor local, abra http://127.0.0.1:PORTA com browser_navigate. " +
            "Para só baixar uma página/API, use curl ou web_fetch."

    val SHIMS = listOf(
        "google-chrome", "google-chrome-stable", "chrome", "chromium", "chromium-browser", "chrome-headless-shell",
        "firefox", "firefox-esr", "chromedriver", "geckodriver", "msedge", "microsoft-edge",
    )

    /** Exports carregados pelo shell de cada tarefa (hook.sh). */
    val ENV_EXPORTS: String = listOf(
        "PUPPETEER_SKIP_DOWNLOAD=1", "PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=1", "PUPPETEER_EXECUTABLE_PATH=$SHIM_DIR/chromium",
        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1", "CYPRESS_INSTALL_BINARY=0", "CHROMEDRIVER_SKIP_DOWNLOAD=true",
        "GECKODRIVER_SKIP_DOWNLOAD=true", "CHROME_BIN=$SHIM_DIR/chromium", "CHROME_PATH=$SHIM_DIR/chromium",
        "BROWSER=$SHIM_DIR/chromium", "WDM_LOCAL=1",
    ).joinToString("\n") { "export $it" } + "\ncase \":\$PATH:\" in *\":$SHIM_DIR:\"*) ;; *) export PATH=\"$SHIM_DIR:\$PATH\" ;; esac\n"

    fun shimScript(name: String): String =
        "#!/bin/sh\n# Fluwy: redireciona '$name' para o navegador do app\n" +
            "echo \"FLUWY_BROWSER_REDIRECT: '$name' não existe aqui. ${MESSAGE.replace("\"", "'")}\" >&2\nexit 127\n"

    /** Escreve os executáveis falsos em [fluwyDir]/bin (é o /root/.fluwy da sessão). */
    fun installShims(fluwyDir: File) {
        val bin = File(fluwyDir, "bin").apply { mkdirs() }
        SHIMS.forEach { n ->
            val f = File(bin, n)
            val body = shimScript(n)
            if (!f.exists() || f.readText() != body) { f.writeText(body) }
            f.setExecutable(true, false)
        }
    }

    private val installRe = Regex(
        "(?i)(" +
            "playwright(@[\\w.^~-]+)?\\s+install(?!-deps\\s*$)" +
            "|(@puppeteer/browsers|puppeteer(@[\\w.^~-]+)?\\s+browsers)\\s+install" +
            "|\\b(apk|apt|apt-get|dnf|yum|pacman|snap|zypper)\\b[^;&|\\n]{0,60}\\b(add|install|-S)\\b[^;&|\\n]*\\b(chromium|chromium-browser|chromium-chromedriver|google-chrome[\\w-]*|firefox(-esr)?|geckodriver|chromedriver|microsoft-edge[\\w-]*)\\b" +
            "|\\bpip3?\\s+install\\b[^;&|\\n]*\\b(webdriver-manager|chromedriver-autoinstaller|chromedriver-binary|geckodriver-autoinstaller|pyppeteer)\\b" +
            "|\\bseleniumbase\\s+(install|get)\\b" +
            "|\\b(curl|wget)\\b[^;&|\\n]*(google-chrome[\\w.-]*\\.(deb|rpm)|chromedriver[\\w.-]*\\.zip|geckodriver[\\w.-]*\\.tar\\.gz|chrome-for-testing|download-installer\\.cdn\\.mozilla)" +
            ")",
    )

    /** Mensagem de GUARDRAIL quando o comando tenta instalar um navegador/driver; null quando pode rodar. */
    fun guard(command: String): String? =
        if (installRe.containsMatchIn(command)) "GUARDRAIL: não instale navegadores nem drivers. $MESSAGE" else null

    /** Saídas típicas de puppeteer/playwright/selenium sem navegador. */
    val failureRe = Regex(
        "(?i)FLUWY_BROWSER_REDIRECT|could not find (expected browser|chrom(e|ium))|executable doesn't exist at[^\\n]{0,120}ms-playwright" +
            "|looks like playwright[^\\n]{0,40}just (installed|updated)|please run the following command to download new browsers" +
            "|failed to launch the browser process|browserType\\.launch|chromedriver[^\\n]{0,40}(not found|executable needs)" +
            "|geckodriver[^\\n]{0,40}(not found|executable needs)|selenium\\.common\\.exceptions\\.(sessionnotcreated|webdriver)exception" +
            "|no usable sandbox|cannot find chrome binary|chrome not reachable|unable to obtain (driver|browser)" +
            "|webdriver_manager|chromium revision is not downloaded|you need to install a browser",
    )

    const val PROMPT_LINE =
        "Navegador: o navegador do app (browser_*) é a única fonte de verdade. Nunca instale nem rode Chrome, Chromium, Firefox, " +
            "puppeteer, playwright ou selenium no Linux do app; para navegar, testar uma página local (http://127.0.0.1:PORTA) ou tirar print, use browser_*."
}
