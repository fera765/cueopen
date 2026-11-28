package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

/** Recursos do Android (com opt-in por agente). Devolve null quando a ferramenta não é desta família. */
internal suspend fun AgentRunner.deviceTools(inv: ToolInvocation): String? = with(inv) {
    when (call.name) {
        "device_notifications" -> dev.fluwy.app.device.NotifStore.recent(s("filter"), (i("limit") ?: 30).coerceIn(1, 100))
            .joinToString("\n") { n ->
                val head = "[${java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(n.time))}]" + (if (n.id.isNotBlank()) " id=${n.id}" else "") +
                    " ${n.app}: ${n.title}" + (if (n.replyable) " [pode responder]" else "")
                if (n.conversation.isNotEmpty()) head + "\n" + n.conversation.joinToString("\n") { "    $it" }.take(1_500)
                else "$head — ${n.text}".take(300)
            }
            .ifBlank { "Nenhuma notificação capturada ainda." }
        "device_notification_reply" -> dev.fluwy.app.device.NotificationReply.send(ctx, s("id"), SecretVault.resolve(sessionId, s("text")))
        "send_file", "device_share_file" -> {
            val f = AgentFilePathPolicy.readableWorkspace(sandbox.workspaceFor(sessionId), sandbox.connectorsDir, s("path"))
            if (f == null || !f.isFile) "ERRO: arquivo não encontrado no workspace (recebido: \"${s("path").take(120)}\"). Confira com file_list."
            else if (call.name == "send_file") {
                val ch = dev.fluwy.app.share.ChannelSender.channel(s("channel"))
                if (ch == null) "ERRO: canal inválido. Use: " + dev.fluwy.app.share.ChannelSender.configured(channelConfig()).joinToString { it.id }
                else dev.fluwy.app.share.ChannelSender.send(ch, channelConfig(), f, SecretVault.resolve(sessionId, s("caption")))
            } else dev.fluwy.app.share.ChannelSender.shareToApp(ctx, s("app"), f, s("text"), s("phone"))
        }
        "device_open_link" -> {
            val url = s("url").trim()
            val scheme = url.substringBefore(':').lowercase()
            if (scheme !in setOf("https", "http", "spotify", "geo", "mailto", "tel", "whatsapp", "market", "youtube", "vnd.youtube", "uber", "instagram", "fb", "tg", "sms", "smsto"))
                "RECUSADO: tipo de link não permitido ($scheme). Use links https ou de apps conhecidos (spotify:, geo:, mailto:, tel:, whatsapp:)."
            else runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val target = runCatching { ctx.packageManager.resolveActivity(intent, 0)?.loadLabel(ctx.packageManager)?.toString() }.getOrNull()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctx.startActivity(intent) }
                kotlinx.coroutines.delay(1_500)
                "Abri o link" + (target?.let { " em $it" } ?: "") + ". Se precisar tocar em um botão (ex.: Enviar), use device_accessibility_tap."
            }.getOrElse { "ERRO: nenhum app do aparelho abriu esse link (${it.javaClass.simpleName})." }
        }
        "device_accessibility_tap" -> {
            val screen = dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
            val hit = dev.fluwy.app.device.ScreenMatch.find(screen, s("text"), i("occurrence") ?: 1)
            if (hit == null) {
                val labels = dev.fluwy.app.device.ScreenMatch.elements(screen).mapNotNull { it.second.takeIf { l -> l.isNotBlank() } }.distinct().take(25)
                "Não achei \"${s("text").take(60)}\" na tela atual. Textos visíveis: " + labels.joinToString(" | ").take(700)
            } else {
                val out = dev.fluwy.app.device.DeviceAccessibilityActions.click(sessionId, hit.snapshotId, hit.elementId)
                "Toquei em \"${hit.label.take(60)}\". " + out.take(200)
            }
        }
        "device_calendar_read" -> dev.fluwy.app.device.DeviceActions.calendar(ctx, i("days") ?: 7)
        "device_calendar_create" -> dev.fluwy.app.device.DeviceActions.createCalendarEvent(
            ctx, s("title"), s("start_epoch_ms").toLongOrNull() ?: 0L, s("end_epoch_ms").toLongOrNull() ?: 0L, s("description"), s("location"))
        "device_contacts" -> dev.fluwy.app.device.DeviceActions.contacts(ctx, s("query"))
        "device_call_log" -> dev.fluwy.app.device.DeviceActions.callLog(ctx, i("limit") ?: 20)
        "device_location" -> dev.fluwy.app.device.DeviceActions.location(ctx)
        "device_bluetooth" -> dev.fluwy.app.device.DeviceActions.bluetoothDevices(ctx)
        "device_bluetooth_pair" -> dev.fluwy.app.device.DeviceActions.openBluetoothSettings(ctx)
        "device_dial" -> dev.fluwy.app.device.DeviceActions.openDialer(ctx, s("phone"))
        "device_microphone" -> dev.fluwy.app.device.DeviceActions.microphone(ctx)
        "device_accessibility_open_app" -> dev.fluwy.app.device.DeviceAccessibilityActions.openApp(ctx, sessionId, s("app"))
        "device_accessibility_read_screen" -> dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
        "device_accessibility_inspect_image" -> {
            val request = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User } as? ChatItem.User
            when {
                request == null || !dev.fluwy.app.device.AccessibilityVisualInspectionPolicy
                    .explicitlyRequestsPostOrImage(request.text) ->
                    "RECUSADO: captura visual só é permitida como fallback de um pedido explícito para ler/analisar um post de app social."
                !dev.fluwy.app.device.AccessibilityVisualInspectionPolicy.canInspectImage(
                    request.text,
                    currentScreenWasRead = sessionId in accessibilityReadScreenCompleted,
                    inspectionAlreadyUsed = sessionId in visualInspectionUsed,
                ) -> if (sessionId in visualInspectionUsed)
                    "RECUSADO: já fiz a única inspeção visual desta tarefa; use o resultado recebido e não capture outras telas/posts."
                else "RECUSADO: primeiro leia a tela acessível atual e verifique o texto/descrição da imagem; só então use este fallback se a árvore não revelar o conteúdo."
                else -> {
                    visualInspectionUsed.add(sessionId)
                    val jpeg = try {
                        dev.fluwy.app.device.DeviceAccessibilityActions.inspectImage(
                            sessionId, s("snapshot_id"), i("element_id") ?: -1,
                        )
                    } catch (t: Throwable) {
                        if (isStaleAccessibilitySnapshotFailure(dev.fluwy.app.prompts.Prompts.render("vision.4", mapOf<String, Any?>("lb" to "{{")), t.message.orEmpty()))
                            visualInspectionUsed.remove(sessionId)
                        throw t
                    }
                    val prompt = dev.fluwy.app.prompts.Prompts.render("vision.3", mapOf<String, Any?>("lb" to "{{")) +
                        dev.fluwy.app.prompts.Prompts.render("vision.2", mapOf<String, Any?>("lb" to "{{")) +
                        dev.fluwy.app.prompts.Prompts.render("vision.1", mapOf<String, Any?>("lb" to "{{")) +
                        "Pedido do usuário: ${request.text.take(1200)}"
                    val visual = try {
                        llm.inspectImage(prompt, jpeg, model = model, conversationId = sessionId)
                    } finally {
                        jpeg.fill(0)
                    }
                    "Análise visual temporária do recorte solicitado (imagem não salva no histórico): $visual"
                }
            }
        }
        "device_accessibility_click" -> dev.fluwy.app.device.DeviceAccessibilityActions.click(
            sessionId, s("snapshot_id"), i("element_id") ?: -1)
        "device_accessibility_type" -> {
            val rawText = s("text")
            val exactSecret = SecretVault.resolveExact(sessionId, rawText)
            dev.fluwy.app.device.DeviceAccessibilityActions.typeText(
                sessionId, s("snapshot_id"), i("element_id") ?: -1,
                exactSecret ?: SecretVault.resolve(sessionId, rawText), exactSecret != null)
        }
        "device_accessibility_scroll" -> dev.fluwy.app.device.DeviceAccessibilityActions.scroll(
            sessionId, s("snapshot_id"), i("element_id") ?: -1, s("direction"))
        "device_accessibility_back" -> dev.fluwy.app.device.DeviceAccessibilityActions.back(sessionId)
        "device_notify" -> { notifyRaw(s("title").ifBlank { "Fluwy" }, plainText(s("text"))); "Notificação enviada." }
        "device_overlay" -> { dev.fluwy.app.device.Overlay.show(ctx, s("text")); "Balão exibido." }
        "device_screenshot" -> {
            val request = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User } as? ChatItem.User
            when {
                request != null && dev.fluwy.app.device.AccessibilityVisualInspectionPolicy
                    .explicitlyRequestsPostOrImage(request.text) ->
                    "RECUSADO: não vou capturar a tela inteira para ler um post. Use a árvore acessível e, somente se ela não revelar a imagem, device_accessibility_inspect_image no recorte atual."
                request == null || !dev.fluwy.app.device.DeviceScreenshotPolicy
                    .explicitlyRequestsFullScreenCapture(request.text) ->
                    "RECUSADO: screenshot da tela inteira requer pedido explícito do usuário; não use como inspeção automática."
                else -> {
                    val f = java.io.File(sandbox.workspaceFor(sessionId), "screenshot_${System.currentTimeMillis()}.png")
                    if (dev.fluwy.app.device.ScreenCapture.capture(ctx, f)) {
                        store.addItem(sessionId, ChatItem.Media(path = f.absolutePath, caption = "Captura de tela"))
                        "Captura salva e exibida: ${f.name}"
                    } else "Não foi possível capturar (permissão de captura não concedida)."
                }
            }
        }
        else -> null
    }
}
