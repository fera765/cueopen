package dev.fluwy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.fluwy.app.data.ThemeMode

/** Paleta própria do Fluwy: papel quente + tinta preta + laranja "Pip". Layout inspirado no fluxo do Fluwy. */
@Immutable
data class FluwyColors(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val ink: Color, val ink2: Color, val ink3: Color,
    val accent: Color, val accentSoft: Color, val ok: Color, val warn: Color, val err: Color,
    val bubble: Color, val dark: Boolean,
)

val LightFluwy = FluwyColors(
    bg = Color(0xFFFBFAF7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF2F0EB), line = Color(0xFFE6E2DA),
    ink = Color(0xFF151515), ink2 = Color(0xFF5E5B55), ink3 = Color(0xFF9C978E),
    accent = Color(0xFFFF6B3D), accentSoft = Color(0xFFFFE6DC), ok = Color(0xFF1F9D55), warn = Color(0xFFE0A100), err = Color(0xFFD93A3A),
    bubble = Color(0xFFF2F0EB), dark = false,
)
val DarkFluwy = FluwyColors(
    bg = Color(0xFF111111), surface = Color(0xFF1A1A1A), surface2 = Color(0xFF242322), line = Color(0xFF2F2E2C),
    ink = Color(0xFFF3F1EC), ink2 = Color(0xFFB4B0A8), ink3 = Color(0xFF7D7973),
    accent = Color(0xFFFF7A50), accentSoft = Color(0xFF3A2219), ok = Color(0xFF3CC274), warn = Color(0xFFF2BE2E), err = Color(0xFFFF6464),
    bubble = Color(0xFF242322), dark = true,
)

val LocalFluwy = staticCompositionLocalOf { LightFluwy }
object Fluwy { val c: FluwyColors @Composable get() = LocalFluwy.current }

@Composable
fun FluwyTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val c = if (dark) DarkFluwy else LightFluwy
    val scheme = if (dark) darkColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.ink, onBackground = c.ink, surfaceVariant = c.surface2, outline = c.line)
                 else lightColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.ink, onBackground = c.ink, surfaceVariant = c.surface2, outline = c.line)
    val base = TextStyle(fontFamily = FontFamily.SansSerif)
    val typo = Typography(
        headlineLarge = base.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
        headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink2),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalFluwy provides c, androidx.compose.material3.LocalContentColor provides c.ink) {
        MaterialTheme(colorScheme = scheme, typography = typo) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.ink, content = content)
        }
    }
}
