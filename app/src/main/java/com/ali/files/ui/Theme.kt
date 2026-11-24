package com.ali.files.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ali.files.R
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding

// Fonts: Readex Pro for everything you read, Reem Kufi (Kufic) for titles and the app's name
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Readex = FontFamily(
    variable(R.font.readex_pro, 300), variable(R.font.readex_pro, 400), variable(R.font.readex_pro, 500),
    variable(R.font.readex_pro, 600), variable(R.font.readex_pro, 700),
)
val Kufi = FontFamily(variable(R.font.reem_kufi, 500), variable(R.font.reem_kufi, 600), variable(R.font.reem_kufi, 700))

/** the gold used across the app, as a gradient (buttons, the selected tab, progress) */
val GoldBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFF2D58E), Color(0xFFC9963C)))
/** the screen background: warm light from the top corner */
fun androidx.compose.ui.Modifier.appBackground() = this.drawBehind {
    drawRect(Color(0xFF13110D))
    drawRect(
        androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(Color(0xFF2A2216), Color(0x0013110D)),
            center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, 0f), radius = size.height * 0.65f,
        ),
    )
}

private val base = Typography()
private fun TextStyle.r() = copy(fontFamily = Readex)
private fun TextStyle.k() = copy(fontFamily = Kufi, fontWeight = FontWeight.Bold)
private val AppTypography = Typography(
    displayLarge = base.displayLarge.k(), displayMedium = base.displayMedium.k(), displaySmall = base.displaySmall.k(),
    headlineLarge = base.headlineLarge.k(), headlineMedium = base.headlineMedium.k(), headlineSmall = base.headlineSmall.k(),
    titleLarge = base.titleLarge.r().copy(fontWeight = FontWeight.SemiBold), titleMedium = base.titleMedium.r().copy(fontWeight = FontWeight.SemiBold), titleSmall = base.titleSmall.r(),
    bodyLarge = base.bodyLarge.r(), bodyMedium = base.bodyMedium.r(), bodySmall = base.bodySmall.r(),
    labelLarge = base.labelLarge.r().copy(fontWeight = FontWeight.SemiBold), labelMedium = base.labelMedium.r(), labelSmall = base.labelSmall.r(),
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp),
)

// Dark charcoal with gold accents
private val Dark = darkColorScheme(
    primary = Color(0xFFD4A94F),
    onPrimary = Color(0xFF14100A),
    primaryContainer = Color(0xFF4A3A16),
    onPrimaryContainer = Color(0xFFF7E3AE),
    secondary = Color(0xFFE8C872),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF3A3020),
    onSecondaryContainer = Color(0xFFF7E3AE),
    tertiary = Color(0xFF7FB685),
    background = Color(0xFF13110D),
    onBackground = Color(0xFFF3EBDD),
    surface = Color(0xFF13110D),
    onSurface = Color(0xFFF3EBDD),
    surfaceVariant = Color(0xFF2A2419),
    onSurfaceVariant = Color(0xFFA89B86),
    surfaceContainerLowest = Color(0xFF0E0C09),
    surfaceContainerLow = Color(0xFF181510),
    surfaceContainer = Color(0xFF1E1A13),
    surfaceContainerHigh = Color(0xFF262017),
    surfaceContainerHighest = Color(0xFF2F281C),
    outline = Color(0xFF5A4C34),
    outlineVariant = Color(0xFF3A3123),
    error = Color(0xFFE88A70),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A6414),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E3AE),
    onPrimaryContainer = Color(0xFF2A1F00),
    secondary = Color(0xFF8A6D1F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E4C2),
    onSecondaryContainer = Color(0xFF2A1F00),
    tertiary = Color(0xFF3F7A46),
    background = Color(0xFFF8F4EC),
    onBackground = Color(0xFF221D14),
    surface = Color(0xFFF8F4EC),
    onSurface = Color(0xFF221D14),
    surfaceVariant = Color(0xFFEDE5D5),
    onSurfaceVariant = Color(0xFF6E6452),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF9F3),
    surfaceContainer = Color(0xFFF3EDE1),
    surfaceContainerHigh = Color(0xFFEDE6D8),
    surfaceContainerHighest = Color(0xFFE6DECD),
    outline = Color(0xFF9A8E78),
    outlineVariant = Color(0xFFD8CDB8),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = AppTypography, shapes = AppShapes, content = content)
}

/**
 * For the content of a bottom sheet: scrolls when it's taller than the screen and keeps clear of the
 * phone's navigation bar — so the last items of a list are never cut off.
 */
@androidx.compose.runtime.Composable
fun androidx.compose.ui.Modifier.sheetBody(): androidx.compose.ui.Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
        .then(androidx.compose.ui.Modifier.navigationBarsPadding())

/**
 * A sheet that a stray tap or swipe can't close — only its own buttons, through [close], which slides
 * it away properly before it's removed (removing a sheet that's still open left the page under it frozen).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class GuardedSheet internal constructor(
    val state: androidx.compose.material3.SheetState,
    private val allow: androidx.compose.runtime.MutableState<Boolean>,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private var closing = false
    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        allow.value = true
        scope.launch {
            runCatching { state.hide() }
            then()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun rememberGuardedSheet(): GuardedSheet {
    val allow = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val state = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden || allow.value },
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return androidx.compose.runtime.remember(state) { GuardedSheet(state, allow, scope) }
}
