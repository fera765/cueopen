package dev.fluwy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.agent.ActionGate
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.WalletAgentPolicyStore

/** Rascunho das permissões da Carteira Solana (gravado só no Salvar). */
class AgentWalletDraft(store: WalletAgentPolicyStore, id: String) {
    var readAddress by mutableStateOf(store.canAgentReadAddress(id))
    var readBalance by mutableStateOf(store.canAgentReadBalance(id))
    var requestTransfer by mutableStateOf(store.canAgentRequestTransfer(id))
    var autonomousTransfer by mutableStateOf(store.canAgentAutonomousTransfer(id))
    var dayTrade by mutableStateOf(store.canAgentDayTrade(id))
    var access by mutableStateOf(readAddress || readBalance || requestTransfer || dayTrade)

    fun save(store: WalletAgentPolicyStore, id: String) = store.setAgentCapabilities(
        id,
        readAddress = access && readAddress,
        readBalance = access && readBalance,
        requestTransfer = access && requestTransfer,
        autonomousTransfer = access && requestTransfer && autonomousTransfer,
        dayTrade = access && dayTrade,
    )
}

/** Seção "Ferramentas e permissões": ferramentas, UI conectores, Android, confirmações e carteira. */
@Composable
fun AgentToolsPanel(a: AgentProfile, wallet: AgentWalletDraft, onChange: (AgentProfile) -> Unit, onDeviceChange: (AgentProfile) -> Unit) {
    val c = Fluwy.c
    var tab by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Ferramentas", tab == 0) { tab = 0 }
        Chip("Confirmações", tab == 1) { tab = 1 }
        Chip("Carteira" + if (wallet.access) " ✓" else "", tab == 2) { tab = 2 }
    }
    when (tab) {
        0 -> {
            ToggleRow("Terminal Linux", "Executar comandos no sandbox", a.useTerminal) { onChange(a.copy(useTerminal = it)) }
            ToggleRow("Browser", "Navegar e interagir com sites", a.useBrowser) { onChange(dev.fluwy.app.device.BrowserAutonomyPolicy.setBrowserEnabled(a, it)) }
            if (a.useBrowser) ToggleRow("Browser em segundo plano", "Tarefas deste agent continuam usando o Browser com o app fechado. Não agenda nada sozinho.", a.browserAutonomy) {
                onChange(dev.fluwy.app.device.BrowserAutonomyPolicy.setEnabled(a, it))
            }
            ToggleRow("Hub de conectores", "Gmail, Planilhas, Instagram, GitHub e outros apps do Hub (Activepieces).", a.useConnectorHub) { onChange(a.copy(useConnectorHub = it)) }
            val uiHub = dev.fluwy.app.FluwyApp.instance.uiConnectors
            if (uiHub.connectors.isNotEmpty()) {
                Text("UI Conectores", fontSize = 13.sp, color = c.ink2)
                uiHub.connectors.forEach { uc ->
                    ToggleRow("Usar ${uc.spec.name}", uc.spec.description.take(90), uc.spec.id in a.uiConnectorIds) { on ->
                        onChange(a.copy(uiConnectorIds = if (on) (a.uiConnectorIds + uc.spec.id).distinct() else a.uiConnectorIds - uc.spec.id))
                    }
                }
            }
            DevicePermissionsCard(a, onDeviceChange)
        }
        1 -> {
            ToggleRow("100% autônomo (sem confirmações)", "Executa tudo sozinho, inclusive comprar, pagar, apagar e enviar. Só para agents de total confiança.", a.confirmMode == "auto") {
                onChange(a.copy(confirmMode = if (it) "auto" else "ask"))
            }
            if (a.confirmMode == "auto") Text("⚠ Este agent não vai pedir sua confirmação para nada.", fontSize = 12.sp, color = c.warn)
            else {
                Text("Perguntar antes de:", fontSize = 13.sp, color = c.ink2)
                ActionGate.Category.entries.forEach { cat ->
                    val desc = when (cat) {
                        ActionGate.Category.PURCHASE -> "Comprar, finalizar pedido, pagar, assinar, gerar Pix/boleto"
                        ActionGate.Category.TRANSFER -> "Transferir, sacar, apostar, vender, operar em corretoras"
                        ActionGate.Category.DELETE -> "Excluir posts, mensagens, arquivos e itens em sites e conectores"
                        ActionGate.Category.SEND -> "Enviar mensagens e e-mails, publicar, comentar, compartilhar"
                        ActionGate.Category.ACCOUNT -> "Trocar senha, excluir ou desativar conta"
                    }
                    ToggleRow(cat.label, desc, cat.id in a.confirmCategories) { on ->
                        onChange(a.copy(confirmCategories = if (on) (a.confirmCategories + cat.id).distinct() else a.confirmCategories - cat.id))
                    }
                }
                Text("O agent para, mostra um card no chat e notifica. Cada aprovação vale uma vez.", fontSize = 11.sp, color = c.ink3)
            }
        }
        else -> AgentWalletPanel(wallet)
    }
}

@Composable
private fun AgentWalletPanel(w: AgentWalletDraft) {
    val c = Fluwy.c
    ToggleRow("Permitir acesso à carteira", "Só as capacidades marcadas abaixo. Chaves privadas e frase de recuperação nunca são expostas.", w.access) { w.access = it }
    if (!w.access) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow("Consultar endereço público", "Ler o endereço público da carteira local.", w.readAddress) { w.readAddress = it }
        ToggleRow("Consultar saldo SOL", "Saldo on-chain da rede selecionada.", w.readBalance) { w.readBalance = it }
        ToggleRow("Solicitar transferência", "Cria pedidos de envio de SOL; cada um exige aprovação manual.", w.requestTransfer) { w.requestTransfer = it; if (!it) w.autonomousTransfer = false }
        if (w.requestTransfer) {
            ToggleRow("Transferência automática", "Assina e envia sem aprovar a cada vez, respeitando os limites por transação e diário.", w.autonomousTransfer) { w.autonomousTransfer = it }
            if (w.autonomousTransfer) Text("⚠ O agent pode enviar para qualquer endereço sem aprovação. Os limites são o único teto de segurança.", fontSize = 12.sp, color = c.warn)
        }
        ToggleRow("Day trade (mercado + swaps + regras)", "Dados reais de mercado, trocas e stop-loss/take-profit. Não envia SOL a terceiros.", w.dayTrade) { w.dayTrade = it }
        if (w.dayTrade) Text("⚠ Day trade envolve risco de perda; o agent nunca promete lucro.", fontSize = 12.sp, color = c.warn)
    }
}
