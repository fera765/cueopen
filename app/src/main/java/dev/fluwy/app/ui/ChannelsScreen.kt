package dev.fluwy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.share.ChannelSender
import dev.fluwy.app.ui.theme.Fluwy

/** Configurações › Canais de envio de arquivos (Telegram, Discord, Slack; WhatsApp pelo app). */
@Composable
fun ChannelsScreen(nav: NavController) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val s = st.settings
    val c = Fluwy.c
    val active = ChannelSender.configured(ChannelSender.Config(s.telegramBotToken, s.telegramChatId, s.discordWebhookUrl, s.slackBotToken, s.slackChannelId))
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Canais de envio", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Com um canal configurado, os agentes enviam arquivos e imagens sozinhos (cada envio passa pela confirmação do agente, salvo no modo 100% autônomo). As credenciais ficam só no aparelho e nunca vão para o modelo.", fontSize = 12.sp, color = c.ink3)
            Text("Ativos: " + active.joinToString { it.label }.ifBlank { "nenhum" }, fontSize = 13.sp)
            SectionLabel("Telegram")
            FluwyField(s.telegramBotToken, { v -> app.store.updateSettings { it.copy(telegramBotToken = v.trim()) } }, "Token do bot (@BotFather)", password = true)
            FluwyField(s.telegramChatId, { v -> app.store.updateSettings { it.copy(telegramChatId = v.trim()) } }, "chat_id de destino")
            SectionLabel("Discord")
            FluwyField(s.discordWebhookUrl, { v -> app.store.updateSettings { it.copy(discordWebhookUrl = v.trim()) } }, "URL do webhook do canal", password = true)
            if (s.discordWebhookUrl.isNotBlank() && !ChannelSender.validDiscordWebhook(s.discordWebhookUrl))
                Text("Isso não parece um webhook do Discord (https://discord.com/api/webhooks/…).", fontSize = 12.sp, color = c.err)
            SectionLabel("Slack")
            FluwyField(s.slackBotToken, { v -> app.store.updateSettings { it.copy(slackBotToken = v.trim()) } }, "Token do bot (xoxb-…, escopo files:write)", password = true)
            FluwyField(s.slackChannelId, { v -> app.store.updateSettings { it.copy(slackChannelId = v.trim()) } }, "ID do canal (ex.: C0123…)")
            SectionLabel("WhatsApp")
            Text("O WhatsApp não tem envio automático oficial para contas pessoais: o agente abre o WhatsApp com o arquivo pronto e toca em Enviar (precisa da acessibilidade ligada no agente).", fontSize = 12.sp, color = c.ink3)
        }
    }
}
