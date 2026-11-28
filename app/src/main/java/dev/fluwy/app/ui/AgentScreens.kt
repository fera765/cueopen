package dev.fluwy.app.ui
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.BasicTextField
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.*
import dev.fluwy.app.scheduler.Scheduler
import kotlinx.coroutines.launch
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AgentsScreen(nav: NavController) {
    val st by FluwyApp.instance.store.state.collectAsStateWithLifecycle()
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Agents", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("agent/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Criar Agent") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FluwyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row { agentColors.take(3).forEachIndexed { i, col -> AgentFace(Color(col), agentFaces[i], 34.dp, Modifier.offset(x = (-10 * i).dp)) } }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Agents especializados", fontWeight = FontWeight.SemiBold)
                            Text("Cada Agent tem instruções, skills, memória e modelo próprios.", fontSize = 13.sp, color = c.ink2)
                        }
                    }
                }
            }
            item {
                val bots = remember { dev.fluwy.app.agent.BotCatalog.all() }
                FluwyCard(onClick = { nav.navigate("agentbots") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.SmartToy, null, tint = c.accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Agentes bots", fontWeight = FontWeight.SemiBold)
                            Text("${bots.size} agentes bots para configurar e adicionar.", fontSize = 13.sp, color = c.ink2)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                    }
                }
            }
            if (st.agents.isEmpty()) item { EmptyState(Icons.Rounded.Groups, "Nenhum Agent ainda", "Crie um Agent para tarefas recorrentes — ex.: pesquisador, dev, social media.") }
            items(st.agents, key = { it.id }) { a ->
                FluwyCard(onClick = { nav.navigate("agent/${a.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentFace(Color(a.color), a.face, 44.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                            Text(a.instructions.ifBlank { a.mission.ifBlank { "Sem instruções" } }, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (a.autonomous) Text("● Autônomo · ${a.actions} ações", fontSize = 11.sp, color = c.ok)
                        }
                        IconButton({ nav.navigate("home?agent=${a.id}") }) { Icon(Icons.Rounded.ChatBubbleOutline, "Nova tarefa") }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fluwy.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = c.ink2)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
    }
}

// ============================ SKILLS ============================
