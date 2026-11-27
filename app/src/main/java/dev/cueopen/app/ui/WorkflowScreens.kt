package dev.cueopen.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.workflow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val NODE_W = 212f
private const val NODE_H = 84f
private const val WORLD = 4000f

private fun nodeIcon(type: String): ImageVector = when (WfCatalog.of(type).icon) {
    "play" -> Icons.Rounded.PlayArrow
    "schedule" -> Icons.Rounded.Schedule
    "webhook" -> Icons.Rounded.Webhook
    "agent" -> Icons.Rounded.SmartToy
    "apps" -> Icons.Rounded.Apps
    "mcp" -> Icons.Rounded.Extension
    "http" -> Icons.Rounded.Http
    "terminal" -> Icons.Rounded.Terminal
    "set" -> Icons.Rounded.Tune
    "branch" -> Icons.Rounded.CallSplit
    "timer" -> Icons.Rounded.Timer
    "approval" -> Icons.Rounded.ThumbUp
    "notify" -> Icons.Rounded.Notifications
    "ai" -> Icons.Rounded.AutoAwesome
    "decide" -> Icons.Rounded.Psychology
    "review" -> Icons.Rounded.RateReview
    "new" -> Icons.Rounded.FiberNew
    else -> Icons.Rounded.Circle
}

private fun statusColor(status: String?, c: dev.cueopen.app.ui.theme.CueColors): Color? = when (status) {
    "running" -> c.accent
    "ok" -> c.ok
    "error" -> c.err
    "waiting" -> c.warn
    "paused" -> c.warn
    "queued" -> c.ink3
    else -> null
}

private val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

/** Modelo pronto: título, descrição e montagem (ids curtos e legíveis; layout automático). */
private data class WfTemplate(val title: String, val description: String, val icon: String, val make: () -> Workflow)

private fun flow(name: String, nodes: List<WfNode>, vararg edges: WfEdge) = WfLogic.layout(Workflow(name = name, nodes = nodes, edges = edges.toList()), NODE_W, NODE_H)

private fun templates(): List<WfTemplate> = listOf(
    WfTemplate("Em branco", "Comece só com um gatilho manual.", "play") {
        Workflow(name = "Novo fluxo", nodes = listOf(WfNode(type = "trigger_manual", label = "Início", x = 60f, y = 200f)))
    },
    WfTemplate("Novo e-mail → aviso com o e-mail", "A cada 30 min olha o último e-mail do Gmail; se for novo, notifica e o toque abre o e-mail num quadro.", "notify") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "A cada 30 min", config = mapOf("mode" to "interval", "minutes" to "30"))
        val g = WfNode(id = "g$id", type = "connector", label = "Último e-mail", config = mapOf("source" to "hub", "conector" to "gmail", "acao" to "gmail_search_email",
            "campos" to "{\n  \"max_results\": 1\n}"))
        val n0 = WfNode(id = "n$id", type = "if_new", label = "É novo?", config = mapOf("key" to "{{${g.id}.data.results.messages.0.id}}", "first" to "new"))
        val a = WfNode(id = "a$id", type = "ai", label = "Formatar e-mail", config = mapOf("maxTokens" to "1200", "prompt" to
            "Formate este e-mail para leitura no celular, em português, sem cabeçalhos técnicos. Primeira linha: De: <remetente>. Segunda: Assunto: <assunto>. Terceira: Data: <data>. Depois uma linha em branco e o texto do e-mail (resuma se passar de 25 linhas).\n\n{{${g.id}.data.results.messages.0}}"))
        val nt = WfNode(id = "m$id", type = "notify", label = "Avisar", config = mapOf("title" to "📬 Novo e-mail", "text" to "{{${g.id}.data.results.messages.0.subject}}",
            "detail" to "{{${a.id}}}", "open" to "modal"))
        flow("Novo e-mail → aviso", listOf(t, g, n0, a, nt), WfEdge(from = t.id, to = g.id), WfEdge(from = g.id, to = n0.id),
            WfEdge(from = n0.id, to = a.id, port = "true"), WfEdge(from = a.id, to = nt.id))
    },
    WfTemplate("Responder e-mails importantes (com aprovação)", "A cada 15 min: e-mail novo → a IA decide se precisa de resposta → escreve o rascunho → você revisa (com fotos e anexos), edita e aprova → envia.", "review") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "A cada 15 min", config = mapOf("mode" to "interval", "minutes" to "15"))
        val g = WfNode(id = "g$id", type = "connector", label = "Último e-mail", config = mapOf("source" to "hub", "conector" to "gmail", "acao" to "gmail_search_email",
            "campos" to "{\n  \"max_results\": 1\n}"))
        val nw = WfNode(id = "n$id", type = "if_new", label = "É novo?", config = mapOf("key" to "{{${g.id}.data.results.messages.0.id}}", "first" to "new"))
        val d = WfNode(id = "d$id", type = "ai_decide", label = "Precisa de resposta?", config = mapOf("input" to "{{${g.id}}}", "criteria" to
            "É um e-mail importante que precisa da minha resposta? Considere importantes: clientes, propostas comerciais, atendimento/suporte, pessoas que escrevem direto para mim. Não responda: propaganda, newsletters, notificações automáticas, avisos de sistema e no-reply."))
        val a = WfNode(id = "a$id", type = "ai", label = "Escrever rascunho", config = mapOf("maxTokens" to "700", "prompt" to
            "Escreva a resposta para o e-mail abaixo, em português, cordial e objetiva, no tom de quem recebeu. Não invente fatos, preços ou prazos: se faltar informação, diga que vai verificar. Responda SÓ com o corpo do e-mail (sem assunto).\n\nE-MAIL:\n{{${g.id}.data.results.messages.0 | text}}"))
        val rv = WfNode(id = "r$id", type = "review", label = "Revisar resposta", config = mapOf("title" to "Responder: {{${g.id}.data.results.messages.0.subject}}",
            "item" to "{{${g.id}}}", "draft" to "{{${a.id}}}", "mode" to "ask", "hours" to "24"))
        val sd = WfNode(id = "s$id", type = "connector", label = "Enviar resposta", config = mapOf("source" to "hub", "conector" to "gmail", "acao" to "send_email",
            "campos" to "{\n  \"receiver\": [\"{{${g.id}.data.results.messages.0.from | email}}\"],\n  \"subject\": \"Re: {{${g.id}.data.results.messages.0.subject}}\",\n  \"body_text\": \"{{${rv.id}.draft}}\"\n}"))
        val nt = WfNode(id = "m$id", type = "notify", label = "Avisar", config = mapOf("title" to "✉ Resposta enviada", "text" to "Re: {{${g.id}.data.results.messages.0.subject}}", "detail" to "{{${rv.id}.draft}}", "open" to "modal", "layout" to "markdown"))
        flow("Responder e-mails importantes", listOf(t, g, nw, d, a, rv, sd, nt), WfEdge(from = t.id, to = g.id), WfEdge(from = g.id, to = nw.id),
            WfEdge(from = nw.id, to = d.id, port = "true"), WfEdge(from = d.id, to = a.id, port = "true"), WfEdge(from = a.id, to = rv.id),
            WfEdge(from = rv.id, to = sd.id, port = "true"), WfEdge(from = sd.id, to = nt.id))
    },
    WfTemplate("Resumo de site por e-mail", "A cada 30 min lê uma página, resume com IA e envia por e-mail (Gmail).", "apps") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "A cada 30 min", config = mapOf("mode" to "interval", "minutes" to "30"))
        val h = WfNode(id = "h$id", type = "http", label = "Ler site", config = mapOf("method" to "GET", "url" to "https://example.com", "timeout" to "30"))
        val a = WfNode(id = "a$id", type = "ai", label = "Resumir", config = mapOf("maxTokens" to "800", "prompt" to "Resuma o conteúdo desta página em até 8 tópicos curtos, em português:\n\n{{${h.id}.text}}"))
        val g = WfNode(id = "g$id", type = "connector", label = "Enviar e-mail", config = mapOf("source" to "hub", "conector" to "gmail", "acao" to "send_email",
            "campos" to "{\n  \"receiver\": [\"seu@email.com\"],\n  \"subject\": \"Resumo do site · {{trigger.time}}\",\n  \"body_text\": \"{{${a.id}}}\"\n}"))
        flow("Resumo de site por e-mail", listOf(t, h, a, g), WfEdge(from = t.id, to = h.id), WfEdge(from = h.id, to = a.id), WfEdge(from = a.id, to = g.id))
    },
    WfTemplate("Monitorar site", "A cada 30 min confere se o site responde; avisa nos dois casos.", "http") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "A cada 30 min", config = mapOf("mode" to "interval", "minutes" to "30"))
        val h = WfNode(id = "h$id", type = "http", label = "Baixar página", config = mapOf("method" to "GET", "url" to "https://example.com", "timeout" to "20", "onError" to "continue"))
        val i = WfNode(id = "i$id", type = "if", label = "Está no ar?", config = mapOf("left" to "{{${h.id}.status}}", "op" to "equals", "right" to "200"))
        val ok = WfNode(id = "o$id", type = "notify", label = "Site no ar", config = mapOf("title" to "✅ Site no ar", "text" to "Status {{${h.id}.status}} em {{trigger.time}}", "open" to "flow"))
        val bad = WfNode(id = "b$id", type = "notify", label = "Site com problema", config = mapOf("title" to "⚠ Site com problema", "text" to "Status {{${h.id}.status}} em {{trigger.time}}",
            "detail" to "Status: {{${h.id}.status}}\nErro: {{${h.id}.error}}\nHora: {{trigger.time}}", "open" to "modal"))
        flow("Monitorar site", listOf(t, h, i, ok, bad), WfEdge(from = t.id, to = h.id), WfEdge(from = h.id, to = i.id),
            WfEdge(from = i.id, to = ok.id, port = "true"), WfEdge(from = i.id, to = bad.id, port = "false"))
    },
    WfTemplate("Cotação do dólar", "Todo dia às 9h avisa a cotação USD → BRL.", "schedule") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "Todo dia 9h", config = mapOf("mode" to "daily", "time" to "09:00"))
        val h = WfNode(id = "h$id", type = "http", label = "Cotação", config = mapOf("method" to "GET", "url" to "https://economia.awesomeapi.com.br/json/last/USD-BRL", "timeout" to "20"))
        val n = WfNode(id = "n$id", type = "notify", label = "Avisar", config = mapOf("title" to "💵 Dólar: R$ {{${h.id}.data.USDBRL.bid}}",
            "text" to "Máx {{${h.id}.data.USDBRL.high}} · mín {{${h.id}.data.USDBRL.low}} · variação {{${h.id}.data.USDBRL.pctChange}}%", "open" to "flow"))
        flow("Cotação do dólar", listOf(t, h, n), WfEdge(from = t.id, to = h.id), WfEdge(from = h.id, to = n.id))
    },
    WfTemplate("Clima da manhã", "Todo dia às 7h: previsão de São Paulo explicada pela IA.", "schedule") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "Todo dia 7h", config = mapOf("mode" to "daily", "time" to "07:00"))
        val h = WfNode(id = "h$id", type = "http", label = "Previsão", config = mapOf("method" to "GET", "timeout" to "20",
            "url" to "https://api.open-meteo.com/v1/forecast?latitude=-23.55&longitude=-46.63&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=America%2FSao_Paulo&forecast_days=1"))
        val a = WfNode(id = "a$id", type = "ai", label = "Explicar", config = mapOf("maxTokens" to "300", "prompt" to
            "Em 2 frases curtas em português, diga como será o tempo hoje (máxima, mínima e chance de chuva) e se vale levar guarda-chuva:\n\n{{${h.id}.data.daily}}"))
        val n = WfNode(id = "n$id", type = "notify", label = "Avisar", config = mapOf("title" to "🌤 Clima de hoje", "text" to "{{${a.id}}}", "open" to "modal"))
        flow("Clima da manhã", listOf(t, h, a, n), WfEdge(from = t.id, to = h.id), WfEdge(from = h.id, to = a.id), WfEdge(from = a.id, to = n.id))
    },
    WfTemplate("Notícias do dia com IA", "Todo dia às 8h um agente pesquisa as principais notícias; o toque abre a lista completa.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "Todo dia 8h", config = mapOf("mode" to "daily", "time" to "08:00"))
        val a = WfNode(id = "a$id", type = "agent", label = "Pesquisar", config = mapOf("timeout" to "10", "prompt" to
            "Use web_search (sem abrir o navegador) para achar as 5 notícias mais importantes de hoje sobre tecnologia. Responda com uma lista numerada: título, uma linha de resumo e o link direto da fonte."))
        val n = WfNode(id = "n$id", type = "notify", label = "Avisar", config = mapOf("title" to "📰 Notícias do dia", "text" to "As 5 principais de hoje — toque para ler", "detail" to "{{${a.id}}}", "open" to "modal"))
        flow("Notícias do dia", listOf(t, a, n), WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = n.id))
    },
    WfTemplate("E-mail com aprovação", "A IA escreve um e-mail, você aprova e só então ele é enviado pelo Gmail.", "approval") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_manual", label = "Início")
        val a = WfNode(id = "a$id", type = "ai", label = "Escrever e-mail", config = mapOf("maxTokens" to "600", "prompt" to "Escreva um e-mail curto e cordial de follow-up para um cliente que pediu orçamento ontem. Responda só com o corpo do e-mail."))
        val ap = WfNode(id = "p$id", type = "approval", label = "Aprovar envio", config = mapOf("question" to "Enviar este e-mail?\n\n{{${a.id}}}"))
        val g = WfNode(id = "g$id", type = "connector", label = "Enviar (Gmail)", config = mapOf("source" to "hub", "conector" to "gmail", "acao" to "send_email",
            "campos" to "{\n  \"receiver\": [\"cliente@email.com\"],\n  \"subject\": \"Seu orçamento\",\n  \"body_text\": \"{{${a.id}}}\"\n}"))
        val n = WfNode(id = "n$id", type = "notify", label = "Enviado", config = mapOf("title" to "✉ E-mail enviado", "text" to "O follow-up foi enviado.", "open" to "flow"))
        flow("E-mail com aprovação", listOf(t, a, ap, g, n), WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = ap.id), WfEdge(from = ap.id, to = g.id, port = "true"), WfEdge(from = g.id, to = n.id))
    },
    // ─── WhatsApp (API Hub + webhook em tempo real) — inspirados nos modelos mais usados do n8n, com o poder do app ───
    WfTemplate("WhatsApp · Atendente de vendas com IA", "Cada mensagem privada recebida é respondida na hora por uma IA com a SUA base de conhecimento (produtos, preços, regras), focada em converter. Pede humano quando o cliente quiser.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Mensagem recebida", config = mapOf("scope" to "private", "from_me" to "false"))
        val a = WfNode(id = "a$id", type = "atendente", label = "Atendente de vendas", config = mapOf(
            "persona" to "Você é a Ana, consultora da empresa. Tom cordial, direto e humano; chama o cliente pelo nome quando souber.",
            "knowledge" to "PRODUTOS E PREÇOS:\n- (descreva aqui seus produtos/planos, preços e condições)\n\nREGRAS:\n- Horário de atendimento: seg a sex, 9h às 18h\n- Formas de pagamento: Pix, cartão em até 12x\n\nPERGUNTAS FREQUENTES:\n- (pergunta) → (resposta)",
            "objective" to "vendas", "history" to "10", "reply" to "auto", "handoff" to "falar com humano|atendente humano|reclamação"))
        flow("WhatsApp · Atendente de vendas", listOf(t, a), WfEdge(from = t.id, to = a.id))
    },
    WfTemplate("WhatsApp · Suporte com base de conhecimento", "Responde dúvidas de clientes (privado) usando só o seu manual/FAQ; o que não estiver na base ele diz que vai confirmar e te avisa.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Mensagem recebida", config = mapOf("scope" to "private"))
        val a = WfNode(id = "a$id", type = "atendente", label = "Suporte", config = mapOf("persona" to "Você é o suporte da empresa: paciente, claro, passo a passo.",
            "knowledge" to "(cole aqui o manual, FAQ, políticas de troca, prazos e contatos)", "objective" to "suporte", "history" to "12", "reply" to "auto", "handoff" to "falar com humano|atendente|reclamação|cancelar"))
        flow("WhatsApp · Suporte", listOf(t, a), WfEdge(from = t.id, to = a.id))
    },
    WfTemplate("WhatsApp · Recuperar pagamento", "Clientes que respondem sobre cobrança/boleto/Pix recebem atendimento empático com as condições de negociação que você definir.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Mensagem sobre pagamento", config = mapOf("scope" to "private", "keyword" to "boleto|pix|pagamento|pagar|fatura|parcela|atraso"))
        val a = WfNode(id = "a$id", type = "atendente", label = "Negociação", config = mapOf("persona" to "Você é do financeiro: empático, sem julgamento, objetivo.",
            "knowledge" to "CONDIÇÕES:\n- Pix com 5% de desconto à vista\n- Parcelamento em até 3x sem juros para atrasos até 30 dias\n- Chave Pix: (sua chave)\n- 2ª via: (link)",
            "objective" to "recuperar_pagamento", "history" to "10", "reply" to "auto", "handoff" to "falar com humano|contestar|não reconheço"))
        flow("WhatsApp · Recuperar pagamento", listOf(t, a), WfEdge(from = t.id, to = a.id))
    },
    WfTemplate("WhatsApp · Conversar com uma sessão do app", "Mande mensagem para você mesmo no WhatsApp: ela acorda uma conversa do app (com agentes, arquivos e ferramentas) e a resposta volta no próprio WhatsApp.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Mensagem para mim mesmo", config = mapOf("chat" to "", "from_me" to "true", "scope" to "private"))
        val a = WfNode(id = "a$id", type = "atendente", label = "Repassar à sessão", config = mapOf("session" to "", "reply" to "auto", "history" to "0", "handoff" to ""))
        flow("WhatsApp · Minha sessão no WhatsApp", listOf(t, a), WfEdge(from = t.id, to = a.id))
    },
    WfTemplate("WhatsApp · /imagem gera e envia a imagem", "Escreva \"/imagem um gato astronauta\" em uma conversa: um agente gera a imagem (API Hub) e envia a foto de volta no mesmo chat.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Comando /imagem", config = mapOf("keyword" to "/imagem", "from_me" to "true"))
        val a = WfNode(id = "a$id", type = "agent", label = "Gerar e enviar imagem", config = mapOf("maxSteps" to "12", "prompt" to
            "Pedido recebido no WhatsApp: {{${t.id}.payload.text}}\n1) Tire o comando /imagem e use o resto como descrição da imagem.\n2) Gere a imagem com uma Web API do hub (webapi_list; ex.: geração de imagem). O arquivo volta em webapi_files/.\n" +
                "3) Envie a imagem com webapi_call api=whatsapp_send_file args={\"to\":\"{{${t.id}.payload.chat}}\",\"file_path\":\"<caminho do arquivo>\",\"caption\":\"<título curto>\",\"confirmed\":true} — o usuário autorizou este envio ao criar o fluxo.\n4) Termine com 1 frase dizendo o que enviou."))
        flow("WhatsApp · /imagem", listOf(t, a), WfEdge(from = t.id, to = a.id))
    },
    WfTemplate("WhatsApp · /pesquisa responde com a internet", "Escreva \"/pesquisa preço do dólar hoje\": um agente pesquisa na web e a resposta (com fontes) volta no mesmo chat.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Comando /pesquisa", config = mapOf("keyword" to "/pesquisa", "from_me" to "true"))
        val a = WfNode(id = "a$id", type = "agent", label = "Pesquisar", config = mapOf("maxSteps" to "10", "prompt" to
            "Pergunta recebida no WhatsApp: {{${t.id}.payload.text}} (ignore o comando /pesquisa). Pesquise com web_search/web_fetch e responda em texto de WhatsApp: no máximo 6 linhas, sem markdown, com 1–2 links das fontes no fim."))
        val s = WfNode(id = "s$id", type = "webapi", label = "Responder no WhatsApp", config = mapOf("api" to "whatsapp_send_text",
            "args" to "{\"to\": \"{{${t.id}.payload.chat}}\", \"text\": \"{{${a.id}}}\", \"reply_to\": \"{{${t.id}.payload.id}}\"}"))
        flow("WhatsApp · /pesquisa", listOf(t, a, s), WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = s.id))
    },
    WfTemplate("WhatsApp · /arquivo envia um arquivo do app", "Escreva \"/arquivo relatório de vendas\": um agente acha (ou cria) o arquivo na área de trabalho e envia como documento no chat.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Comando /arquivo", config = mapOf("keyword" to "/arquivo", "from_me" to "true"))
        val a = WfNode(id = "a$id", type = "agent", label = "Achar e enviar arquivo", config = mapOf("maxSteps" to "12", "prompt" to
            "Pedido no WhatsApp: {{${t.id}.payload.text}} (ignore /arquivo). Ache o arquivo pedido na área de trabalho (file_list/file_search); se não existir e for possível, crie-o (ex.: um .md ou .csv com o conteúdo pedido). " +
                "Envie com webapi_call api=whatsapp_send_file args={\"to\":\"{{${t.id}.payload.chat}}\",\"file_path\":\"<caminho>\",\"type\":\"document\",\"confirmed\":true} (o usuário autorizou ao criar o fluxo). Termine com 1 frase."))
        flow("WhatsApp · /arquivo", listOf(t, a), WfEdge(from = t.id, to = a.id))
    },
    WfTemplate("WhatsApp · Alerta de palavra-chave em grupos", "Quando alguém num grupo escrever uma palavra importante (ex.: urgente, pagamento, seu nome), você recebe um aviso na hora com a mensagem.", "notify") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Palavra-chave em grupo", config = mapOf("scope" to "groups", "keyword" to "urgente|pagamento|reunião"))
        val n = WfNode(id = "n$id", type = "notify", label = "Avisar", config = mapOf("title" to "💬 {{${t.id}.payload.chat_name}}", "text" to "{{${t.id}.payload.sender_name}}: {{${t.id}.payload.text}}", "open" to "modal"))
        flow("WhatsApp · Alerta em grupos", listOf(t, n), WfEdge(from = t.id, to = n.id))
    },
    WfTemplate("WhatsApp · Salvar mídias recebidas", "Fotos, áudios, vídeos e documentos que chegarem (privado) são baixados para a área de trabalho do Hub (webapi_files/).", "http") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_whatsapp", label = "Mensagem recebida", config = mapOf("scope" to "private"))
        val c = WfNode(id = "c$id", type = "if", label = "Tem mídia?", config = mapOf("left" to "{{${t.id}.payload.has_media}}", "op" to "equals", "right" to "true"))
        val d = WfNode(id = "d$id", type = "webapi", label = "Baixar mídia", config = mapOf("api" to "whatsapp_download_media", "args" to "{\"message_id\": \"{{${t.id}.payload.id}}\"}"))
        flow("WhatsApp · Salvar mídias", listOf(t, c, d), WfEdge(from = t.id, to = c.id), WfEdge(from = c.id, to = d.id, port = "true"))
    },
    WfTemplate("WhatsApp · Resumo diário de um grupo", "Todo dia às 18h lê as últimas mensagens de um grupo, a IA resume decisões, pendências e quem pediu o quê, e manda num quadro para você.", "notify") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "Todo dia às 18h", config = mapOf("mode" to "daily", "time" to "18:00"))
        val m = WfNode(id = "m$id", type = "webapi", label = "Ler o grupo", config = mapOf("api" to "whatsapp_messages", "args" to "{\"to\": \"Nome do grupo\", \"count\": 80}"))
        val a = WfNode(id = "a$id", type = "ai", label = "Resumir", config = mapOf("maxTokens" to "900", "prompt" to
            "Resuma estas mensagens de um grupo de WhatsApp em português, para ler no celular: 1) decisões, 2) pendências (quem / o quê / prazo), 3) assuntos principais. Seja curto.\n\n{{${m.id}.results}}"))
        val n = WfNode(id = "n$id", type = "notify", label = "Avisar", config = mapOf("title" to "🗂 Resumo do grupo", "text" to "O que rolou hoje no grupo", "detail" to "{{${a.id}}}", "open" to "page", "layout" to "markdown"))
        flow("WhatsApp · Resumo diário do grupo", listOf(t, m, a, n), WfEdge(from = t.id, to = m.id), WfEdge(from = m.id, to = a.id), WfEdge(from = a.id, to = n.id))
    },
    WfTemplate("Webhook → aviso com os dados", "Um sistema externo chama o webhook (comentário numa issue do GitHub com o segredo); a IA resume o payload e o toque abre uma página formatada.", "notify") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_webhook", label = "Webhook", config = mapOf("repo" to "", "issue" to "", "secret" to "", "sample" to "{\"pedido\": 1042, \"cliente\": \"Ana\", \"total\": 189.9, \"status\": \"pago\"}"))
        val a = WfNode(id = "a$id", type = "ai", label = "Resumir chamada", config = mapOf("maxTokens" to "500", "prompt" to
            "Transforme os dados abaixo num resumo em Markdown para o celular: uma linha de título em negrito e uma tabela | Campo | Valor | com os campos importantes. Sem JSON.\n\n{{${t.id}.payload}}"))
        val n = WfNode(id = "n$id", type = "notify", label = "Avisar", config = mapOf("title" to "🔔 Chamada recebida", "text" to "Toque para ver os detalhes", "detail" to "{{${a.id}}}", "open" to "page", "layout" to "markdown"))
        flow("Webhook → aviso", listOf(t, a, n), WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = n.id))
    },
    WfTemplate("Resumo do dia no chat do fluxo", "Todo dia às 18h a IA pesquisa as notícias do seu tema e manda num chat do fluxo (tabela + links), onde você pode continuar perguntando.", "agent") {
        val id = WfIds.new().take(4)
        val t = WfNode(id = "t$id", type = "trigger_schedule", label = "Todo dia às 18h", config = mapOf("mode" to "daily", "time" to "18:00"))
        val a = WfNode(id = "a$id", type = "agent", label = "Pesquisar", config = mapOf("maxSteps" to "10", "prompt" to
            "Pesquise com web_search as 5 notícias mais importantes de hoje sobre tecnologia no Brasil. Responda em Markdown: uma tabela | # | Manchete | Fonte | com o link direto de cada fonte e, abaixo, 1 frase de contexto por notícia."))
        val n = WfNode(id = "n$id", type = "notify", label = "Enviar ao chat", config = mapOf("title" to "🗞 Resumo do dia", "text" to "As 5 notícias de hoje", "detail" to "{{${a.id}}}", "open" to "chat"))
        flow("Resumo do dia no chat", listOf(t, a, n), WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = n.id))
    },
)

// ───────────────────────────── Lista ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(nav: NavController) {
    val c = Cue.c
    val flows by WorkflowStore.flows.collectAsStateWithLifecycle()
    val runs by WorkflowStore.runs.collectAsStateWithLifecycle()
    val ctx = nav.context
    var newSheet by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Fluxos", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSheet = true }, containerColor = c.accent, contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Novo fluxo") })
        }) { pad ->
        // Relógio da lista: atualiza "última/próxima execução" a cada 15 s.
        var tick by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) } }
        if (flows.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.AccountTree, "Nenhum fluxo ainda",
                "Monte automações ligando nós no canvas: gatilho por horário, agentes de IA, apps conectados, HTTP, condições e notificações.")
        } else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(flows.sortedByDescending { it.updatedAt }, key = { it.id }) { w ->
                val mine = runs.filter { it.workflowId == w.id }
                val last = mine.maxByOrNull { it.startedAt }
                val active = mine.filter { WorkflowEngine.isActive(it.status) }.maxByOrNull { it.startedAt }
                val sched = w.nodes.firstOrNull { it.type == "trigger_schedule" }
                // Gatilho por evento: escuta em tempo real (sem horário) — fica ativo/desligado, nunca "manual".
                val event = w.nodes.firstOrNull { it.type == "trigger_whatsapp" || it.type == "trigger_webhook" }
                val eventLabel = if (event?.type == "trigger_whatsapp") "Mensagem no WhatsApp" else "Webhook"
                val accent = statusColor(active?.status, c) ?: c.accent
                CueCard(onClick = { nav.navigate("workflow/${w.id}") }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                if (active?.status == "running") CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.5.dp)
                                else Icon(Icons.Rounded.AccountTree, null, tint = accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (w.enabled && (event != null || sched != null)) { LiveDot(Color(0xFF2EC27E)); Spacer(Modifier.width(6.dp)) }
                                    Text(w.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${w.nodes.size} nós · " + (event?.let { eventLabel + if (w.enabled) " · ativo, escutando" else " · desligado" }
                                    ?: sched?.let { WfLogic.scheduleLabel(it.config) + if (w.enabled) " · ativo" else " · desligado" } ?: "manual"),
                                    fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (active == null && last != null) Text(runLabel(last), fontSize = 12.sp, color = statusColor(last.status, c) ?: c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // Start / pausa / continuar / parar direto da lista.
                            when {
                                // Evento: o botão liga/desliga a escuta (▶ ativa · ⏸ pausa), em vez de executar uma vez.
                                active == null && event != null -> if (w.enabled) FilledTonalIconButton({
                                    val v = WorkflowStore.upsert(w.copy(enabled = false)); WorkflowScheduler.sync(ctx, v)
                                }) { Icon(Icons.Rounded.Pause, "Pausar escuta") } else FilledIconButton({
                                    if (WfLogic.problems(w).any { !it.contains("não está ligado") }) nav.navigate("workflow/${w.id}")
                                    else { val v = WorkflowStore.upsert(w.copy(enabled = true)); WorkflowScheduler.sync(ctx, v) }
                                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent)) { Icon(Icons.Rounded.PlayArrow, "Ativar escuta", tint = Color.White) }
                                active == null -> FilledIconButton({
                                    WorkflowEngine.start(w.id).onFailure { nav.navigate("workflow/${w.id}") }
                                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent)) { Icon(Icons.Rounded.PlayArrow, "Executar", tint = Color.White) }
                                active.status == "waiting" -> FilledTonalIconButton({
                                    val rv = WorkflowEngine.pendingReviewOf(w.id)
                                    nav.navigate(if (rv != null) "review/${rv.runId}/${rv.nodeId}" else "workflow/${w.id}")
                                }) { Icon(Icons.Rounded.HourglassTop, "Responder", tint = c.warn) }
                                else -> Row {
                                    FilledTonalIconButton({ if (active.status == "paused") WorkflowEngine.resume(active.id) else WorkflowEngine.pause(active.id) }) {
                                        Icon(if (active.status == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (active.status == "paused") "Continuar" else "Pausar")
                                    }
                                    IconButton({ WorkflowEngine.cancel(active.id) }) { Icon(Icons.Rounded.Stop, "Parar", tint = c.err) }
                                }
                            }
                        }
                        // Execução ao vivo: nó atual e progresso (o editor abre herdando este estado).
                        if (active != null) {
                            val done = active.steps.count { it.status == "ok" }
                            val cur = active.steps.lastOrNull()
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { (done.toFloat() / w.nodes.size.coerceAtLeast(1)).coerceIn(0.03f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = accent, trackColor = c.line)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                cur?.let { Icon(nodeIcon(it.type), null, Modifier.size(14.dp), tint = accent); Spacer(Modifier.width(6.dp)) }
                                Text(when (active.status) {
                                    "paused" -> "Pausado"
    "queued" -> "Na fila (outros fluxos rodando)"
                                    "waiting" -> "Aguardando aprovação"
                                    else -> "Executando"
                                } + (cur?.let { " · ${it.label}" } ?: "") + " · $done de ${w.nodes.size}", fontSize = 12.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text("Ver ao vivo ›", fontSize = 12.sp, color = c.ink3)
                            }
                        }
                                // Sempre: última execução e próxima (qualquer intervalo — 2 min, 1 h, 1 dia…).
                        val lastAt = listOfNotNull(last?.startedAt, if (sched != null) WorkflowScheduler.lastFire(w, ctx) else null).maxOrNull()
                        val nextAt = if (sched != null && w.enabled) WorkflowScheduler.nextRuns(w, ctx).firstOrNull { it > tick - 120_000 } else null
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, Modifier.size(14.dp), tint = c.ink3); Spacer(Modifier.width(4.dp))
                            Text("Última: " + (lastAt?.let { WfLogic.whenLabel(it, tick) } ?: "nunca"), fontSize = 12.sp, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = if (nextAt != null) c.accent else c.ink3); Spacer(Modifier.width(4.dp))
                            Text("Próxima: " + when { event != null -> if (w.enabled) "a cada evento" else "desligado"; nextAt != null -> WfLogic.whenLabel(nextAt, tick); sched == null -> "manual"; else -> "desligado" },
                                fontSize = 12.sp, color = if (nextAt != null || (event != null && w.enabled)) c.accent else c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (sched != null) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Agendamento", fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f))
                                Switch(checked = w.enabled, onCheckedChange = { on ->
                                    if (on && WfLogic.problems(w).any { !it.contains("não está ligado") }) nav.navigate("workflow/${w.id}")
                                    else if (on) WorkflowEngine.publish(ctx, w.id) { ok, msg -> if (!ok) android.widget.Toast.makeText(ctx, msg.take(300), android.widget.Toast.LENGTH_LONG).show() }
                                    else { val v = WorkflowStore.upsert(w.copy(enabled = false)); WorkflowScheduler.sync(ctx, v) }
                                }, modifier = Modifier.scale(0.85f))
                            }
                        }
                    }
                }
            }
        }
    }
    if (newSheet) ModalBottomSheet(onDismissRequest = { newSheet = false }, containerColor = c.surface) {
        Column(Modifier.padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Começar de", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Modelos prontos, já ligados e validados — ajuste os campos e toque em Executar.", fontSize = 12.sp, color = c.ink3)
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                templates().forEach { tp ->
                    CueCard(onClick = {
                        val w = WorkflowStore.upsert(tp.make())
                        newSheet = false
                        nav.navigate("workflow/${w.id}")
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(c.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                Icon(when (tp.icon) { "notify" -> Icons.Rounded.Notifications; "apps" -> Icons.Rounded.Apps; "http" -> Icons.Rounded.Http
                                    "schedule" -> Icons.Rounded.Schedule; "agent" -> Icons.Rounded.SmartToy; "approval" -> Icons.Rounded.ThumbUp; "review" -> Icons.Rounded.RateReview; else -> Icons.Rounded.PlayArrow }, null, tint = c.accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tp.title, fontWeight = FontWeight.Medium)
                                Text(tp.description, fontSize = 12.sp, color = c.ink3)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun runLabel(r: WfRun): String = when (r.status) {
    "running" -> "Executando agora…"
    "paused" -> "Pausado"
    "waiting" -> "Aguardando sua aprovação"
    "ok" -> "Última execução ok · ${timeFmt.format(Date(r.startedAt))}"
    "error" -> "Falhou · ${timeFmt.format(Date(r.startedAt))} · ${r.error.take(60)}"
    else -> "Parada · ${timeFmt.format(Date(r.startedAt))}"
}

// ───────────────────────────── Editor (canvas) ─────────────────────────────

private const val GRID = 20f

private fun snap(v: Float) = (Math.round(v / GRID) * GRID).toFloat()

/** Lugar livre perto de (x, y): desce até não encostar em nenhum nó. */
private fun freeSpot(w: Workflow, x: Float, y: Float, except: String? = null): Pair<Float, Float> {
    var ny = snap(y); val nx = snap(x).coerceIn(0f, WORLD - NODE_W)
    fun hit(yy: Float) = w.nodes.any { it.id != except && kotlin.math.abs(it.x - nx) < NODE_W + 20f && kotlin.math.abs(it.y - yy) < NODE_H + 20f }
    var guard = 0
    while (hit(ny) && guard++ < 40) ny += NODE_H + 30f
    return nx to ny.coerceIn(0f, WORLD - NODE_H)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(nav: NavController, id: String) {
    val c = Cue.c
    val ctx = nav.context
    val flows by WorkflowStore.flows.collectAsStateWithLifecycle()
    val runs by WorkflowStore.runs.collectAsStateWithLifecycle()
    val w = flows.firstOrNull { it.id == id }
    if (w == null) {
        Scaffold(containerColor = c.bg, topBar = { CueTopBar("Fluxo", { nav.popBackStack() }) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Fluxo não encontrado.", color = c.ink3) }
        }
        return
    }
    val density = LocalDensity.current
    val dens = density.density
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset(0f, 0f)) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var connectFrom by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Arrasto e posição recém-gravada ficam na memória até o fluxo salvo refletir (o nó não "pula" ao soltar).
    val dragOff = remember { mutableStateMapOf<String, Offset>() }
    val pending = remember { mutableStateMapOf<String, Offset>() }
    var palette by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var problemsOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAuto by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var watchRun by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<WfRun?>(null) }
    val problems = remember(w) { WfLogic.problems(w) }
    val myRuns = runs.filter { it.workflowId == w.id }.sortedByDescending { it.startedAt }
    val active = myRuns.firstOrNull { WorkflowEngine.isActive(it.status) }
    val shown = active ?: myRuns.firstOrNull()
    val nodeStatus = shown?.steps?.associate { it.nodeId to it.status }.orEmpty()

    LaunchedEffect(w.nodes) { pending.keys.toList().forEach { k -> val n = w.nodes.firstOrNull { it.id == k }; if (n == null || (n.x == pending[k]?.x && n.y == pending[k]?.y)) pending.remove(k) } }
    // Execução disparada daqui terminou: mostra o resultado na tela.
    LaunchedEffect(myRuns.firstOrNull { it.id == watchRun }?.status) {
        val r = myRuns.firstOrNull { it.id == watchRun } ?: return@LaunchedEffect
        if (!WorkflowEngine.isActive(r.status)) { result = r; watchRun = null }
    }

    fun pos(n: WfNode): Offset { val p = pending[n.id] ?: Offset(n.x, n.y); val d = dragOff[n.id] ?: Offset.Zero; return Offset(p.x + d.x, p.y + d.y) }
    fun fit() {
        if (viewport.width == 0 || w.nodes.isEmpty()) { scale = 1f; offset = Offset(40f, 40f); return }
        val d = dens
        val minX = w.nodes.minOf { it.x }; val minY = w.nodes.minOf { it.y }
        val maxX = w.nodes.maxOf { it.x + NODE_W + 40f }; val maxY = w.nodes.maxOf { it.y + NODE_H }
        val pad = 48f
        val s = minOf(viewport.width / ((maxX - minX + pad * 2) * d), (viewport.height - 160 * d) / ((maxY - minY + pad * 2) * d), 1.15f).coerceIn(0.35f, 1.15f)
        scale = s
        val contentW = (maxX - minX) * d * s; val contentH = (maxY - minY) * d * s
        offset = Offset((viewport.width - contentW) / 2 - minX * d * s, (viewport.height - 120 * d - contentH) / 2 - minY * d * s)
    }
    LaunchedEffect(viewport, w.id) { if (!fitted && viewport.width > 0) { fit(); fitted = true } }

    fun save(v: Workflow) { val s = WorkflowStore.upsert(v); if (s.enabled) WorkflowScheduler.sync(ctx, s) }
    fun connect(to: String) {
        val (from, port) = connectFrom ?: return
        connectFrom = null
        val target = w.nodes.firstOrNull { it.id == to } ?: return
        when {
            from == to -> toast = "Um nó não pode ligar nele mesmo."
            WfCatalog.of(target.type).trigger -> toast = "Gatilhos não recebem ligação de entrada."
            w.edges.any { it.from == from && it.to == to && it.port == port } -> toast = "Essa ligação já existe."
            else -> { save(w.copy(edges = w.edges + WfEdge(from = from, to = to, port = port))); toast = "Ligado" }
        }
    }
    fun addNode(type: String, preset: WfPreset? = null) {
        val t = WfCatalog.of(type)
        val anchor = w.nodes.firstOrNull { it.id == selected } ?: w.nodes.maxByOrNull { it.x }
        val d = dens
        val center = Offset((viewport.width / 2f - offset.x) / (d * scale) - NODE_W / 2, (viewport.height / 2.5f - offset.y) / (d * scale))
        val (x, y) = if (anchor != null && !t.trigger) freeSpot(w, anchor.x + NODE_W + 60f, anchor.y) else freeSpot(w, center.x, center.y)
        val cfg = t.fields.filter { it.default.isNotEmpty() }.associate { it.key to it.default } + preset?.config.orEmpty()
        val n = WfNode(type = type, label = preset?.title.orEmpty(), x = x, y = y, config = cfg)
        val edge = anchor?.takeIf { !t.trigger }?.let { a -> WfEdge(from = a.id, to = n.id, port = WfCatalog.of(a.type).ports.first()) }
        save(w.copy(nodes = w.nodes + n, edges = w.edges + listOfNotNull(edge)))
        selected = n.id
    }
    fun runNow() {
        result = null
        WorkflowEngine.start(w.id).onFailure { toast = it.message?.lineSequence()?.firstOrNull(); problemsOpen = problems.isNotEmpty() }
            .onSuccess { watchRun = it; toast = "Executando…" }
    }

    Scaffold(containerColor = c.bg, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg),
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Voltar") } },
            title = {
                Column(Modifier.clickable { rename = true }) {
                    Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val next = WorkflowScheduler.nextRuns(w, ctx).minOrNull()
                    val done = active?.steps?.count { it.status == "ok" } ?: 0
                    Text(when {
                        active?.status == "waiting" -> "Aguardando sua aprovação"
                        active?.status == "paused" -> "Pausado · $done de ${w.nodes.size} nós"
                        active != null -> "Executando · $done de ${w.nodes.size} nós"
                        next != null -> "Ativo · próxima ${timeFmt.format(Date(next))}"
                        w.nodes.any { it.type == "trigger_schedule" } -> "Agendamento desligado"
                        else -> "Manual · ${w.nodes.size} nós"
                    }, fontSize = 12.sp, color = if (active != null) c.accent else c.ink3)
                }
            },
            actions = {
                if (w.nodes.any { it.type == "trigger_schedule" || it.type == "trigger_webhook" || it.type == "trigger_whatsapp" }) Switch(checked = w.enabled, onCheckedChange = { on ->
                    if (on && problems.any { !it.contains("não está ligado") }) { problemsOpen = true }
                    else if (on) { toast = "Testando de ponta a ponta antes de publicar…"; WorkflowEngine.publish(ctx, w.id) { _, msg -> toast = msg.take(300) } }
                    else { val v = WorkflowStore.upsert(w.copy(enabled = false)); WorkflowScheduler.sync(ctx, v); toast = "Desligado" }
                }, modifier = Modifier.padding(end = 4.dp))
                IconButton({ history = true }) {
                    BadgedBox(badge = { if (active?.status == "waiting") Badge(containerColor = c.warn) }) { Icon(Icons.Rounded.History, "Execuções") }
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        if (w.nodes.any { it.type == "review" }) DropdownMenuItem({ Text(if (w.autonomous) "Desligar autonomia total" else "Autonomia total…") }, {
                            menu = false
                            if (w.autonomous) { save(w.copy(autonomous = false)); toast = "Revisões voltam a pedir sua aprovação" } else confirmAuto = true
                        }, leadingIcon = { Icon(Icons.Rounded.Bolt, null) })
                        DropdownMenuItem({ Text("Organizar nós") }, {
                            menu = false
                            save(WfLogic.layout(w, NODE_W, NODE_H)); fitted = false
                        }, leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, null) })
                        DropdownMenuItem({ Text("Duplicar fluxo") }, {
                            menu = false
                            val idMap = w.nodes.associate { it.id to WfIds.new() }
                            val copy = WorkflowStore.upsert(Workflow(name = w.name + " (cópia)", nodes = w.nodes.map { it.copy(id = idMap.getValue(it.id)) },
                                edges = w.edges.mapNotNull { e -> val f = idMap[e.from]; val t = idMap[e.to]; if (f != null && t != null) e.copy(id = WfIds.new(), from = f, to = t) else null }))
                            nav.navigate("workflow/${copy.id}")
                        }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        DropdownMenuItem({ Text("Ver ajustes pendentes") }, { menu = false; problemsOpen = true }, leadingIcon = { Icon(Icons.Rounded.Rule, null) })
                        DropdownMenuItem({ Text("Excluir fluxo", color = c.err) }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.err) })
                    }
                }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val minor = c.line.copy(alpha = .35f); val major = c.line.copy(alpha = .75f)
            Box(Modifier.fillMaxSize().background(c.surface2)
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.35f, 2.2f)
                        offset = (offset - centroid) * (ns / scale) + centroid + pan
                        scale = ns
                    }
                }
                .pointerInput(Unit) { detectTapGestures { connectFrom = null; selected = null } }) {
                // Canvas quadriculado (linhas finas a cada 20 e marcadas a cada 100), acompanhando pan e zoom.
                Canvas(Modifier.fillMaxSize()) {
                    val step = GRID * dens * scale
                    if (step >= 6f) {
                        var i = kotlin.math.floor(-offset.x / step).toInt()
                        var x = offset.x + i * step
                        while (x < size.width) { drawLine(if (i % 5 == 0) major else minor, Offset(x, 0f), Offset(x, size.height), if (i % 5 == 0) 1.2f else 0.8f); x += step; i++ }
                        var j = kotlin.math.floor(-offset.y / step).toInt()
                        var y = offset.y + j * step
                        while (y < size.height) { drawLine(if (j % 5 == 0) major else minor, Offset(0f, y), Offset(size.width, y), if (j % 5 == 0) 1.2f else 0.8f); y += step; j++ }
                    }
                }
                // Ligações: curvas suaves; o caminho que a execução percorreu fica sólido e, enquanto roda, anima.
                val flow = rememberInfiniteTransition(label = "wfflow")
                val phase by flow.animateFloat(0f, 36f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
                val edgeColor = c.ink3
                val okColor = c.ok; val errColor = c.err; val accent = c.accent
                Canvas(Modifier.fillMaxSize()) {
                    val k = dens * scale
                    fun scr(xDp: Float, yDp: Float) = Offset(offset.x + xDp * k, offset.y + yDp * k)
                    w.edges.forEach { e ->
                        val a = w.nodes.firstOrNull { it.id == e.from } ?: return@forEach
                        val b = w.nodes.firstOrNull { it.id == e.to } ?: return@forEach
                        val pa = pos(a); val pb = pos(b)
                        val ports = WfCatalog.of(a.type).ports
                        val py = if (ports.size > 1) (if (e.port == "false") NODE_H * 0.70f else NODE_H * 0.32f) else NODE_H / 2
                        val s0 = scr(pa.x + NODE_W, pa.y + py)
                        val t0 = scr(pb.x, pb.y + NODE_H / 2)
                        val dx = (kotlin.math.abs(t0.x - s0.x) / 2).coerceAtLeast(50f * k)
                        val path = Path().apply { moveTo(s0.x, s0.y); cubicTo(s0.x + dx, s0.y, t0.x - dx, t0.y, t0.x, t0.y) }
                        val base = when (e.port) { "true" -> okColor; "false" -> errColor; else -> edgeColor }
                        val ran = nodeStatus[e.from] == "ok" && nodeStatus.containsKey(e.to)
                        val live = ran && nodeStatus[e.to] == "running"
                        drawPath(path, (if (live) accent else base).copy(alpha = if (ran) 1f else .55f), style = Stroke(width = (if (ran) 2.6f else 1.8f) * k,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            pathEffect = if (live) PathEffect.dashPathEffect(floatArrayOf(12f * k, 6f * k), -phase * k) else if (ran) null else PathEffect.dashPathEffect(floatArrayOf(7f * k, 6f * k))))
                        // Seta de chegada.
                        val ah = 6f * k
                        drawPath(Path().apply { moveTo(t0.x, t0.y); lineTo(t0.x - ah * 1.4f, t0.y - ah); lineTo(t0.x - ah * 1.4f, t0.y + ah); close() }, base.copy(alpha = if (ran) 1f else .7f))
                    }
                }
                w.nodes.forEach { n ->
                    val p = pos(n)
                    Box(Modifier.offset { IntOffset((offset.x + p.x * dens * scale).roundToInt(), (offset.y + p.y * dens * scale).roundToInt()) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
                        NodeCard(n, nodeStatus[n.id], selected == n.id, connectFrom != null && connectFrom?.first != n.id, connectFrom?.first == n.id,
                            onTap = { if (connectFrom != null) connect(n.id) else selected = n.id },
                            onDrag = { dx, dy ->
                                val base = pending[n.id] ?: Offset(n.x, n.y)
                                val d = dragOff[n.id] ?: Offset.Zero
                                val nx = (base.x + d.x + dx / dens).coerceIn(0f, WORLD - NODE_W) - base.x
                                val ny = (base.y + d.y + dy / dens).coerceIn(0f, WORLD - NODE_H) - base.y
                                dragOff[n.id] = Offset(nx, ny)
                            },
                            onDragEnd = {
                                val d = dragOff.remove(n.id) ?: return@NodeCard
                                val base = pending[n.id] ?: Offset(n.x, n.y)
                                // Encaixa na grade e evita ficar em cima de outro nó.
                                val (fx, fy) = freeSpot(w, base.x + d.x, base.y + d.y, except = n.id)
                                pending[n.id] = Offset(fx, fy)
                                save(w.copy(nodes = w.nodes.map { if (it.id == n.id) it.copy(x = fx, y = fy) else it }))
                            },
                            onPort = { port -> connectFrom = if (connectFrom == n.id to port) null else n.id to port })
                    }
                }
                if (w.nodes.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.AccountTree, null, Modifier.size(40.dp), tint = c.ink3)
                    Spacer(Modifier.height(8.dp))
                    Text("Comece adicionando um gatilho", color = c.ink2, fontWeight = FontWeight.Medium)
                    TextButton({ palette = true }) { Text("Adicionar nó") }
                }
            }
            // Avisos.
            Column(Modifier.align(Alignment.TopCenter).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connectFrom != null) Banner("Toque no nó de destino · toque no fundo para cancelar", c.accent) { connectFrom = null }
                else if (active?.status == "waiting") Banner("Aguardando sua aprovação — toque para responder", c.warn) {
                    val rv = WorkflowEngine.pendingReviewOf(w.id)
                    if (rv != null) nav.navigate("review/${rv.runId}/${rv.nodeId}") else history = true
                }
                else if (w.autonomous) Banner("Autonomia total: revisões seguem sozinhas", c.accent) { menu = true }
                else if (problems.isNotEmpty()) Banner("${problems.size} ajuste(s) pendente(s)", c.warn) { problemsOpen = true }
            }
            // Resultado da execução disparada aqui.
            result?.let { r ->
                val col = statusColor(r.status, c) ?: c.ink3
                Row(Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 84.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, col.copy(alpha = .6f), RoundedCornerShape(18.dp))
                    .clickable { history = true; result = null }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.status == "ok") Icons.Rounded.CheckCircle else if (r.status == "error") Icons.Rounded.Error else Icons.Rounded.StopCircle, null, tint = col)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when (r.status) { "ok" -> "Execução concluída"; "error" -> "A execução falhou"; else -> "Execução parada" }, fontWeight = FontWeight.SemiBold)
                        val lastOut = r.steps.lastOrNull { it.status == "ok" && !it.type.startsWith("trigger") }?.output.orEmpty().let { WfFormat.oneLine(it) }
                        Text((if (r.status == "error") r.error else "${r.steps.size} passos · ${(((r.finishedAt ?: r.startedAt) - r.startedAt) / 100) / 10.0} s" +
                            (lastOut.takeIf { it.isNotBlank() }?.let { " · " + it.replace(Regex("\\s+"), " ").take(70) } ?: "")),
                            fontSize = 12.sp, color = if (r.status == "error") c.err else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ result = null }) { Icon(Icons.Rounded.Close, "Fechar", tint = c.ink3) }
                }
            }
            // Barra de ações (alcance do polegar).
            Row(Modifier.align(Alignment.BottomCenter).padding(14.dp).clip(RoundedCornerShape(26.dp)).background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(26.dp)).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scale = (scale / 1.25f).coerceAtLeast(0.35f) }) { Icon(Icons.Rounded.Remove, "Diminuir") }
                Text("${(scale * 100).roundToInt()}%", fontSize = 12.sp, color = c.ink3, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ scale = (scale * 1.25f).coerceAtMost(2.2f) }) { Icon(Icons.Rounded.Add, "Aumentar") }
                IconButton({ fit() }) { Icon(Icons.Rounded.FitScreen, "Ajustar à tela") }
                Spacer(Modifier.width(2.dp))
                if (active == null) FilledTonalButton({ palette = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Rounded.AddBox, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Nó") }
                Spacer(Modifier.width(6.dp))
                if (active != null && active.status != "waiting") {
                    IconButton({ if (active.status == "paused") WorkflowEngine.resume(active.id) else WorkflowEngine.pause(active.id) }) {
                        Icon(if (active.status == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (active.status == "paused") "Continuar" else "Pausar", tint = c.warn)
                    }
                }
                if (active != null) Button({ WorkflowEngine.cancel(active.id) }, colors = ButtonDefaults.buttonColors(containerColor = c.err), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Parar")
                } else Button({ runNow() }, colors = ButtonDefaults.buttonColors(containerColor = c.accent), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Executar")
                }
            }
            toast?.let { msg ->
                LaunchedEffect(msg) { kotlinx.coroutines.delay(2_200); toast = null }
                Text(msg, Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.ink.copy(alpha = .92f)).padding(horizontal = 14.dp, vertical = 8.dp), color = c.bg, fontSize = 13.sp)
            }
        }
    }

    if (palette) ModalBottomSheet(onDismissRequest = { palette = false }, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NodePalette(onPick = { type, preset -> addNode(type, preset); palette = false })
    }

    selected?.let { nid ->
        val node = w.nodes.firstOrNull { it.id == nid }
        if (node != null) ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            NodeEditor(w, node, shown?.steps?.lastOrNull { it.nodeId == node.id }, nav,
                onChange = { nn -> save(w.copy(nodes = w.nodes.map { if (it.id == nn.id) nn else it })) },
                onDeleteEdge = { eid -> save(w.copy(edges = w.edges.filterNot { it.id == eid })) },
                onConnect = { port -> connectFrom = node.id to port; selected = null },
                onDuplicate = {
                    val (x, y) = freeSpot(w, node.x, node.y + NODE_H + 40f)
                    val copy = node.copy(id = WfIds.new(), x = x, y = y)
                    save(w.copy(nodes = w.nodes + copy)); selected = copy.id
                },
                onDelete = { save(w.copy(nodes = w.nodes.filterNot { it.id == nid }, edges = w.edges.filterNot { it.from == nid || it.to == nid })); selected = null })
        }
    }

    if (history) ModalBottomSheet(onDismissRequest = { history = false }, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RunsPanel(w, myRuns, nav) { history = false }
    }

    if (rename) {
        var name by remember { mutableStateOf(w.name) }
        AlertDialog(onDismissRequest = { rename = false }, containerColor = c.surface,
            title = { Text("Nome do fluxo") }, text = { CueField(name, { name = it }, "Nome") },
            confirmButton = { TextButton({ if (name.isNotBlank()) save(w.copy(name = name.trim())); rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
    if (problemsOpen) AlertDialog(onDismissRequest = { problemsOpen = false }, containerColor = c.surface,
        title = { Text(if (problems.isEmpty()) "Tudo certo" else "Ajustes pendentes") },
        text = { Text(if (problems.isEmpty()) "O fluxo está pronto para rodar." else problems.joinToString("\n") { "• $it" }, fontSize = 14.sp) },
        confirmButton = { TextButton({ problemsOpen = false }) { Text("Ok") } })
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false }, containerColor = c.surface,
        title = { Text("Autonomia total?") },
        text = { Text("Os nós \"Revisar e aprovar\" deste fluxo vão seguir sozinhos com o rascunho da IA — por exemplo, responder e-mails sem você ver antes. Você pode desligar a qualquer momento.") },
        confirmButton = { TextButton({ confirmAuto = false; save(w.copy(autonomous = true)); toast = "Autonomia total ligada" }) { Text("Ligar") } },
        dismissButton = { TextButton({ confirmAuto = false }) { Text("Cancelar") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = c.surface,
        title = { Text("Excluir \"${w.name}\"?") }, text = { Text("O fluxo, o agendamento e o histórico de execuções serão apagados.") },
        confirmButton = { TextButton({
            confirmDelete = false
            active?.let { WorkflowEngine.cancel(it.id) }
            WorkflowScheduler.cancel(ctx, w.id); WorkflowStore.delete(w.id); nav.popBackStack()
        }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
}

/** Paleta: busca + nós prontos + tipos básicos, por categoria. */
@Composable
private fun NodePalette(onPick: (String, WfPreset?) -> Unit) {
    val c = Cue.c
    var q by remember { mutableStateOf("") }
    val norm = { s: String -> java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp)) {
        Text("Adicionar nó", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar: gmail, resumo, http, condição…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            WfCatalog.categories().forEach { cat ->
                val basics = WfCatalog.TYPES.filter { it.category == cat && !it.hidden }.map { Triple(it.type, null as WfPreset?, it.title to it.description) }
                val presets = WfCatalog.PRESETS.filter { it.category == cat }.map { Triple(it.type, it, it.title to it.description) }
                val all = (basics + presets).filter { q.isBlank() || norm(it.third.first + " " + it.third.second).contains(norm(q.trim())) }
                if (all.isNotEmpty()) {
                    item(key = "h_$cat") { SectionLabel(cat) }
                    items(all, key = { cat + it.third.first }) { (type, preset, td) ->
                        val t = WfCatalog.of(type)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(type, preset) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(t.color).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                Icon(nodeIcon(type), null, tint = Color(t.color), modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(td.first, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (preset != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("pronto", fontSize = 10.sp, color = Color(t.color), modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(Color(t.color).copy(alpha = .14f)).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                }
                                Text(td.second, fontSize = 12.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(Cue.c.surface).border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun NodeCard(n: WfNode, status: String?, selected: Boolean, targetable: Boolean, source: Boolean,
                     onTap: () -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit, onPort: (String) -> Unit) {
    val c = Cue.c
    val t = WfCatalog.of(n.type)
    val tint = Color(t.color)
    // Os gestos ficam vivos entre recomposições: sempre chamam a versão atual (posição e fluxo atualizados).
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val pulse = rememberInfiniteTransition(label = "wfpulse")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val st = statusColor(status, c)
    val border = st?.let { if (status == "running" || status == "waiting") it.copy(alpha = a) else it }
        ?: if (selected || source) c.accent else if (targetable) tint.copy(alpha = a) else c.line
    val shape = RoundedCornerShape(if (t.trigger) 22.dp else 16.dp)
    Box(Modifier.size(NODE_W.dp, NODE_H.dp)) {
        Row(Modifier.fillMaxSize().clip(shape).background(c.surface)
            .border(if (selected || st != null || source || targetable) 2.dp else 1.dp, border, shape)
            .pointerInput(n.id) { detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { ch, d -> ch.consume(); drag(d.x, d.y) } }
            .pointerInput(n.id) { detectTapGestures { tap() } }) {
            // Faixa de cor do tipo à esquerda.
            Box(Modifier.width(5.dp).fillMaxHeight().background(tint))
            Row(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    if (status == "running") CircularProgressIndicator(Modifier.size(22.dp), color = tint, strokeWidth = 2.5.dp)
                    else Icon(nodeIcon(n.type), null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title.uppercase(), fontSize = 9.sp, color = tint, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
                    Text(WfLogic.label(n), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle(n), fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (status) {
                    "ok" -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = c.ok)
                    "error" -> Icon(Icons.Rounded.Error, null, Modifier.size(18.dp), tint = c.err)
                    "waiting" -> Icon(Icons.Rounded.HourglassTop, null, Modifier.size(18.dp), tint = c.warn)
                }
            }
        }
        // Entrada (esquerda) e saídas (direita). Toque numa saída e depois no nó de destino para ligar.
        if (!t.trigger) Box(Modifier.align(Alignment.CenterStart).offset(x = (-7).dp).size(14.dp).clip(CircleShape).background(c.surface).border(2.dp, c.ink3, CircleShape))
        t.ports.forEachIndexed { i, port ->
            val y = if (t.ports.size > 1) (if (i == 0) NODE_H * 0.32f else NODE_H * 0.70f) else NODE_H / 2
            val col = when (port) { "true" -> c.ok; "false" -> c.err; else -> tint }
            Box(Modifier.offset { IntOffset((NODE_W - 15).dp.roundToPx(), (y - 15).dp.roundToPx()) }.size(30.dp).clip(CircleShape)
                .clickable { onPort(port) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(col).border(3.dp, c.surface, CircleShape))
            }
            if (port != "main") Text(if (port == "true") "sim" else "não", Modifier.offset { IntOffset((NODE_W + 16).dp.roundToPx(), (y - 8).dp.roundToPx()) }
                .clip(RoundedCornerShape(6.dp)).background(col.copy(alpha = .15f)).padding(horizontal = 5.dp), fontSize = 10.sp, color = col, fontWeight = FontWeight.Bold)
        }
    }
}

private fun subtitle(n: WfNode): String = when (n.type) {
    "trigger_schedule" -> WfLogic.scheduleLabel(n.config)
    "trigger_manual" -> "Toque em Executar"
    "agent", "ai" -> n.config["prompt"].orEmpty().replace(Regex("\\s+"), " ").ifBlank { "Defina a instrução" }
    "if_new" -> n.config["key"].orEmpty().ifBlank { "Defina a chave" }
    "ai_decide" -> n.config["criteria"].orEmpty().replace(Regex("\\s+"), " ").ifBlank { "Defina o critério" }
    "review" -> if (n.config["mode"] == "auto") "Automático (sem perguntar)" else "Pede sua aprovação"
    "connector" -> listOf(n.config["conector"].orEmpty(), n.config["acao"].orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Escolha conector e ação" }
    "mcp" -> n.config["tool"].orEmpty().ifBlank { "Escolha a ferramenta" }
    "http" -> "${n.config["method"] ?: "GET"} ${n.config["url"].orEmpty()}".trim()
    "shell" -> n.config["command"].orEmpty().ifBlank { "Defina o comando" }
    "set" -> n.config["values"].orEmpty().lines().firstOrNull().orEmpty().ifBlank { "Defina os campos" }
    "if" -> "${n.config["left"].orEmpty()} ${WfCatalog.of("if").fields[1].options.firstOrNull { it.first == n.config["op"] }?.second ?: ""} ${n.config["right"].orEmpty()}".trim()
    "delay" -> "${n.config["seconds"] ?: "10"} s"
    "approval" -> n.config["question"].orEmpty()
    "notify" -> n.config["title"].orEmpty()
    else -> ""
}

// ───────────────────────────── Configuração do nó ─────────────────────────────

@Composable
private fun NodeEditor(w: Workflow, node: WfNode, lastStep: WfStep?, nav: NavController, onChange: (WfNode) -> Unit, onDeleteEdge: (String) -> Unit,
                       onConnect: (String) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val c = Cue.c
    val t = WfCatalog.of(node.type)
    val app = CueApp.instance
    val scope = rememberCoroutineScope()
    var cfg by remember(node.id) { mutableStateOf(node.config) }
    var label by remember(node.id) { mutableStateOf(node.label) }
    var lastKey by remember(node.id) { mutableStateOf(t.fields.firstOrNull { it.kind == WfField.Kind.MULTILINE || it.kind == WfField.Kind.TEXT || it.kind == WfField.Kind.JSON }?.key) }
    var helper by remember(node.id) { mutableStateOf<String?>(null) }
    var helperBusy by remember(node.id) { mutableStateOf(false) }
    fun commit(newCfg: Map<String, String> = cfg, newLabel: String = label) { cfg = newCfg; label = newLabel; onChange(node.copy(config = newCfg, label = newLabel)) }
    fun loadHelper(block: suspend () -> String) { helperBusy = true; scope.launch { helper = runCatching { block() }.getOrElse { "Erro: ${it.message}" }; helperBusy = false } }

    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(t.color).copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(nodeIcon(node.type), null, tint = Color(t.color))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(t.description, fontSize = 12.sp, color = c.ink3)
            }
        }
        CueField(label, { commit(newLabel = it) }, "Nome do nó (${t.title})")
        Text("Id para usar em outros nós: {{${node.id}}}", fontSize = 11.sp, color = c.ink3, fontFamily = FontFamily.Monospace)

        if (node.type == "connector") ConnectorConfig(node, cfg, w.nodes.filter { it.id != node.id && w.edges.any { e -> e.to == node.id && e.from == it.id } }) { commit(it) }
        else t.fields.forEach { f ->
            val v = cfg[f.key] ?: f.default
            val visible = when {
                node.type == "trigger_schedule" && f.key == "minutes" -> (cfg["mode"] ?: "daily") == "interval"
                node.type == "trigger_schedule" && f.key == "time" -> (cfg["mode"] ?: "daily") != "interval"
                node.type == "trigger_schedule" && f.key == "weekday" -> (cfg["mode"] ?: "daily") == "weekly"
                node.type == "if" && f.key == "right" -> (cfg["op"] ?: "contains") !in setOf("empty", "not_empty")
                else -> true
            }
            if (!visible) return@forEach
            when (f.kind) {
                WfField.Kind.SELECT -> {
                    Text(f.label, fontSize = 12.sp, color = c.ink2)
                    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.options.forEach { (key, text) -> Chip(text, selected = v == key) { commit(cfg + (f.key to key)) } }
                    }
                }
                else -> {
                    CueField(v, { commit(cfg + (f.key to it)); lastKey = f.key }, f.label + if (f.required) " *" else "",
                        minLines = if (f.kind == WfField.Kind.MULTILINE || f.kind == WfField.Kind.JSON) 3 else 1)
                    if (f.hint.isNotBlank()) Text(f.hint, fontSize = 11.sp, color = c.ink3)
                }
            }
        }

        // Variáveis: toque para inserir no último campo editado.
        val others = w.nodes.filter { it.id != node.id && w.edges.any { e -> e.to == node.id || e.from == it.id } }
        if (node.type != "connector" && lastKey != null && t.fields.any { it.kind != WfField.Kind.SELECT }) {
            Text("Inserir dados de outros nós", fontSize = 12.sp, color = c.ink2)
            Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("{{prev}}" to "Saída anterior", "{{trigger.time}}" to "Hora do disparo") +
                    others.map { "{{${it.id}}}" to WfLogic.label(it) }).forEach { (token, text) ->
                    Chip(text) { lastKey?.let { k -> commit(cfg + (k to (cfg[k].orEmpty() + token))) } }
                }
            }
        }

        // Ajudas por tipo de nó.
        when (node.type) {
            "agent" -> {
                val agents = app.store.state.value.agents
                if (agents.isNotEmpty()) {
                    Text("Agentes", fontSize = 12.sp, color = c.ink2)
                    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Padrão", selected = cfg["agent"].isNullOrBlank()) { commit(cfg + ("agent" to "")) }
                        agents.forEach { a -> Chip(a.name, selected = cfg["agent"].equals(a.name, true)) { commit(cfg + ("agent" to a.name)) } }
                    }
                }
            }
            "mcp" -> {
                val tools = app.store.state.value.connectors.filter { it.enabled }.flatMap { con ->
                    dev.cueopen.app.connectors.Connectors.toolNames(con).map { (name, _) -> dev.cueopen.app.connectors.Connectors.toolFunctionName(con.id, name) to "${con.name} · $name" }
                }
                if (tools.isEmpty()) Text("Nenhum conector MCP ativo. Adicione em Conectores.", fontSize = 12.sp, color = c.warn)
                else {
                    Text("Ferramentas disponíveis", fontSize = 12.sp, color = c.ink2)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tools.take(40).forEach { (fn, text) -> Chip(text, selected = cfg["tool"] == fn) { commit(cfg + ("tool" to fn)) } }
                    }
                }
            }
        }
        if (helperBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        helper?.let { h ->
            Text(h.take(6_000), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
        }

        // Ligações.
        SectionLabel("Ligações")
        t.ports.forEach { port ->
            val outs = w.edges.filter { it.from == node.id && (it.port == port || (port == "main" && it.port == "main")) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (port) { "true" -> "Se sim →"; "false" -> "Se não →"; else -> "Depois →" }, fontSize = 13.sp, color = c.ink2, modifier = Modifier.width(78.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    outs.forEach { e ->
                        val target = w.nodes.firstOrNull { it.id == e.to }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(target?.let { WfLogic.label(it) } ?: "?", Modifier.weight(1f), fontSize = 13.sp)
                            IconButton({ onDeleteEdge(e.id) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.LinkOff, "Desligar", Modifier.size(18.dp), tint = c.err) }
                        }
                    }
                    TextButton({ onConnect(port) }) { Icon(Icons.Rounded.AddLink, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Ligar a outro nó") }
                }
            }
        }

        if (!WfCatalog.of(node.type).trigger && node.type != "approval") NodeTester(w.copy(nodes = w.nodes.map { if (it.id == node.id) node.copy(config = cfg, label = label) else it }), node, lastStep)
        lastStep?.let { s ->
            if (s.status == "error") Text("Última execução: erro — ${s.error}".take(1_500), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.err)
            s.sessionId?.let { sid -> TextButton({ nav.navigate("chat/$sid") }) { Text("Abrir o chat do agente") } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onDuplicate) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Duplicar") }
            OutlinedButton(onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = c.err)) {
                Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Excluir nó")
            }
        }
    }
}

// ───────────────────────────── Nó Conector (unificado) ─────────────────────────────

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun prettyOut(raw: String): String = runCatching {
    prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.Json.parseToJsonElement(raw))
}.getOrDefault(raw)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectorConfig(node: WfNode, cfg: Map<String, String>, upstream: List<WfNode>, onCfg: (Map<String, String>) -> Unit) {
    val c = Cue.c
    val app = CueApp.instance
    val kind = cfg["source"].orEmpty().ifBlank { WfConnectors.HUB }
    val id = cfg["conector"].orEmpty()
    val action = cfg["acao"].orEmpty()
    var sources by remember { mutableStateOf<List<WfConnectors.Source>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { sources = runCatching { WfConnectors.sources(app.store) }.getOrDefault(emptyList()) }
    var actions by remember(kind, id) { mutableStateOf<List<WfConnectors.Action>?>(null) }
    LaunchedEffect(kind, id) { actions = if (id.isBlank()) null else runCatching { WfConnectors.actions(app.store, kind, id) }.getOrDefault(emptyList()) }
    var fields by remember(kind, id, action) { mutableStateOf<List<WfConnectors.Field>?>(null) }
    var fieldsErr by remember(kind, id, action) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, id, action) {
        if (id.isBlank() || action.isBlank()) return@LaunchedEffect
        runCatching { WfConnectors.fields(app.store, kind, id, action, cfg["conexao"].orEmpty()) }
            .onSuccess { fields = it }.onFailure { fieldsErr = it.message; fields = emptyList() }
    }
    val campos = remember(cfg["campos"]) {
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(cfg["campos"].orEmpty().ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject }.getOrNull()
    }
    var raw by remember(node.id) { mutableStateOf(false) }
    var focus by remember(node.id) { mutableStateOf<String?>(null) }
    var pickAction by remember { mutableStateOf(false) }
    val openGroups = remember { mutableStateMapOf<String, Boolean>() }
    fun value(k: String): String = campos?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.toString() }.orEmpty()
    fun setValue(k: String, v: String) {
        val base = campos ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val next = if (v.isEmpty()) base - k else base + (k to kotlinx.serialization.json.JsonPrimitive(v))
        onCfg(cfg + ("campos" to prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(next))))
    }

    // 1 · Conector
    SectionLabel("1 · Conector")
    val list = sources
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        list.isEmpty() -> Text("Nenhum conector ativo. Conecte um app ou servidor MCP em Conectores e volte aqui.", fontSize = 13.sp, color = c.warn)
        else -> {
            val current = list.firstOrNull { it.kind == kind && (it.id.equals(id, true) || it.name.equals(id, true)) && (cfg["conexao"].isNullOrBlank() || it.connection.isBlank() || it.connection == cfg["conexao"]) }
            if (id.isNotBlank() && current == null)
                Text("\"$id\" não está conectado. Escolha um conector ativo abaixo (ou conecte-o em Conectores).", fontSize = 12.sp, color = c.warn)
            // Grupos colapsáveis (MCP pode ter dezenas de servidores): lista com rolagem própria, carregada sob demanda.
            list.groupBy { it.kind }.forEach { (k, items) ->
                val isOpen = openGroups[k] ?: (current?.kind == k || (current == null && k != WfConnectors.MCP && items.size <= 8))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openGroups[k] = !isOpen }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(WfConnectors.kindLabel(k), fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Text("${items.size} ativo" + if (items.size == 1) "" else "s", fontSize = 11.sp, color = c.ink3, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(c.surface2).padding(horizontal = 6.dp, vertical = 1.dp))
                    Spacer(Modifier.weight(1f))
                    if (!isOpen && current?.kind == k) Text(current?.name.orEmpty(), fontSize = 12.sp, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.ink3)
                }
                if (isOpen) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.kind + it.id + it.connection }) { s ->
                        val sel = s == current
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent.copy(alpha = .10f) else c.surface2)
                            .border(1.dp, if (sel) c.accent else c.line, RoundedCornerShape(14.dp))
                            .clickable {
                                if (!sel) onCfg(cfg + mapOf("source" to s.kind, "conector" to s.id, "conexao" to s.connection) +
                                    (if (s.kind != kind || !s.id.equals(id, true)) mapOf("acao" to "", "campos" to "{}") else emptyMap()))
                            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2FBF71).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                Text(s.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color(0xFF2FBF71))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.detail + if (!s.ready) " · precisa reconectar" else "", fontSize = 11.sp, color = if (s.ready) c.ink3 else c.warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            TextButton({ sources = null; reload++ }) { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Atualizar lista") }
        }
    }

    // 2 · Ação
    if (id.isNotBlank()) {
        SectionLabel("2 · Ação")
        val a = actions?.firstOrNull { it.name == action }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(enabled = !actions.isNullOrEmpty()) { pickAction = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a?.title ?: action.ifBlank { if (actions == null) "Carregando ações…" else if (actions!!.isEmpty()) "Sem ações disponíveis" else "Escolher ação" },
                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                (a?.description?.takeIf { it.isNotBlank() } ?: action.takeIf { it.isNotBlank() && a == null }?.let { "Nome técnico: $it" })?.let {
                    Text(it, fontSize = 11.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (actions == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.UnfoldMore, null, tint = c.ink3)
        }
    }

    // 3 · Campos
    if (id.isNotBlank() && action.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("3 · Campos") }
            TextButton({ raw = !raw }) { Text(if (raw) "Formulário" else "Editar JSON", fontSize = 12.sp) }
        }
        val fs = fields
        when {
            raw || campos == null -> {
                if (campos == null) Text("O JSON dos campos está inválido; corrija abaixo.", fontSize = 12.sp, color = c.err)
                CueField(cfg["campos"].orEmpty(), { onCfg(cfg + ("campos" to it)) }, "Campos (JSON)", minLines = 4)
            }
            fs == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            fs.isEmpty() -> Text(fieldsErr?.let { "Não foi possível ler os campos: $it" } ?: "Esta ação não precisa de campos.", fontSize = 12.sp, color = if (fieldsErr != null) c.warn else c.ink3)
            else -> fs.forEach { f ->
                val v = value(f.key)
                if (f.type == "checkbox" || f.type == "boolean") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label + if (f.required) " *" else "", fontSize = 14.sp)
                            if (f.description.isNotBlank()) Text(f.description, fontSize = 11.sp, color = c.ink3, maxLines = 2)
                        }
                        Switch(checked = (v.ifBlank { f.default }).equals("true", true), onCheckedChange = { setValue(f.key, it.toString()) })
                    }
                } else {
                    CueField(v, { setValue(f.key, it); focus = f.key }, f.label + (if (f.required) " *" else "") + if (f.default.isNotBlank() && v.isBlank()) " (padrão: ${f.default.take(30)})" else "",
                        minLines = if (f.type.contains("long") || f.type == "json" || f.type == "object" || f.key.contains("body", true) || f.key.contains("content", true)) 3 else 1)
                    if (f.description.isNotBlank()) Text(f.description, fontSize = 11.sp, color = c.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (f.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.options.take(30).forEach { (lbl, ov) -> Chip(lbl.take(40), selected = v == ov) { setValue(f.key, ov); focus = f.key } }
                    } else if (f.dynamic) Text("Lista carregada do app quando a conta está conectada; você também pode digitar o valor.", fontSize = 11.sp, color = c.ink3)
                }
            }
        }
        if (!raw && !fields.isNullOrEmpty()) {
            Text("Inserir dados de outros nós" + (focus?.let { " em \"$it\"" } ?: " (edite um campo primeiro)"), fontSize = 12.sp, color = c.ink2)
            Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("{{prev}}" to "Saída anterior", "{{trigger.time}}" to "Hora do disparo") + upstream.map { "{{${it.id}}}" to WfLogic.label(it) }).forEach { (token, text) ->
                    Chip(text) { focus?.let { k -> setValue(k, value(k) + token) } }
                }
            }
        }
        SectionLabel("Formato da resposta")
        Text(WfConnectors.responseShape(kind, node.id), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
    }

    if (pickAction) {
        var q by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pickAction = false }, containerColor = c.surface, title = { Text("Escolher ação") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar ação") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(actions.orEmpty().filter { q.isBlank() || (it.name + " " + it.title + " " + it.description).contains(q.trim(), true) }, key = { it.name }) { a ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                pickAction = false
                                if (a.name != action) onCfg(cfg + mapOf("acao" to a.name, "campos" to "{}"))
                            }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                Text(a.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (a.name == action) c.accent else c.ink)
                                if (a.description.isNotBlank()) Text(a.description, fontSize = 11.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ pickAction = false }) { Text("Fechar") } })
    }
}

/** Testa o nó sozinho e mostra a resposta real, com os caminhos prontos para usar em outros nós. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeTester(w: Workflow, node: WfNode, lastStep: WfStep?) {
    val c = Cue.c
    val scope = rememberCoroutineScope()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var busy by remember(node.id) { mutableStateOf(false) }
    var res by remember(node.id) { mutableStateOf<WorkflowEngine.NodeResult?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var showRaw by remember(node.id) { mutableStateOf(false) }
    SectionLabel("Testar este nó")
    Text("Roda só este nó agora, com os dados da última execução como entrada. Ações reais (enviar e-mail, postar) acontecem de verdade.", fontSize = 11.sp, color = c.ink3)
    Button({ busy = true; res = null; scope.launch { res = WorkflowEngine.testNode(w, node.id); busy = false } }, enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = c.bg, strokeWidth = 2.dp) else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp)); Text(if (busy) "Testando…" else "Testar")
    }
    val out = res?.takeIf { it.error == null }?.output ?: lastStep?.takeIf { res == null && it.status == "ok" }?.output
    res?.error?.let { e ->
        Text("Erro: $e", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.err.copy(alpha = .08f)).padding(10.dp), fontSize = 12.sp, color = c.err)
    }
    if (out != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (res != null) "Resultado do teste" else "Resultado da última execução", fontSize = 12.sp, color = c.ink2, modifier = Modifier.weight(1f))
            if (WfFormat.isJson(out)) TextButton({ showRaw = !showRaw }) { Text(if (showRaw) "Ver leitura clara" else "Dados técnicos", fontSize = 12.sp) }
        }
        if (showRaw) Text(prettyOut(out).take(4_000), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
        else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp)) { CueMarkdown(WfFormat.readable(out, maxItems = 10).take(3_000)) }
        val paths = WfLogic.paths(out)
        if (paths.isNotEmpty()) {
            Text("Use em outros nós (toque para copiar)" + (copied?.let { " · copiado $it" } ?: ""), fontSize = 12.sp, color = c.ink2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("") + paths.take(30)).forEach { p ->
                    val token = if (p.isEmpty()) "{{${node.id}}}" else "{{${node.id}.$p}}"
                    Chip(if (p.isEmpty()) "tudo" else p) { clip.setText(androidx.compose.ui.text.AnnotatedString(token)); copied = token }
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollIfNeeded(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

// ───────────────────────────── Execuções ─────────────────────────────

@Composable
private fun RunsPanel(w: Workflow, runs: List<WfRun>, nav: NavController, onClose: () -> Unit) {
    val c = Cue.c
    var open by remember { mutableStateOf(runs.firstOrNull()?.id) }
    var raw by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp).heightIn(max = 640.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Execuções", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (runs.any { it.status != "running" && it.status != "waiting" }) TextButton({ WorkflowStore.clearRuns(w.id) }) { Text("Limpar histórico") }
        }
        if (runs.isEmpty()) Text("Nenhuma execução ainda. Toque em Executar.", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { r ->
                val col = statusColor(r.status, c) ?: c.ink3
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).clickable { open = if (open == r.id) null else r.id }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                        Spacer(Modifier.width(8.dp))
                        Text(when (r.status) { "ok" -> "Concluída"; "error" -> "Falhou"; "running" -> "Executando"; "waiting" -> "Aguardando aprovação"; "paused" -> "Pausada"; "queued" -> "Na fila"; else -> "Parada" },
                            fontWeight = FontWeight.SemiBold, color = col, modifier = Modifier.weight(1f))
                        Text("${when (r.trigger) { "schedule" -> "agendada"; "webhook" -> "webhook"; "test" -> "teste"; else -> "manual" }} · ${timeFmt.format(Date(r.startedAt))}", fontSize = 11.sp, color = c.ink3)
                    }
                    r.finishedAt?.let { Text("Duração: ${((it - r.startedAt) / 1000).coerceAtLeast(0)} s", fontSize = 11.sp, color = c.ink3) }
                    if (r.error.isNotBlank()) Text(r.error.take(400), fontSize = 12.sp, color = c.err)
                    if (WorkflowEngine.isActive(r.status)) TextButton({ WorkflowEngine.cancel(r.id) }) { Text("Parar execução", color = c.err) }
                    if (open == r.id && r.steps.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dados técnicos", fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f))
                        Switch(checked = raw, onCheckedChange = { raw = it }, modifier = Modifier.scale(0.8f))
                    }
                    if (open == r.id) r.steps.forEach { s ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(nodeIcon(s.type), null, Modifier.size(16.dp), tint = statusColor(s.status, c) ?: c.ink3)
                            Spacer(Modifier.width(6.dp))
                            Text(s.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            s.finishedAt?.let { Text("${((it - s.startedAt) / 100) / 10.0} s", fontSize = 11.sp, color = c.ink3) }
                        }
                        // Leitura clara por padrão; os dados técnicos (JSON) só quando o usuário pede.
                        if (s.status == "error") Text(s.error.take(800), Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp), fontSize = 12.sp, color = c.err)
                        else if (s.output.isNotBlank()) {
                            if (raw) Text(prettyOut(s.output).take(3_000), Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, color = c.ink3)
                            else if (s.type.startsWith("trigger")) Text(if (r.trigger == "schedule") "Disparado pelo agendamento" else if (r.trigger == "test") "Teste" else "Disparado por você",
                                Modifier.padding(start = 22.dp, top = 2.dp), fontSize = 12.sp, color = c.ink3)
                            else Box(Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp)) { CueMarkdown(WfFormat.readable(s.output, maxItems = 10).take(2_000)) }
                        }
                        if (r.status == "waiting" && s.status == "running" && s.type == "review" && WorkflowEngine.review(r.id, s.nodeId) != null)
                            Button({ onClose(); nav.navigate("review/${r.id}/${s.nodeId}") }, Modifier.padding(start = 22.dp), colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
                                Icon(Icons.Rounded.RateReview, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Revisar e aprovar")
                            }
                        if (r.status == "waiting" && s.status == "running" && s.type == "approval" && WorkflowEngine.pendingApproval(r.id, s.nodeId)) Row(Modifier.padding(start = 22.dp)) {
                            Button({ WorkflowEngine.approve(r.id, s.nodeId, true) }, colors = ButtonDefaults.buttonColors(containerColor = c.ok)) { Text("Aprovar") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton({ WorkflowEngine.approve(r.id, s.nodeId, false) }) { Text("Recusar") }
                        }
                        s.sessionId?.let { sid -> TextButton({ onClose(); nav.navigate("chat/$sid") }, Modifier.padding(start = 14.dp)) { Text("Abrir o chat do agente", fontSize = 12.sp) } }
                    }
                }
            }
        }
    }
}

/** Ponto verde pulsando: fluxo ativo, escutando em tempo real. */
@Composable
private fun LiveDot(color: Color) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = a)))
}
