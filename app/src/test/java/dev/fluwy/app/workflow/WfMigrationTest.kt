package dev.fluwy.app.workflow

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfMigrationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Fluxo salvo no formato antigo (sem version/settings, http com onError). */
    private val old = """[{"id":"w1","name":"Antigo","enabled":true,"nodes":[
        {"id":"t","type":"trigger_schedule","config":{"mode":"daily","time":"08:00"}},
        {"id":"h","type":"http","label":"Site","config":{"method":"GET","url":"https://example.com","onError":"continue"}},
        {"id":"i","type":"if","config":{"left":"{{h.status}}","op":"equals","right":"200"}},
        {"id":"m","type":"notify","config":{"text":"{{prev}}"}}],
        "edges":[{"id":"e1","from":"t","to":"h"},{"id":"e2","from":"h","to":"i","port":"main"},{"id":"e3","from":"i","to":"m","port":"true"},{"id":"e4","from":"h","to":"m","port":"bogus"}]}]"""

    @Test fun oldFileLoadsAndMigrates() {
        val w = json.decodeFromString(ListSerializer(Workflow.serializer()), old).single()
        assertEquals(1, w.version)
        val m = WfMigration.migrate(w)
        assertEquals(WfMigration.CURRENT, m.version)
        val h = m.nodes.first { it.id == "h" }
        assertTrue(h.settings.continueOnFail)
        assertFalse(h.config.containsKey("onError"))
        assertEquals("main", m.edges.first { it.id == "e4" }.port)
        assertEquals("true", m.edges.first { it.id == "e3" }.port)
        assertEquals("{{h.status}}", m.nodes.first { it.id == "i" }.config["left"])
        assertEquals(m, WfMigration.migrate(m))
        // Ida e volta pelo serializador mantém as configurações novas.
        val back = json.decodeFromString(Workflow.serializer(), json.encodeToString(Workflow.serializer(), m))
        assertEquals(m, back)
        assertTrue(WfLogic.ports(h).contains("error"))
    }

    @Test fun onErrorFromAgentOnCurrentVersion() {
        val w = Workflow(version = WfMigration.CURRENT, nodes = listOf(WfNode(id = "h", type = "http", config = mapOf("url" to "https://x.com", "onError" to "stop"))))
        val m = WfMigration.migrate(w)
        assertFalse(m.nodes[0].settings.continueOnFail); assertFalse(m.nodes[0].config.containsKey("onError"))
    }

    @Test fun signatureIgnoresDefaultSettingsAndPin() {
        val w = Workflow(nodes = listOf(WfNode(id = "a", type = "set", config = mapOf("values" to "a = 1"))))
        val pinned = w.copy(nodes = listOf(w.nodes[0].copy(settings = WfNodeSettings(pinData = "[{}]"))))
        val retry = w.copy(nodes = listOf(w.nodes[0].copy(settings = WfNodeSettings(retries = 2))))
        assertEquals(WfLogic.signature(w), WfLogic.signature(pinned))
        assertFalse(WfLogic.signature(w) == WfLogic.signature(retry))
    }

    @Test fun convertLegacyExpressions() {
        val labels = mapOf("g1" to "Último e-mail", "h1" to "Buscar", "x" to "Dup", "y" to "Dup")
        fun cv(s: String) = WfExprConvert.text(s, labels)
        assertEquals("{{ \$json }}", cv("{{prev}}"))
        assertEquals("{{ \$json.data[0].id }}", cv("{{prev.data.0.id}}"))
        assertEquals("{{ \$node[\"Último e-mail\"].json.data.results }}", cv("{{g1.data.results}}"))
        assertEquals("{{ \$node[\"Buscar\"].json }}", cv("{{Buscar}}"))
        assertEquals("{{ \$trigger.json.payload.chat }}", cv("{{trigger.payload.chat}}"))
        assertEquals("{{ \$now.toFormat('yyyy-MM-dd HH:mm') }}", cv("{{trigger.time}}"))
        assertEquals("{{ \$json.a || 'nada' }}", cv("{{prev.a | default:nada}}"))
        assertEquals("{{g1 | text}}", cv("{{g1 | text}}"))
        assertEquals("{{x.a}}", cv("{{x.a}}"))
        assertEquals("{{trigger.type}}", cv("{{trigger.type}}"))
        assertEquals("{{ \$json.k }}", cv("{{ \$json.k }}"))
    }

    @Test fun convertedRendersTheSame() {
        val items = mapOf("g1" to listOf(WfItems.fromOutput("""{"data":{"results":[{"id":"m1","subject":"Oi"}]}}""")))
        val labels = mapOf("g1" to "Último e-mail", "t" to "Início")
        val ctx = WfExprCtx(item = items.getValue("g1")[0], nodeItems = items, labels = labels)
        listOf("{{g1.data.results.0.subject}}", "{{prev.data.results.0.id}}", "{{prev.nada | default:x}}", "A: {{g1.data.results.0.id}}!").forEach { t ->
            assertEquals(t, WfExpr.render(t, ctx), WfExpr.render(WfExprConvert.text(t, labels), ctx))
        }
    }
}
