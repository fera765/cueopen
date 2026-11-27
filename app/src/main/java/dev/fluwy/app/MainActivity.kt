package dev.fluwy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.fluwy.app.ui.*
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.ui.theme.FluwyTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val pendingSession = mutableStateOf<String?>(null)
    private val sharedText = mutableStateOf<String?>(null)
    private val pendingRoute = mutableStateOf<String?>(null)
    private val pendingEntry = mutableStateOf<Long?>(null)
    private val pendingMicrophonePermission = mutableStateOf(false)
    private val microphonePermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        android.widget.Toast.makeText(
            this,
            if (granted) "Microfone autorizado. Invoque o assistente novamente." else "O microfone não foi autorizado.",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onResume() {
        super.onResume()
        // palco invisível do browser do agente (WebView fora da janela não renderiza SPAs)
        dev.fluwy.app.browser.OffscreenStage.onActivityResumed(this)
        // Hub de conectores sobe sozinho ao abrir o app (se instalado); para por inatividade para poupar bateria.
        if (!AppExit.exited(this)) runCatching { dev.fluwy.app.connectors.ActivepiecesHub.onAppForeground() }
        // Live Server: projetos publicados voltam ao ar sozinhos, na mesma URL.
        if (!AppExit.exited(this) && !dev.fluwy.app.live.LiveServer.restoreStarted) {
            dev.fluwy.app.live.LiveServer.restoreStarted = true
            (application as FluwyApp).applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { dev.fluwy.app.live.LiveServer.restoreActive() } }
        }
    }

    override fun onPause() {
        dev.fluwy.app.browser.OffscreenStage.onActivityPaused(this)
        super.onPause()
    }

    override fun onDestroy() {
        dev.fluwy.app.browser.OffscreenStage.onActivityDestroyed(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppExit.reopen(application as FluwyApp)
        enableEdgeToEdge()
        handleIntent(intent)
        // Voltar não destrói a Activity: ela fica viva em segundo plano e o browser dos agentes segue com janela.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            val app = FluwyApp.instance
            // só o tema é observado aqui: evita recompor o app inteiro a cada atualização do agente
            val theme by remember { app.store.state.map { it.settings.theme }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(app.store.state.value.settings.theme)
            val startOnboarding = remember { !app.store.state.value.onboarded }
            var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            FluwyTheme(theme) {
                Box(Modifier.fillMaxSize().background(Fluwy.c.bg)) {
                    val nav = rememberNavController()
                    FluwyNav(nav, startOnboarding = startOnboarding)
                    BrowserPermissionHost()
                    dev.fluwy.app.ui.NotificationOverlay(nav, pendingEntry)
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
                    val route = pendingRoute.value
                    LaunchedEffect(route) {
                        if (route != null) {
                            nav.navigate(route) { launchSingleTop = true }
                            pendingRoute.value = null
                        }
                    }
                    val askForMicrophone = pendingMicrophonePermission.value
                    LaunchedEffect(askForMicrophone) {
                        if (askForMicrophone) {
                            pendingMicrophonePermission.value = false
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                microphonePermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                android.widget.Toast.makeText(this@MainActivity, "Microfone já autorizado. Invoque o assistente novamente.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    val shared = sharedText.value
                    LaunchedEffect(shared) { if (shared != null) { nav.navigate("home?draft=${Uri.encode(shared)}"); sharedText.value = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onStop() {
        super.onStop()
        runCatching { FluwyApp.instance.persistRuntimeState() }
        FluwyApp.instance.store.flushAsync()
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val assistantChat = if (i.action == dev.fluwy.app.assistant.AssistantRoutingPolicy.ACTION_OPEN_CHAT) {
            i.getStringExtra(dev.fluwy.app.assistant.AssistantRoutingPolicy.EXTRA_SESSION_ID)
                ?.takeIf { FluwyApp.instance.store.session(it) != null }
        } else null
        when (i.action) {
            dev.fluwy.app.assistant.AssistantRoutingPolicy.ACTION_OPEN_SETTINGS -> pendingRoute.value = "settings"
            dev.fluwy.app.assistant.AssistantRoutingPolicy.ACTION_OPEN_CHAT -> {
                pendingSession.value = assistantChat
            }
            dev.fluwy.app.assistant.AssistantRoutingPolicy.ACTION_REQUEST_MICROPHONE -> pendingMicrophonePermission.value = true
            // Chegou depois do desbloqueio (a Activity não abre sobre a tela bloqueada): agora o comando segue.
            dev.fluwy.app.assistant.ACTION_RUN_AFTER_UNLOCK -> i.getStringExtra(dev.fluwy.app.assistant.AssistantRoutingPolicy.EXTRA_TRANSCRIPT)?.let { text ->
                sendBroadcast(Intent(this, dev.fluwy.app.assistant.AssistantCommandReceiver::class.java).apply {
                    action = dev.fluwy.app.assistant.AssistantRoutingPolicy.ACTION_SUBMIT_COMMAND
                    putExtra(dev.fluwy.app.assistant.AssistantRoutingPolicy.EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString())
                    putExtra(dev.fluwy.app.assistant.AssistantRoutingPolicy.EXTRA_TRANSCRIPT, text)
                })
                i.removeExtra(dev.fluwy.app.assistant.AssistantRoutingPolicy.EXTRA_TRANSCRIPT)  // não repete ao recriar a tela
            }
        }
        i.getLongExtra(dev.fluwy.app.notify.NotificationCenter.EXTRA_ENTRY, -1L).takeIf { it > 0 }?.let { pendingEntry.value = it; i.removeExtra(dev.fluwy.app.notify.NotificationCenter.EXTRA_ENTRY) }
        val extraId = i.getStringExtra(SessionNotificationRouting.EXTRA_SESSION_ID)
            ?: i.getStringExtra(SessionNotificationRouting.EXTRA_SESSION)
        val deepLinkId = i.data?.takeIf { it.scheme == "fluwy" && it.host == "chat" }
            ?.pathSegments?.lastOrNull()
        val candidate = SessionNotificationRouting.candidateId(extraId, deepLinkId)
        // IDs ausentes/desconhecidos não podem criar uma rota chat/{id} inexistente.
        if (i.action != dev.fluwy.app.assistant.AssistantRoutingPolicy.ACTION_OPEN_CHAT) {
            pendingSession.value = candidate?.takeIf { FluwyApp.instance.store.session(it) != null }
        }
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            lifecycleScope.launch {
                val fileNote = uri?.let { u ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val name = u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\')
                                ?.replace("..", "_")?.ifBlank { "arquivo" } ?: "arquivo"
                            val dest = File(FluwyApp.instance.sandbox.workspaceRoot, "inbox/$name").apply { parentFile?.mkdirs() }
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
fun FluwyNav(nav: NavHostController, startOnboarding: Boolean) {
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
        composable("chat/{id}") { e -> WithSessionDrawer(nav) { openDrawer -> ChatScreen(nav, e.arguments!!.getString("id")!!, onMenu = openDrawer) } }
        composable("computer/{id}?tab={tab}", arguments = listOf(
            navArgument("id") { type = NavType.StringType },
            navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { e -> ComputerScreen(nav, e.arguments!!.getString("id")!!, e.arguments?.getString("tab")) }
        composable("replay/{id}") { e -> ReplayScreen(nav, e.arguments!!.getString("id")!!) }
        composable("agents") { AgentsScreen(nav) }
        composable("agentbots") { AgentBotsScreen(nav) }
        composable("importdata") { ImportDataScreen(nav) }
        composable("agent/{id}?bot={bot}&section={section}", arguments = listOf(
            navArgument("id") { type = NavType.StringType },
            navArgument("bot") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { e -> AgentEditScreen(nav, e.arguments!!.getString("id")!!, e.arguments?.getString("bot"), e.arguments?.getString("section")) }
        composable("skills") { SkillsScreen(nav) }
        composable("skill/{id}") { e -> SkillEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("scheduled") { ScheduledScreen(nav) }
        composable("workflows") { dev.fluwy.app.ui.WorkflowsScreen(nav) }
        composable("workflow/{id}") { e -> dev.fluwy.app.ui.WorkflowEditorScreen(nav, e.arguments!!.getString("id")!!) }
        composable("live") { dev.fluwy.app.ui.LiveVoiceScreen(nav) }
        composable("review/{run}/{node}") { e -> dev.fluwy.app.ui.ReviewScreen(nav, e.arguments!!.getString("run")!!, e.arguments!!.getString("node")!!) }
        composable("notif/{id}") { e -> dev.fluwy.app.ui.NotificationPageScreen(nav, e.arguments!!.getString("id")!!.toLongOrNull() ?: 0L) }
        composable("scheduled/{id}") { e -> ScheduledEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("knowledge") { KnowledgeScreen(nav) }
        composable("knowledge/{id}") { e -> KnowledgeEditScreen(nav, e.arguments!!.getString("id")!!) }
        composable("files") { FilesScreen(nav) }
        composable("memory") { MemoryScreen(nav) }
        composable("browserhub") { BrowserHubScreen(nav) }
        composable("browserlogin/{id}?session={session}", arguments = listOf(androidx.navigation.navArgument("session") { nullable = true; defaultValue = null })) { e ->
            BrowserLoginScreen(nav, e.arguments!!.getString("id")!!, e.arguments?.getString("session")) }
        composable("connectors") { ConnectorsScreen(nav) }
        composable("hubcatalog") { HubCatalogScreen(nav) }
        composable("mcpconnector/{id}") { e -> McpConnectorSettingsScreen(nav, e.arguments!!.getString("id")!!) }
        composable("uiconnector/edit/{id}") { e -> UiConnectorEditorScreen(nav, e.arguments!!.getString("id")!!) }
        composable("uiconnector/{id}") { e -> UiConnectorScreen(nav, e.arguments!!.getString("id")!!) }
        composable("settings") { SettingsScreen(nav) }
        composable("proxy") { ProxyScreen(nav) }
        composable("providers") { ProvidersScreen(nav) }
        composable("channels") { ChannelsScreen(nav) }
        composable("usage") { UsageScreen(nav) }
        composable("pages?space={space}", arguments = listOf(androidx.navigation.navArgument("space") { nullable = true; defaultValue = null })) { e -> PagesScreen(nav, e.arguments?.getString("space")) }
        composable("page/{id}") { e -> PageScreen(nav, e.arguments!!.getString("id")!!) }
        composable("wallet") { WalletScreen(nav) }
        composable("vault") { VaultScreen(nav) }
        composable("wallet/send") { WalletSendScreen(nav) }
        composable("wallet/receive") { WalletReceiveScreen(nav) }
        composable("wallet/swap") { WalletSwapScreen(nav) }
        composable("wallet/more") { WalletMoreScreen(nav) }
        composable("wallet/market") { WalletMarketScreen(nav) }
        composable("sandbox") { SandboxScreen(nav) }
        composable("terminal") { StandaloneTerminalScreen(nav) }
    }
}
