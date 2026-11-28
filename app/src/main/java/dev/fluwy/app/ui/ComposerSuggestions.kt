package dev.fluwy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.agent.MentionSuggest
import dev.fluwy.app.agent.Mentions
import dev.fluwy.app.agent.SlashCommands
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.Skill
import dev.fluwy.app.ui.theme.Fluwy

/** Uma linha da caixa de sugestões. [replaceAll]: substitui o texto todo (comandos) em vez do último token. */
data class ComposerSuggestion(val label: String, val description: String, val insert: String, val icon: ImageVector, val replaceAll: Boolean = false, val header: String? = null)

/** Monta as sugestões do Composer a partir do texto atual. */
fun composerSuggestions(text: String, token: String, skills: List<Skill>, agents: List<AgentProfile>, targets: List<Mentions.Target>): List<ComposerSuggestion> {
    SlashCommands.pickerQuery(text)?.let { (cmd, q) ->
        val items = if (cmd == "/skill") skills.filter { it.enabled }.map { it.name to it.description }
            else agents.map { it.name to it.mission.ifBlank { it.instructions } }
        return items.filter { q.isBlank() || it.first.contains(q, true) }.take(30).map { (name, desc) ->
            ComposerSuggestion("@$name", desc.take(60), "@$name ", if (cmd == "/skill") Icons.Rounded.AutoAwesome else Icons.Rounded.SmartToy, replaceAll = true)
        }
    }
    if (text.startsWith("/") && !text.contains(' ')) {
        var last: SlashCommands.Group? = null
        return SlashCommands.filter(text).map { c ->
            val header = if (c.group != last) c.group.title.also { last = c.group } else null
            ComposerSuggestion(c.name + (c.arg?.let { " $it" } ?: ""), c.description, SlashCommands.insertText(c), Icons.Rounded.Terminal, replaceAll = true, header = header)
        }
    }
    val names: (String) -> String = { slug -> runCatching { dev.fluwy.app.browser.WebApiHub.find(FluwyApp.instance, slug)?.name }.getOrNull() ?: "" }
    return MentionSuggest.suggest(token, targets, targets, names).map {
        ComposerSuggestion(it.label, it.description, it.insert, if (it.kind == Mentions.Kind.WEBAPI) Icons.Rounded.Api else Icons.Rounded.Cable)
    }
}

@Composable
fun ComposerSuggestionBox(items: List<ComposerSuggestion>, onPick: (ComposerSuggestion) -> Unit) {
    val c = Fluwy.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        items.forEach { sg ->
            sg.header?.let { Text(it.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, modifier = Modifier.padding(start = 14.dp, top = 8.dp)) }
            Row(Modifier.fillMaxWidth().clickable { onPick(sg) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(sg.icon, null, Modifier.size(18.dp), tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Text(sg.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(sg.description, fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
