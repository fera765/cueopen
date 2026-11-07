package dev.cueopen.app.agent

import dev.cueopen.app.agent.ActionGate.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGateTest {
    @Test
    fun buttonLabels() {
        assertEquals(Category.PURCHASE, ActionGate.classify("browser_click", "Comprar agora"))
        assertEquals(Category.PURCHASE, ActionGate.classify("browser_click", "Finalizar compra"))
        assertEquals(Category.PURCHASE, ActionGate.classify("device_accessibility_click", "Confirmar pagamento"))
        assertEquals(Category.DELETE, ActionGate.classify("device_accessibility_click", "Excluir publicação"))
        assertEquals(Category.SEND, ActionGate.classify("browser_click", "Publicar"))
        assertEquals(Category.TRANSFER, ActionGate.classify("browser_click", "Enviar Pix"))
        assertEquals(Category.ACCOUNT, ActionGate.classify("browser_click", "Alterar senha"))
    }

    @Test
    fun routineControlsPass() {
        assertNull(ActionGate.classify("browser_click", "Ver detalhes"))
        assertNull(ActionGate.classify("browser_click", "Posts"))
        assertNull(ActionGate.classify("browser_click", "Adicionar ao carrinho"))
        assertNull(ActionGate.classify("browser_type", null))
        assertNull(ActionGate.classify("browser_read", "Comprar"))
        assertNull(ActionGate.classify("shell", "rm -rf build/"))
    }

    @Test
    fun connectorToolNames() {
        assertEquals(Category.DELETE, ActionGate.classify("mcp_instagram__delete_post", null))
        assertEquals(Category.SEND, ActionGate.classify("uic_gmail_send_email", null))
        assertEquals(Category.TRANSFER, ActionGate.classify("uic_iqoption_open_option", null))
        assertNull(ActionGate.classify("uic_iqoption_get_trade_result", null))
        assertNull(ActionGate.classify("mcp_shop__list_orders", null))
    }

    @Test
    fun shellAndJs() {
        assertEquals(Category.DELETE, ActionGate.classify("shell", "rm -f /sdcard/DCIM/foto.jpg"))
        assertEquals(Category.PURCHASE, ActionGate.classify("browser_js", "document.querySelector('#buy').click(); 'Comprar agora'"))
        assertNull(ActionGate.classify("browser_js", "document.title"))
    }

    @Test
    fun policyModes() {
        assertFalse(ActionGate.requiresConfirmation("auto", null, Category.PURCHASE))
        assertTrue(ActionGate.requiresConfirmation("ask", null, Category.PURCHASE))
        assertFalse(ActionGate.requiresConfirmation("ask", listOf("delete"), Category.SEND))
    }

    @Test
    fun clickApprovalKeyIgnoresIndex() {
        val a = ActionGate.approvalKey("browser_click", "{\"index\":3}", "Comprar agora", "loja.com")
        val b = ActionGate.approvalKey("browser_click", "{\"index\":7}", "Comprar  agora", "loja.com")
        assertEquals(a.replace("  ", " "), b.replace("  ", " "))
    }
}
