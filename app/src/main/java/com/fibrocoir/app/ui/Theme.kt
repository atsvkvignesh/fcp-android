package com.fibrocoir.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fibrocoir.app.R

/** The Fibro Coir palette: dark husk, warm fibre gold, cream text. */
object Fcp {
    val husk = Color(0xFF1C1408)
    val husk2 = Color(0xFF2A1F10)
    val husk3 = Color(0xFF352716)
    val husk4 = Color(0xFF433220)
    val fibre = Color(0xFFE2A93B)
    val fibreHi = Color(0xFFFFD25E)
    val cream = Color(0xFFF6EEDC)
    val muted = Color(0xFFB6A184)
    val faint = Color(0xFF8A7860)
    val edge = Color(0x3DE2A93B)
    val bad = Color(0xFFE8734A)
    val good = Color(0xFF8FBF6A)
    val info = Color(0xFF7FB6D9)

    /** Series colours for graphs (Belt, Block, then one per yard). */
    val series = listOf(
        Color(0xFFF2C255), Color(0xFF7FB6D9), Color(0xFF8FBF6A), Color(0xFFD98CC0),
        Color(0xFFE8734A), Color(0xFFB59CE6), Color(0xFF6FD3C1), Color(0xFFE0A07A),
    )
}

private fun fraunces(w: Int) = Font(
    R.font.fraunces, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", 72f))
)

private fun archivo(w: Int) = Font(
    R.font.archivo, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)

val Serif = FontFamily(fraunces(500), fraunces(600), fraunces(700))
val Sans = FontFamily(archivo(400), archivo(500), archivo(600), archivo(700))

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 34.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 14.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 11.5.sp, lineHeight = 14.sp),
)

private val colors = darkColorScheme(
    primary = Fcp.fibre,
    onPrimary = Fcp.husk,
    primaryContainer = Fcp.husk4,
    onPrimaryContainer = Fcp.fibreHi,
    secondary = Fcp.muted,
    onSecondary = Fcp.husk,
    secondaryContainer = Fcp.husk3,
    onSecondaryContainer = Fcp.cream,
    background = Fcp.husk,
    onBackground = Fcp.cream,
    surface = Fcp.husk2,
    onSurface = Fcp.cream,
    surfaceVariant = Fcp.husk3,
    onSurfaceVariant = Fcp.muted,
    surfaceContainerLowest = Fcp.husk,
    surfaceContainerLow = Fcp.husk2,
    surfaceContainer = Fcp.husk2,
    surfaceContainerHigh = Fcp.husk3,
    surfaceContainerHighest = Fcp.husk4,
    outline = Fcp.edge,
    outlineVariant = Color(0x22E2A93B),
    error = Fcp.bad,
    onError = Fcp.husk,
    scrim = Color(0xCC000000),
)

@Composable
fun FcpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
