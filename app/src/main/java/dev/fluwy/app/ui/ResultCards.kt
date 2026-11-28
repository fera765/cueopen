package dev.fluwy.app.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.CardEntry
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.delay

/** Cards de resultado (produtos, notícias, sites…) renderizados a partir dos dados estruturados do agente. */
@Composable
fun ResultCards(item: ChatItem.Cards, sessionId: String?, onSend: ((String) -> Unit)?) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    fun open(url: String) {
        if (!url.startsWith("http")) return
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url)) }
    }
    fun select(n: Int) {
        val e = item.items.getOrNull(n) ?: return
        if (sessionId == null || item.selected >= 0) return
        // A escolha fica registrada no card (persistida na sessão) e vai para o agente responsável por esta conversa.
        FluwyApp.instance.store.replaceItem(sessionId, item.copy(selected = n))
        onSend?.invoke("[Seleção] Escolhi o item ${n + 1} dos resultados \"${item.title.ifBlank { item.kind }}\": ${e.title}" +
            (if (e.price.isNotBlank()) " — ${e.price}" else "") + (if (e.source.isNotBlank()) " — ${e.source}" else "") +
            (if (e.url.isNotBlank()) "\n${e.url}" else "") + (if (e.ref.isNotBlank()) "\n(ref ${e.ref})" else "") +
            "\nSiga com este item. Antes de qualquer compra, pagamento ou ação irreversível, peça minha confirmação.")
    }
    val product = item.kind == "product"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.title.isNotBlank()) Text(item.title, fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(item.items) { n, e ->
                val chosen = item.selected == n
                Column(
                    Modifier.width(if (product) 168.dp else 240.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                        .then(if (chosen) Modifier.border(2.dp, c.accent, RoundedCornerShape(14.dp)) else Modifier)
                        .clickable { if (product && item.selected < 0) select(n) else open(e.url) }
                ) {
                    if (e.image.startsWith("http")) Box {
                        SubcomposeAsyncImage(e.image, e.title, Modifier.fillMaxWidth().height(if (product) 150.dp else 130.dp).background(androidx.compose.ui.graphics.Color.White),
                            contentScale = if (product) ContentScale.Fit else ContentScale.Crop,
                            loading = { Box(Modifier.fillMaxSize().background(c.surface2)) },
                            error = { Box(Modifier.fillMaxSize().background(c.surface2), contentAlignment = Alignment.Center) { Text("sem imagem", fontSize = 10.sp, color = c.ink3) } })
                        if (e.badge.isNotBlank()) Text(e.badge, fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color(0xCC111111)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(e.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.price.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.price, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink)
                            if (e.oldPrice.isNotBlank()) { Spacer(Modifier.width(6.dp)); Text(e.oldPrice, fontSize = 11.sp, color = c.ink3, textDecoration = TextDecoration.LineThrough) }
                        }
                        if (e.subtitle.isNotBlank()) Text(e.subtitle, fontSize = 11.sp, color = c.ink2, maxLines = if (product) 2 else 3, overflow = TextOverflow.Ellipsis)
                        val meta = listOf(e.source, e.date, e.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty()).filter { it.isNotBlank() }
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 10.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (product) Text(if (chosen) "Selecionado ✓" else if (item.selected >= 0) "" else "Selecionar", fontSize = 11.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            if (e.url.startsWith("http")) Icon(Icons.Rounded.OpenInNew, "Abrir", Modifier.size(16.dp).clickable { open(e.url) }, tint = c.ink3)
                        }
                    }
                }
            }
        }
        // Atalhos de escolha (como no exemplo): um toque já registra a seleção.
        if (product && item.selected < 0 && item.items.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.items.take(4).forEachIndexed { n, e ->
                Box(Modifier.clip(CircleShape).background(c.surface2).clickable { select(n) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(e.title.split(' ').take(3).joinToString(" "), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Mensagem de áudio do agente: tocar/pausar, progresso, duração e o texto falado. */
@Composable
fun AudioMessage(item: ChatItem.Audio) {
    val c = Fluwy.c
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var dur by remember { mutableStateOf(item.durationMs.toInt()) }
    var showText by remember { mutableStateOf(false) }
    DisposableEffect(item.path) { onDispose { runCatching { player?.release() }; player = null } }
    LaunchedEffect(playing) { while (playing) { pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); delay(200) } }
    fun toggle() {
        val p = player ?: runCatching {
            MediaPlayer().apply {
                setDataSource(item.path); prepare(); dur = duration
                setOnCompletionListener { playing = false; pos = 0; seekTo(0) }
            }
        }.getOrNull()?.also { player = it } ?: return
        if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
    }
    fun mmss(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    Column(Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ toggle() }, Modifier.size(38.dp).clip(CircleShape).background(c.accentSoft)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausar" else "Tocar", tint = c.accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
                Spacer(Modifier.height(4.dp))
                Text("${mmss(pos)} / ${if (dur > 0) mmss(dur) else "—"}", fontSize = 11.sp, color = c.ink3)
            }
        }
        if (item.text.isNotBlank()) Text(if (showText) item.text else "Mostrar texto", fontSize = 12.sp, color = if (showText) c.ink2 else c.accent,
            modifier = Modifier.padding(top = 6.dp).clickable { showText = !showText })
    }
}
