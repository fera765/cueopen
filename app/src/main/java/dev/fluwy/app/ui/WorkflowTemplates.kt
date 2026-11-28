package dev.fluwy.app.ui
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
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.workflow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal fun templates(): List<WfTemplate> = listOf(
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
