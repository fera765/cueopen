package dev.fluwy.app.ui
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.agent.Activity
import dev.fluwy.app.agent.AskTools
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.ui.theme.Fluwy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Card de instalação de um UI Connector que o agente criou e validou. */
@Composable
internal fun ConnectorProposalCard(p: ChatItem.ConnectorProposal, sessionId: String?, nav: NavController?) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    val exists = remember(p.id, p.installed) { app.uiConnectors.isCustom(p.connectorId) }
    val validationValid = remember(p.id, p.yaml, p.validation) {
        dev.fluwy.app.uiconnector.custom.ConnectorAttestationPolicy.isInstallable(p.yaml, p.validation)
    }
    // Capacidades são autorizadas somente no momento da instalação (o usuário pode desmarcar).
    var grantCaps by remember(p.id) { mutableStateOf(true) }
    val secretsReady = sessionId != null && p.loginSecrets.isNotEmpty() &&
        p.loginSecrets.values.all { v -> dev.fluwy.app.agent.SecretVault.missing(sessionId, v).isEmpty() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (p.installed) c.ok else c.accent, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.installed) Icons.Rounded.CheckCircle else Icons.Rounded.Extension, null, tint = if (p.installed) c.ok else c.accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (p.installed) "Conector instalado" else if (validationValid) "Dry-run offline aprovado · pronto para instalar" else "Validação ausente ou desatualizada", fontSize = 12.sp, color = if (p.installed) c.ok else c.ink2)
            }
        }
        Text(p.description, fontSize = 13.sp, color = c.ink2)
        Text("Host: ${p.host}", fontSize = 12.sp, color = c.ink3)
        if (p.tools.isNotEmpty()) Text("Ferramentas do agente: " + p.tools.joinToString(), fontSize = 12.sp, color = c.ink3)
        if (!p.installed && p.capabilities.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { grantCaps = !grantCaps }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(grantCaps, { grantCaps = it })
            Text("Autorizar " + p.capabilities.joinToString { dev.fluwy.app.uiconnector.custom.Capabilities.label(it) } + " (a execução real começa após sua autorização)", fontSize = 12.sp, color = c.ink2)
        }
        if (!p.installed && secretsReady) Text("🔒 Entra com as credenciais que você enviou no formulário seguro (testadas).", fontSize = 12.sp, color = c.ink2)
        error?.let { Text(it, fontSize = 12.sp, color = c.err) }
        if (p.installed) {
            Text("Pronto: ative-o no agente em Agents → editar → UI Conectores.", fontSize = 12.sp, color = c.ink2)
        } else if (!validationValid) {
            Text("Instalação bloqueada. Volte ao criador e rode connector_test novamente para esta definição YAML.", fontSize = 12.sp, color = c.err)
        } else {
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        if (!dev.fluwy.app.uiconnector.custom.ConnectorAttestationPolicy.isInstallable(p.yaml, p.validation)) {
                            error = "A validação não corresponde mais a esta definição. Volte ao criador e teste novamente."
                            busy = false
                            return@launch
                        }
                        app.uiConnectors.saveCustom(p.yaml)
                            .onSuccess {
                                if (grantCaps) p.capabilities.forEach { cap -> app.uiConnectors.setCapabilityGranted(p.connectorId, cap, true) }
                                if (secretsReady && sessionId != null) {
                                    val creds = p.loginSecrets.mapValues { dev.fluwy.app.agent.SecretVault.resolve(sessionId, it.value) }
                                    app.uiConnectors.login(p.connectorId, creds, remember = true)
                                        .onFailure { e -> error = "Instalado, mas o login automático falhou: ${e.message}. Entre manualmente em Conectores." }
                                }
                                if (sessionId != null) app.store.replaceItem(sessionId, p.copy(installed = true))
                            }
                            .onFailure { error = it.message ?: "Falha ao instalar." }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
            ) { Text(if (exists) "Atualizar conector" else "Instalar conector", fontWeight = FontWeight.SemiBold) }
            Text("Dry-run validou o contrato com mocks locais; API/login reais e comandos não foram executados. O agente só usa o conector se você o ativar.", fontSize = 11.sp, color = c.ink3)
        }
    }
}

/** Card de revisão e aplicação explícita para configurações de um único conector. */
@Composable
internal fun ConnectorEditProposalCard(p: ChatItem.ConnectorEditProposal, sessionId: String?) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var showDefinition by remember(p.id) { mutableStateOf(false) }
    val current = app.uiConnectors.editableConfiguration(p.connectorId)
    val settingById = current?.settings.orEmpty().associateBy { it.id }
    val risky = p.settings.keys.any { settingById[it]?.risky == true }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (p.applied) c.ok else c.accent, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.applied) Icons.Rounded.CheckCircle else Icons.Rounded.Tune, null, tint = if (p.applied) c.ok else c.accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.connectorName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (p.applied) "Atualização aplicada" else "Revisar atualização", fontSize = 12.sp, color = if (p.applied) c.ok else c.ink2)
            }
        }
        Text(p.summary, fontSize = 13.sp, color = c.ink2)
        if (p.enabled != null) Text("Habilitado: ${if (p.enabled) "ligado" else "desligado"}", fontSize = 13.sp)
        if (p.agentRequired != null) Text("Agent required?: ${if (p.agentRequired) "ligado" else "desligado"}", fontSize = 13.sp)
        p.settings.forEach { (id, value) ->
            val label = settingById[id]?.label ?: id
            Text("$label → $value", fontSize = 13.sp, color = c.ink)
        }
        if (p.definitionYaml != null) {
            Text("Definição funcional", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            TextButton(onClick = { showDefinition = !showDefinition }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                Text(if (showDefinition) "Ocultar YAML sanitizado" else "Revisar YAML sanitizado")
            }
            if (showDefinition) SelectionContainer {
                Text(
                    p.definitionYaml,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = c.ink2,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()).background(c.bg, RoundedCornerShape(10.dp)).padding(10.dp),
                )
            }
            if (!p.applied) Text(
                "A aprovação grava a definição, mas não executa ações. Mudanças em ferramentas ou ações podem fazer chamadas externas quando forem usadas depois; credenciais e permissões ficam preservadas.",
                fontSize = 12.sp, color = c.warn,
            )
        }
        if (risky && !p.applied) Text("Atenção: esta proposta altera uma configuração marcada como sensível. Revise antes de aplicar.", fontSize = 12.sp, color = c.warn)
        error?.let { Text(it, fontSize = 12.sp, color = c.err) }
        if (!p.applied) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val result = app.uiConnectors.applyEditableConfiguration(
                            p.connectorId, p.baseFingerprint, p.enabled, p.agentRequired, p.settings,
                            definitionYaml = p.definitionYaml,
                            sessionId = sessionId,
                        )
                        result.onSuccess {
                            if (sessionId != null) {
                                app.store.replaceItem(sessionId, p.copy(applied = true))
                                val nextFingerprint = app.uiConnectors.configurationFingerprint(p.connectorId)
                                app.store.editSession(sessionId) { session ->
                                    if (session.connectorEditId == p.connectorId) session.copy(connectorEditFingerprint = nextFingerprint) else session
                                }
                            }
                        }.onFailure { error = it.message ?: "Não foi possível aplicar a atualização." }
                        busy = false
                    }
                },
                enabled = !busy && current != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.bg)
                else Text("Atualizar conector", fontWeight = FontWeight.SemiBold)
            }
            Text("A configuração só muda depois deste toque. Nenhuma rede externa, login ou ação real é executada; validações de definição usam mocks locais.", fontSize = 11.sp, color = c.ink3)
        }
    }
}


/** Card de confirmação de ação sensível (comprar/pagar, apagar, enviar/publicar, transferir, conta). */
@Composable
internal fun ApprovalCard(item: ChatItem.Approval, sessionId: String?) {
    val c = Fluwy.c
    val app = dev.fluwy.app.FluwyApp.instance
    val cat = dev.fluwy.app.agent.ActionGate.Category.of(item.category)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(c.surface).border(1.dp, if (item.state == "pending") c.warn else c.line, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = if (item.state == "pending") c.warn else c.ink3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(cat?.question ?: "Confirmar ação?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.summary, fontSize = 14.sp, color = c.ink2)
        Spacer(Modifier.height(10.dp))
        when (item.state) {
            "pending" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ sessionId?.let { app.agent.decideApproval(it, item, approve = false) } }, Modifier.weight(1f)) { Text("Negar") }
                Button({ sessionId?.let { app.agent.decideApproval(it, item, approve = true) } }, Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Aprovar") }
            }
            "approved", "used" -> Text("✓ Aprovado", fontSize = 12.sp, color = c.ok)
            else -> Text("✕ Negado", fontSize = 12.sp, color = c.err)
        }
    }
}
