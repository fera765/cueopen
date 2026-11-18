package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserRedirectTest {
    @Test fun refusesBrowserInstalls() {
        listOf(
            "npx playwright install chromium",
            "npx playwright@1.48 install",
            "npx @puppeteer/browsers install chrome@stable",
            "apk add --no-cache chromium chromium-chromedriver",
            "apt-get install -y firefox-esr",
            "pip install selenium webdriver-manager --break-system-packages",
            "wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb",
        ).forEach { assertNotNull(it, BrowserRedirect.guard(it)) }
    }

    @Test fun letsNormalCommandsRun() {
        listOf(
            "npm i puppeteer",
            "pip install selenium --break-system-packages",
            "apk add python3 nodejs",
            "node scrape.js",
            "echo chromium is a browser",
            "npm install",
        ).forEach { assertNull(it, BrowserRedirect.guard(it)) }
    }

    @Test fun headlessFailuresPointToAppBrowser() {
        listOf(
            "Error: Could not find Chrome (ver. 131.0.6778.85). This can occur if either",
            "browserType.launch: Executable doesn't exist at /root/.cache/ms-playwright/chromium-1140/chrome-linux/chrome",
            "selenium.common.exceptions.SessionNotCreatedException: Message: session not created",
            "FLUWY_BROWSER_REDIRECT: 'chromium' não existe aqui.",
            "Error: Failed to launch the browser process!",
        ).forEach { assertEquals(it, ToolOutcomeAdvisor.Reason.HEADLESS_BROWSER, ToolOutcomeAdvisor.classify("shell", it)) }
    }
}
