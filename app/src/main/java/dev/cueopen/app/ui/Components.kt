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
            "star" -> avatarStarPath(w, h, 5, .44f, .20f)
            "diamond" -> avatarPolygon(w, h, listOf(.5f to .06f, .92f to .5f, .5f to .94f, .08f to .5f))
            "heart" -> p {
                moveTo(w*.5f, h*.9f); cubicTo(w*.4f, h*.81f, w*.1f, h*.57f, w*.1f, h*.35f)
                cubicTo(w*.1f, h*.13f, w*.39f, h*.06f, w*.5f, h*.29f)
                cubicTo(w*.61f, h*.06f, w*.9f, h*.13f, w*.9f, h*.35f)
                cubicTo(w*.9f, h*.57f, w*.6f, h*.81f, w*.5f, h*.9f); close()
            }
            "flower" -> p {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.38f, h*.04f), androidx.compose.ui.geometry.Size(w*.24f, h*.45f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.38f, h*.51f), androidx.compose.ui.geometry.Size(w*.24f, h*.45f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.04f, h*.38f), androidx.compose.ui.geometry.Size(w*.45f, h*.24f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.51f, h*.38f), androidx.compose.ui.geometry.Size(w*.45f, h*.24f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.13f, h*.13f), androidx.compose.ui.geometry.Size(w*.38f, h*.38f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.49f, h*.49f), androidx.compose.ui.geometry.Size(w*.38f, h*.38f)))
            }
            "moon" -> p {
                moveTo(w*.76f, h*.13f); cubicTo(w*.43f, h*.03f, w*.14f, h*.28f, w*.18f, h*.56f)
                cubicTo(w*.22f, h*.83f, w*.49f, h*.96f, w*.76f, h*.83f)
                cubicTo(w*.57f, h*.8f, w*.43f, h*.62f, w*.43f, h*.47f)
                cubicTo(w*.43f, h*.3f, w*.56f, h*.16f, w*.76f, h*.13f); close()
            }
            "shield" -> p {
                moveTo(w*.5f, h*.08f); lineTo(w*.9f, h*.25f); lineTo(w*.83f, h*.62f)
                cubicTo(w*.79f, h*.78f, w*.62f, h*.89f, w*.5f, h*.94f)
                cubicTo(w*.38f, h*.89f, w*.21f, h*.78f, w*.17f, h*.62f); lineTo(w*.1f, h*.25f); close()
            }
            "gear" -> avatarStarPath(w, h, 12, .44f, .35f)
            "sun" -> avatarStarPath(w, h, 12, .46f, .32f)
            "cat" -> p {
                moveTo(w*.13f, h*.41f); lineTo(w*.16f, h*.13f); lineTo(w*.39f, h*.28f)
                cubicTo(w*.47f, h*.25f, w*.53f, h*.25f, w*.61f, h*.28f); lineTo(w*.84f, h*.13f); lineTo(w*.87f, h*.41f)
                cubicTo(w*.94f, h*.71f, w*.75f, h*.9f, w*.5f, h*.9f); cubicTo(w*.25f, h*.9f, w*.06f, h*.71f, w*.13f, h*.41f); close()
            }
            "robot" -> p {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(w*.16f, h*.2f, w*.84f, h*.84f, androidx.compose.ui.geometry.CornerRadius(w*.16f, w*.16f)))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(w*.04f, h*.4f, w*.17f, h*.65f, androidx.compose.ui.geometry.CornerRadius(w*.05f, w*.05f)))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(w*.83f, h*.4f, w*.96f, h*.65f, androidx.compose.ui.geometry.CornerRadius(w*.05f, w*.05f)))
                moveTo(w*.5f, h*.2f); lineTo(w*.5f, h*.08f); lineTo(w*.5f, h*.2f)
            }
            "ghost" -> p {
                moveTo(w*.15f, h*.88f); lineTo(w*.15f, h*.44f); cubicTo(w*.15f, h*.07f, w*.85f, h*.07f, w*.85f, h*.44f)
                lineTo(w*.85f, h*.88f); cubicTo(w*.76f, h*.78f, w*.68f, h*.78f, w*.59f, h*.88f)
                cubicTo(w*.5f, h*.78f, w*.42f, h*.78f, w*.33f, h*.88f); cubicTo(w*.25f, h*.79f, w*.2f, h*.8f, w*.15f, h*.88f); close()
            }
            "leaf" -> p {
                moveTo(w*.5f, h*.08f); cubicTo(w*.92f, h*.22f, w*.91f, h*.69f, w*.5f, h*.92f)
                cubicTo(w*.09f, h*.69f, w*.08f, h*.22f, w*.5f, h*.08f); close()
            }
            "rocket" -> p {
                moveTo(w*.5f, h*.06f); cubicTo(w*.78f, h*.27f, w*.78f, h*.6f, w*.7f, h*.73f)
                lineTo(w*.3f, h*.73f); cubicTo(w*.22f, h*.6f, w*.22f, h*.27f, w*.5f, h*.06f); close()
                moveTo(w*.3f, h*.56f); lineTo(w*.1f, h*.83f); lineTo(w*.37f, h*.76f); close()
                moveTo(w*.7f, h*.56f); lineTo(w*.9f, h*.83f); lineTo(w*.63f, h*.76f); close()
            }
            "butterfly" -> p {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.08f, h*.16f), androidx.compose.ui.geometry.Size(w*.43f, h*.4f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.49f, h*.16f), androidx.compose.ui.geometry.Size(w*.43f, h*.4f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.15f, h*.5f), androidx.compose.ui.geometry.Size(w*.36f, h*.34f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.49f, h*.5f), androidx.compose.ui.geometry.Size(w*.36f, h*.34f)))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(w*.46f, h*.24f, w*.54f, h*.86f, androidx.compose.ui.geometry.CornerRadius(w*.04f, w*.04f)))
            }
            "crown" -> avatarPolygon(w, h, listOf(.12f to .24f, .31f to .43f, .5f to .12f, .69f to .43f, .88f to .24f, .82f to .83f, .18f to .83f))
            "comet" -> p {
                moveTo(w*.16f, h*.78f); cubicTo(w*.24f, h*.55f, w*.39f, h*.43f, w*.58f, h*.28f)
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.35f, h*.12f), androidx.compose.ui.geometry.Size(w*.53f, h*.53f)))
            }
            "spiral" -> p {
                moveTo(w*.5f, h*.08f); cubicTo(w*.82f, h*.08f, w*.92f, h*.31f, w*.85f, h*.54f)
                cubicTo(w*.78f, h*.8f, w*.56f, h*.94f, w*.35f, h*.84f)
                cubicTo(w*.15f, h*.75f, w*.13f, h*.48f, w*.3f, h*.35f)
                cubicTo(w*.45f, h*.23f, w*.66f, h*.33f, w*.64f, h*.5f)
                cubicTo(w*.63f, h*.63f, w*.47f, h*.68f, w*.42f, h*.58f)
                cubicTo(w*.38f, h*.49f, w*.48f, h*.42f, w*.53f, h*.48f)
                cubicTo(w*.51f, h*.54f, w*.46f, h*.54f, w*.5f, h*.55f)
                cubicTo(w*.68f, h*.53f, w*.75f, h*.3f, w*.62f, h*.2f)
                cubicTo(w*.5f, h*.11f, w*.32f, h*.18f, w*.28f, h*.29f)
                cubicTo(w*.21f, h*.22f, w*.34f, h*.08f, w*.5f, h*.08f); close()
            }
            "turtle" -> p {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.22f, h*.24f), androidx.compose.ui.geometry.Size(w*.56f, h*.54f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.73f, h*.39f), androidx.compose.ui.geometry.Size(w*.23f, h*.24f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.08f, h*.27f), androidx.compose.ui.geometry.Size(w*.2f, h*.22f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.08f, h*.62f), androidx.compose.ui.geometry.Size(w*.2f, h*.22f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.67f, h*.69f), androidx.compose.ui.geometry.Size(w*.2f, h*.2f)))
            }
            "octagon" -> avatarRegularPolygon(w, h, 8, .43f)
            "spark" -> avatarStarPath(w, h, 4, .46f, .12f)
            "bear" -> p {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.09f, h*.08f), androidx.compose.ui.geometry.Size(w*.3f, h*.3f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.61f, h*.08f), androidx.compose.ui.geometry.Size(w*.3f, h*.3f)))
                addOval(androidx.compose.ui.geometry.Rect(Offset(w*.12f, h*.22f), androidx.compose.ui.geometry.Size(w*.76f, h*.7f)))
            }
            "owl" -> p {
                moveTo(w*.12f, h*.35f); lineTo(w*.27f, h*.12f); lineTo(w*.42f, h*.3f)
                cubicTo(w*.48f, h*.27f, w*.52f, h*.27f, w*.58f, h*.3f); lineTo(w*.73f, h*.12f); lineTo(w*.88f, h*.35f)
                cubicTo(w*.95f, h*.78f, w*.72f, h*.92f, w*.5f, h*.92f); cubicTo(w*.28f, h*.92f, w*.05f, h*.78f, w*.12f, h*.35f); close()
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
val agentFaces = listOf(
    "blob", "hex", "square", "circle", "triangle", "pill", "drop", "cloud", "star", "diamond",
    "heart", "flower", "moon", "shield", "gear", "sun", "cat", "robot", "ghost", "leaf",
    "rocket", "butterfly", "crown", "comet", "spiral", "turtle", "octagon", "spark", "bear", "owl",
)

@Composable
fun Divider() = HorizontalDivider(color = Cue.c.line, thickness = 1.dp)

@Composable
fun outline() = BorderStroke(1.dp, Cue.c.line)
