package dev.fluwy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.ui.theme.Fluwy

/**
 * Mascote do Agent em versão estática (listas, cabeçalhos). Delega ao novo sistema de mascotes;
 * IDs antigos salvos em `face` são migrados para um mascote novo automaticamente.
 */
@Composable
fun AgentFace(color: Color, face: String = "jelly", size: Dp = 40.dp, modifier: Modifier = Modifier) =
    dev.fluwy.app.ui.mascot.Mascot(face, color, size, modifier, animated = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluwyTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Fluwy.c.bg),
    )
}

@Composable
fun FluwyCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Fluwy.c
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun PillButton(text: String, icon: ImageVector? = null, primary: Boolean = true, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Fluwy.c
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) c.ink else c.surface2, contentColor = if (primary) c.bg else c.ink,
        ),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = if (primary) c.bg else c.ink); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, color = if (primary) c.bg else c.ink)
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Fluwy.c
    Row(
        Modifier.clip(CircleShape)
            .background(if (selected) c.ink else c.surface)
            .border(1.dp, if (selected) c.ink else c.line, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = if (selected) c.bg else c.ink); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 14.sp, color = if (selected) c.bg else c.ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Fluwy.c.ink3, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = Fluwy.c
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(c.accentSoft).border(2.dp, c.ink, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(40.dp), tint = c.ink) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun FluwyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, minLines: Int = 1, singleLine: Boolean = minLines == 1, password: Boolean = false) {
    val c = Fluwy.c
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        minLines = minLines, singleLine = singleLine, shape = RoundedCornerShape(16.dp),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface),
    )
}

fun skillIcon(name: String): ImageVector = when (name) {
    "presentation" -> Icons.Rounded.Slideshow
    "globe" -> Icons.Rounded.Language
    "search" -> Icons.Rounded.TravelExplore
    "book" -> Icons.Rounded.MenuBook
    "phone" -> Icons.Rounded.PhoneAndroid
    "image" -> Icons.Rounded.Image
    "wand" -> Icons.Rounded.AutoFixHigh
    else -> Icons.Rounded.AutoAwesome
}

val agentColors = listOf(0xFFFF6B3D, 0xFF4D8DFF, 0xFF2FBF71, 0xFFFFC53D, 0xFFB57BFF, 0xFFFF5C8A, 0xFF2EC4C4, 0xFF9C978E)
/** IDs dos mascotes nativos (o seletor também lista os importados pelo usuário). */
val agentFaces: List<String> get() = dev.fluwy.app.ui.mascot.Mascots.builtinIds

@Composable
fun Divider() = HorizontalDivider(color = Fluwy.c.line, thickness = 1.dp)

@Composable
fun outline() = BorderStroke(1.dp, Fluwy.c.line)
