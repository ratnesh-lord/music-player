package app.unpaused.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unpaused.R
import app.unpaused.data.settings.ThemeMode

/*
 * Design tokens (mirrored in shared/design-tokens.json and web/src/styles). Monochrome surfaces, one red
 * accent, a dot-matrix display face for headings and numbers, a monospace face for technical facts.
 * Inspired by the general dot-matrix/monochrome aesthetic; no third-party brand assets are used.
 */
object Tokens {
    val Red = Color(0xFFE5322D)
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Grey900 = Color(0xFF0E0E0E)
    val Grey850 = Color(0xFF151515)
    val Grey800 = Color(0xFF1C1C1C)
    val Grey700 = Color(0xFF2A2A2A)
    val Grey500 = Color(0xFF6B6B6B)
    val Grey400 = Color(0xFF9A9A9A)
    val Paper = Color(0xFFF3F3F1)
    val PaperHigh = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0A0A0A)
}

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)
val GeistMono = FontFamily(
    Font(R.font.geistmono_regular, FontWeight.Normal),
    Font(R.font.geistmono_medium, FontWeight.Medium),
)
val Doto = FontFamily(Font(R.font.doto_bold, FontWeight.Bold))

private val DarkScheme = darkColorScheme(
    primary = Tokens.White,
    onPrimary = Tokens.Black,
    primaryContainer = Tokens.Grey800,
    onPrimaryContainer = Tokens.White,
    secondary = Tokens.Red,
    onSecondary = Tokens.White,
    tertiary = Tokens.Red,
    background = Tokens.Black,
    onBackground = Tokens.White,
    surface = Tokens.Black,
    onSurface = Tokens.White,
    surfaceVariant = Tokens.Grey850,
    onSurfaceVariant = Tokens.Grey400,
    surfaceContainerLowest = Tokens.Black,
    surfaceContainerLow = Tokens.Grey900,
    surfaceContainer = Tokens.Grey900,
    surfaceContainerHigh = Tokens.Grey850,
    surfaceContainerHighest = Tokens.Grey800,
    outline = Tokens.Grey700,
    outlineVariant = Tokens.Grey800,
    error = Tokens.Red,
    onError = Tokens.White,
)

private val LightScheme = lightColorScheme(
    primary = Tokens.Ink,
    onPrimary = Tokens.White,
    primaryContainer = Color(0xFFE4E4E1),
    onPrimaryContainer = Tokens.Ink,
    secondary = Tokens.Red,
    onSecondary = Tokens.White,
    tertiary = Tokens.Red,
    background = Tokens.Paper,
    onBackground = Tokens.Ink,
    surface = Tokens.Paper,
    onSurface = Tokens.Ink,
    surfaceVariant = Color(0xFFE9E9E6),
    onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceContainerLowest = Tokens.PaperHigh,
    surfaceContainerLow = Color(0xFFF8F8F6),
    surfaceContainer = Tokens.PaperHigh,
    surfaceContainerHigh = Color(0xFFEDEDEA),
    surfaceContainerHighest = Color(0xFFE4E4E1),
    outline = Color(0xFFCFCFCB),
    outlineVariant = Color(0xFFE0E0DC),
    error = Tokens.Red,
    onError = Tokens.White,
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = 1.sp),
    displayMedium = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = 1.sp),
    displaySmall = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 1.sp),
    headlineLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Immutable
data class Extra(val accent: Color, val dim: Color, val dotOff: Color)

val LocalExtra = staticCompositionLocalOf { Extra(Tokens.Red, Tokens.Grey400, Tokens.Grey700) }

@Composable
fun UnpausedTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val extra = if (dark) Extra(Tokens.Red, Tokens.Grey400, Tokens.Grey700) else Extra(Tokens.Red, Color(0xFF5E5E5E), Color(0xFFCFCFCB))
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
