package dev.cueopen.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SandboxPackageDefaultsTest {
    @Test fun `new default includes Alpine PyYAML package`() {
        assertEquals("bash curl wget git python3 py3-pip py3-yaml nodejs npm", SandboxPackageDefaults.DEFAULT)
    }

    @Test fun `previous unmodified default gains py3-yaml on state migration`() {
        assertEquals(SandboxPackageDefaults.DEFAULT, SandboxPackageDefaults.migrate("bash curl wget git python3 py3-pip nodejs npm"))
        assertEquals(SandboxPackageDefaults.DEFAULT, SandboxPackageDefaults.migrate(" bash   curl wget git python3 py3-pip nodejs npm "))
    }

    @Test fun `custom sandbox package list is preserved`() {
        val custom = "bash python3 nodejs jq"
        assertEquals(custom, SandboxPackageDefaults.migrate(custom))
    }
}
