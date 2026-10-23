package dev.cueopen.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.cueopen.app.ui.*
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.ui.theme.CueTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val pendingSession = mutableStateOf<String?>(null)
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            val app = CueApp.instance
            val st by app.store.state.collectAsStateWithLifecycle()
            CueTheme(st.settings.theme) {
                Box(Modifier.fillMaxSize().background(Cue.c.bg)) {
                    val nav = rememberNavController()
                    CueNav(nav, startOnboarding = !st.onboarded)
                    val ps = pendingSession.value
                    LaunchedEffect(ps) { if (ps != null) { nav.navigate("chat/$ps"); pendingSession.value = null } }
                    val shared = sharedText.value
                    LaunchedEffect(shared) { if (shared != null) { nav.navigate("home?draft=${Uri.encode(shared)}"); sharedText.value = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onStop() { super.onStop(); CueApp.instance.store.flush() }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("session")?.let { pendingSession.value = it }
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val fileNote = uri?.let { u ->
                runCatching {
                    val name = u.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
                    val dest = File(CueApp.instance.sandbox.workspaceRoot, "inbox/$name").apply { parentFile?.mkdirs() }
                    contentResolver.openInputStream(u)!!.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                    "\n[Arquivo recebido: /root/workspace/inbox/$name]"
                }.getOrNull()
            }.orEmpty()
            sharedText.value = (text.orEmpty() + fileNote).ifBlank { null }
        }
    }
}

@Composable
fun CueNav(nav: NavHostController, startOnboarding: Boolean) {
    NavHost(nav, startDestination = if (startOnboarding) "onboarding" else "home") {
        composable("onboarding") { OnboardingScreen(onDone = { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } }) }
        composable("home?draft={draft}&agent={agent}", arguments = listOf(
            navArgument("draft") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("agent") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { e ->
            HomeScreen(nav, initialDraft = e.arguments?.getString("draft"), initialAgent = e.arguments?.getString("agent"))
        }
        composable("chat/{id}") { e -> ChatScreen(nav, e.arguments!!.getString("id")!!) }
        composable("computer/{id}?tab={tab}", arguments = listOf(
            navArgument("id") { type = NavType.StringType },
            navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { e -> ComputerScreen(nav, e.arguments!!.getString("id")!!, e.arguments?.getString("tab")) }
        composable("replay/{id}") { e -> ReplayScreen(nav, e.arguments!!.getString("id")!!) }
        composable("agents") { AgentsScreen(nav) }
        composable("agent/{id}") { e -> AgentEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("skills") { SkillsScreen(nav) }
        composable("skill/{id}") { e -> SkillEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("scheduled") { ScheduledScreen(nav) }
        composable("scheduled/{id}") { e -> ScheduledEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("knowledge") { KnowledgeScreen(nav) }
        composable("knowledge/{id}") { e -> KnowledgeEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("files") { FilesScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable("sandbox") { SandboxScreen(nav) }
        composable("terminal") { StandaloneTerminalScreen(nav) }
    }
}
