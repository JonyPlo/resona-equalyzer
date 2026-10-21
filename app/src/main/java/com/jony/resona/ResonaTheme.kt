package com.jony.resona

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta de Resona. Fondo azul tinta y paneles apenas mas claros; un unico color de interfaz (azul violeta).
 * El espectro (calido en los graves, frio en los agudos) se usa solo donde se habla de frecuencias:
 * la curva del ecualizador, los controles de cada banda y el icono.
 */
object ResonaPalette {
    val Ink = Color(0xFF0D0F17)
    val Slate = Color(0xFF151925)
    val Raised = Color(0xFF1E2336)
    val Line = Color(0xFF2A3047)
    val TextMain = Color(0xFFE8EBF4)
    val TextMuted = Color(0xFF8E97AE)
    val Accent = Color(0xFF8EA0FF)

    val Low = Color(0xFFFF7A59)
    val LowMid = Color(0xFFFFC857)
    val Mid = Color(0xFF5BE0B3)
    val High = Color(0xFF62B4FF)
}

private val ResonaScheme = darkColorScheme(
    primary = ResonaPalette.Accent,
    onPrimary = Color(0xFF10163A),
    primaryContainer = Color(0xFF2A3370),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = ResonaPalette.High,
    onSecondary = Color(0xFF00243D),
    tertiary = ResonaPalette.LowMid,
    onTertiary = Color(0xFF3B2A00),
    background = ResonaPalette.Ink,
    onBackground = ResonaPalette.TextMain,
    surface = ResonaPalette.Slate,
    onSurface = ResonaPalette.TextMain,
    surfaceVariant = ResonaPalette.Raised,
    onSurfaceVariant = ResonaPalette.TextMuted,
    outline = Color(0xFF4A5270),
    outlineVariant = ResonaPalette.Line,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF410002)
)

private val ResonaTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun ResonaAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ResonaScheme,
        typography = ResonaTypography,
        content = content
    )
}
