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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // só o tema é observado aqui: evita recompor o app inteiro a cada atualização do agente
            val theme by remember { app.store.state.map { it.settings.theme }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(app.store.state.value.settings.theme)
            val startOnboarding = remember { !app.store.state.value.onboarded }
            var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            CueTheme(theme) {
                Box(Modifier.fillMaxSize().background(Cue.c.bg)) {
                    val nav = rememberNavController()
                    CueNav(nav, startOnboarding = startOnboarding)
                    if (splash) SplashScreen(onDone = { splash = false })
                    val ps = pendingSession.value
                    LaunchedEffect(ps) {
                        if (ps != null) {
                            val current = nav.currentBackStackEntry
                            val currentSession = current?.takeIf { it.destination.route == "chat/{id}" }
                                ?.arguments?.getString("id")
                            if (SessionNotificationRouting.shouldOpen(currentSession, ps)) {
                                nav.navigate("chat/${Uri.encode(ps)}") { launchSingleTop = true }
                            }
                            pendingSession.value = null
                        }
                    }
                    val shared = sharedText.value
                    LaunchedEffect(shared) { if (shared != null) { nav.navigate("home?draft=${Uri.encode(shared)}"); sharedText.value = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onStop() { super.onStop(); CueApp.instance.store.flushAsync() }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val extraId = i.getStringExtra(SessionNotificationRouting.EXTRA_SESSION_ID)
            ?: i.getStringExtra(SessionNotificationRouting.EXTRA_SESSION)
        val deepLinkId = i.data?.takeIf { it.scheme == "cueopen" && it.host == "chat" }
            ?.pathSegments?.lastOrNull()
        val candidate = SessionNotificationRouting.candidateId(extraId, deepLinkId)
        // IDs ausentes/desconhecidos não podem criar uma rota chat/{id} inexistente.
        pendingSession.value = candidate?.takeIf { CueApp.instance.store.session(it) != null }
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            lifecycleScope.launch {
                val fileNote = uri?.let { u ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val name = u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\')
                                ?.replace("..", "_")?.ifBlank { "arquivo" } ?: "arquivo"
                            val dest = File(CueApp.instance.sandbox.workspaceRoot, "inbox/$name").apply { parentFile?.mkdirs() }
                            contentResolver.openInputStream(u)?.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                                ?: error("O arquivo compartilhado não pôde ser aberto.")
                            "\n[Arquivo recebido: /root/workspace/inbox/$name]"
                        }.getOrNull()
                    }
                }.orEmpty()
                sharedText.value = (text.orEmpty() + fileNote).ifBlank { null }
            }
        }
    }
}

@Composable
fun CueNav(nav: NavHostController, startOnboarding: Boolean) {
    NavHost(nav, startDestination = if (startOnboarding) "onboarding" else "home",
        // A Home tem um drawer; não animar sua saída/retorno evita disputar frames com o drawer.
        enterTransition = {
            if (initialState.destination.route?.startsWith("home") == true) EnterTransition.None else fadeIn(tween(110))
        },
        exitTransition = {
            if (initialState.destination.route?.startsWith("home") == true) ExitTransition.None else fadeOut(tween(80))
        },
        popEnterTransition = {
            if (targetState.destination.route?.startsWith("home") == true) EnterTransition.None else fadeIn(tween(110))
        },
        popExitTransition = {
            if (targetState.destination.route?.startsWith("home") == true) ExitTransition.None else fadeOut(tween(80))
        },
    ) {
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
        composable("memory") { MemoryScreen(nav) }
        composable("connectors") { ConnectorsScreen(nav) }
        composable("mcpconnector/{id}") { e -> McpConnectorSettingsScreen(nav, e.arguments!!.getString("id")!!) }
        composable("uiconnector/edit/{id}") { e -> UiConnectorEditorScreen(nav, e.arguments!!.getString("id")!!) }
        composable("uiconnector/{id}") { e -> UiConnectorScreen(nav, e.arguments!!.getString("id")!!) }
        composable("settings") { SettingsScreen(nav) }
        composable("proxy") { ProxyScreen(nav) }
        composable("wallet") { WalletScreen(nav) }
        composable("wallet/send") { WalletSendScreen(nav) }
        composable("wallet/receive") { WalletReceiveScreen(nav) }
        composable("wallet/swap") { WalletSwapScreen(nav) }
        composable("wallet/more") { WalletMoreScreen(nav) }
        composable("wallet/market") { WalletMarketScreen(nav) }
        composable("sandbox") { SandboxScreen(nav) }
        composable("terminal") { StandaloneTerminalScreen(nav) }
    }
}
