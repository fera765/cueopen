package dev.fluwy.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ThemeMode
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
fun SandboxScreen(nav: NavController) {
    val app = FluwyApp.instance
    val state by app.sandbox.state.collectAsStateWithLifecycle()
    val log by app.sandbox.log.collectAsStateWithLifecycle()
    val st by app.store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = Fluwy.c
    var confirm by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Computador do agente", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FluwyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Terminal, null, tint = Color(0xFFFF8A63))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Linux local (Alpine 3.21)", fontWeight = FontWeight.SemiBold)
                        Text(when (state) {
                            Sandbox.State.READY -> "Pronto"; Sandbox.State.INSTALLING -> "Instalando…"
                            Sandbox.State.ERROR -> "Erro na instalação"; Sandbox.State.NOT_INSTALLED -> "Não instalado"
                        }, color = when (state) { Sandbox.State.READY -> c.ok; Sandbox.State.ERROR -> c.err; else -> c.ink2 }, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Roda no próprio celular via proot — sem root, sem nuvem. O agente usa este computador para executar código, instalar pacotes e servir sites. ~150 MB após a instalação.",
                    fontSize = 13.sp, color = c.ink2)
            }
            FluwyField(st.settings.sandboxPackages, { v -> app.store.updateSettings { it.copy(sandboxPackages = v) } }, "Pacotes iniciais (apk add)")
            when (state) {
                Sandbox.State.NOT_INSTALLED, Sandbox.State.ERROR -> PillButton("Instalar", Icons.Rounded.Download, modifier = Modifier.fillMaxWidth()) {
                    scope.launch { app.sandbox.install(st.settings.sandboxPackages) }
                }
                Sandbox.State.INSTALLING -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                Sandbox.State.READY -> {
                    PillButton("Abrir terminal", Icons.Rounded.Terminal, modifier = Modifier.fillMaxWidth()) { nav.navigate("terminal") }
                    PillButton("Reinstalar", Icons.Rounded.Refresh, primary = false, modifier = Modifier.fillMaxWidth()) { confirm = true }
                    PillButton("Parar servidor de terminal", Icons.Rounded.Stop, primary = false, modifier = Modifier.fillMaxWidth()) { app.sandbox.stopAxs() }
                }
            }
            if (log.isNotBlank()) {
                Text(log.takeLast(6000), Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)).padding(12.dp),
                    color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Reinstalar sandbox?") },
        text = { Text("Apaga o sistema Alpine (pacotes instalados). Seus arquivos em /root são mantidos.") },
        confirmButton = { TextButton({ confirm = false; app.sandbox.uninstall(); scope.launch { app.sandbox.install(st.settings.sandboxPackages) } }) { Text("Reinstalar") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
}
