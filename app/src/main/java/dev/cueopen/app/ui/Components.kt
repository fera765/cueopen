package dev.cueopen.app.ui

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
import dev.cueopen.app.ui.theme.Cue

/**
 * Avatar de bot do Fluwy Bot (estilo próprio): um CORPO de forma variada (bolha, hexágono, quadradinho,
 * círculo, triângulo, pílula, gota, nuvem) com contorno grosso e um "rosto" simples. [face] guarda o estilo
 * ("blob", "hex", "square", "circle", "triangle", "pill", "drop", "cloud"); valores antigos continuam válidos.
 */
@Composable
fun AgentFace(color: Color, face: String = "blob", size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val ink = Color(0xFF111111)
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * 0.07f
        fun p(block: Path.() -> Unit) = Path().apply(block)
        // corpo por estilo (coordenadas relativas 0..1). Compat: wink/smile/side/dot = formas antigas.
        val style = when (face) { "wink", "blob" -> "blob"; "smile" -> "square"; "side" -> "pill"; "dot" -> "circle"; else -> face }
        val body = when (style) {
            "hex" -> p {
                moveTo(w*.5f, h*.06f); lineTo(w*.92f, h*.3f); lineTo(w*.92f, h*.7f)
                lineTo(w*.5f, h*.94f); lineTo(w*.08f, h*.7f); lineTo(w*.08f, h*.3f); close()
            }
            "square" -> p {
                val r = w*.28f
                moveTo(w*.12f+r, h*.12f); lineTo(w*.88f-r, h*.12f); cubicTo(w*.88f, h*.12f, w*.88f, h*.12f, w*.88f, h*.12f+r)
                lineTo(w*.88f, h*.88f-r); cubicTo(w*.88f, h*.88f, w*.88f, h*.88f, w*.88f-r, h*.88f)
                lineTo(w*.12f+r, h*.88f); cubicTo(w*.12f, h*.88f, w*.12f, h*.88f, w*.12f, h*.88f-r)
                lineTo(w*.12f, h*.12f+r); cubicTo(w*.12f, h*.12f, w*.12f, h*.12f, w*.12f+r, h*.12f); close()
            }
            "circle" -> p { addOval(androidx.compose.ui.geometry.Rect(Offset(w*.1f, h*.1f), androidx.compose.ui.geometry.Size(w*.8f, h*.8f))) }
            "triangle" -> p {
                moveTo(w*.5f, h*.1f); cubicTo(w*.58f, h*.1f, w*.62f, h*.16f, w*.66f, h*.24f)
                lineTo(w*.9f, h*.74f); cubicTo(w*.95f, h*.85f, w*.9f, h*.9f, w*.78f, h*.9f)
                lineTo(w*.22f, h*.9f); cubicTo(w*.1f, h*.9f, w*.05f, h*.85f, w*.1f, h*.74f)
                lineTo(w*.34f, h*.24f); cubicTo(w*.38f, h*.16f, w*.42f, h*.1f, w*.5f, h*.1f); close()
            }
            "pill" -> p {
                val r = h*.34f
                addRoundRect(androidx.compose.ui.geometry.RoundRect(w*.08f, h*.3f, w*.92f, h*.7f, androidx.compose.ui.geometry.CornerRadius(r, r)))
            }
            "drop" -> p {
                moveTo(w*.5f, h*.06f); cubicTo(w*.82f, h*.34f, w*.92f, h*.52f, w*.92f, h*.64f)
                cubicTo(w*.92f, h*.85f, w*.74f, h*.96f, w*.5f, h*.96f)
                cubicTo(w*.26f, h*.96f, w*.08f, h*.85f, w*.08f, h*.64f)
                cubicTo(w*.08f, h*.52f, w*.18f, h*.34f, w*.5f, h*.06f); close()
            }
            "cloud" -> p {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.06f, h*.34f), androidx.compose.ui.geometry.Size(w*.44f, h*.44f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.5f, h*.34f), androidx.compose.ui.geometry.Size(w*.44f, h*.44f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.28f, h*.16f), androidx.compose.ui.geometry.Size(w*.44f, h*.5f)))
            }
            else -> p { // blob
                moveTo(w*.5f, h*.08f)
                cubicTo(w*.82f, h*.08f, w*.94f, h*.3f, w*.94f, h*.52f)
                cubicTo(w*.94f, h*.8f, w*.74f, h*.94f, w*.5f, h*.94f)
                cubicTo(w*.26f, h*.94f, w*.06f, h*.8f, w*.06f, h*.52f)
                cubicTo(w*.06f, h*.3f, w*.18f, h*.08f, w*.5f, h*.08f); close()
            }
        }
        drawPath(body, color)
        drawPath(body, ink, style = Stroke(sw))
        val eyeY = h * .52f
        // expressão por estilo (cada forma tem um "jeitão" próprio, mas original)
        when (style) {
            "hex" -> { // olhos ovais atentos
                drawOval(ink, Offset(w*.3f, eyeY-h*.06f), androidx.compose.ui.geometry.Size(w*.1f, h*.13f))
                drawOval(ink, Offset(w*.6f, eyeY-h*.06f), androidx.compose.ui.geometry.Size(w*.1f, h*.13f))
                drawCircle(Color.White, w*.028f, Offset(w*.33f, eyeY-h*.02f)); drawCircle(Color.White, w*.028f, Offset(w*.63f, eyeY-h*.02f))
            }
            "square" -> { drawCircle(ink, w*.05f, Offset(w*.38f, eyeY)); drawCircle(ink, w*.05f, Offset(w*.62f, eyeY)) }
            "circle" -> { // piscadinha
                drawCircle(Color.White, w*.1f, Offset(w*.37f, eyeY)); drawCircle(ink, w*.1f, Offset(w*.37f, eyeY), style = Stroke(sw*.7f)); drawCircle(ink, w*.045f, Offset(w*.38f, eyeY))
                drawLine(ink, Offset(w*.57f, eyeY-h*.06f), Offset(w*.7f, eyeY), sw, StrokeCap.Round); drawLine(ink, Offset(w*.7f, eyeY), Offset(w*.57f, eyeY+h*.06f), sw, StrokeCap.Round)
            }
            "triangle" -> { // olhos ">" alerta
                drawLine(ink, Offset(w*.3f, eyeY-h*.05f), Offset(w*.42f, eyeY), sw, StrokeCap.Round); drawLine(ink, Offset(w*.42f, eyeY), Offset(w*.3f, eyeY+h*.05f), sw, StrokeCap.Round)
                drawLine(ink, Offset(w*.58f, eyeY-h*.05f), Offset(w*.7f, eyeY), sw, StrokeCap.Round); drawLine(ink, Offset(w*.7f, eyeY), Offset(w*.58f, eyeY+h*.05f), sw, StrokeCap.Round)
            }
            "pill" -> { drawLine(ink, Offset(w*.32f, eyeY), Offset(w*.44f, eyeY), sw, StrokeCap.Round); drawLine(ink, Offset(w*.56f, eyeY), Offset(w*.68f, eyeY), sw, StrokeCap.Round) }
            "drop" -> { drawCircle(ink, w*.055f, Offset(w*.37f, eyeY)); drawCircle(ink, w*.055f, Offset(w*.63f, eyeY)); drawArc(ink, 20f, 140f, false, Offset(w*.37f, h*.56f), androidx.compose.ui.geometry.Size(w*.26f, h*.16f), style = Stroke(sw, cap = StrokeCap.Round)) }
            "cloud" -> { drawCircle(Color.White, w*.07f, Offset(w*.4f, eyeY)); drawCircle(ink, w*.035f, Offset(w*.4f, eyeY)); drawCircle(Color.White, w*.07f, Offset(w*.6f, eyeY)); drawCircle(ink, w*.035f, Offset(w*.6f, eyeY)) }
            else -> { drawCircle(ink, w*.05f, Offset(w*.38f, eyeY)); drawCircle(ink, w*.05f, Offset(w*.62f, eyeY)); drawArc(ink, 20f, 140f, false, Offset(w*.38f, h*.54f), androidx.compose.ui.geometry.Size(w*.24f, h*.16f), style = Stroke(sw, cap = StrokeCap.Round)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CueTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Cue.c.bg),
    )
}

@Composable
fun CueCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Cue.c
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
    val c = Cue.c
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
    val c = Cue.c
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
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Cue.c.ink3, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = Cue.c
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
fun CueField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, minLines: Int = 1, singleLine: Boolean = minLines == 1, password: Boolean = false) {
    val c = Cue.c
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
val agentFaces = listOf("blob", "hex", "square", "circle", "triangle", "pill", "drop", "cloud")

@Composable
fun Divider() = HorizontalDivider(color = Cue.c.line, thickness = 1.dp)

@Composable
fun outline() = BorderStroke(1.dp, Cue.c.line)
