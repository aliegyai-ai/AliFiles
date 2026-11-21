package com.ali.files.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark charcoal with gold accents
private val Dark = darkColorScheme(
    primary = Color(0xFFD4A94F),
    onPrimary = Color(0xFF1A1408),
    primaryContainer = Color(0xFF4A3A16),
    onPrimaryContainer = Color(0xFFF7E3AE),
    secondary = Color(0xFFE8C872),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF3A3020),
    onSecondaryContainer = Color(0xFFF7E3AE),
    tertiary = Color(0xFF7FB685),
    background = Color(0xFF0E0D0B),
    onBackground = Color(0xFFF3EDE0),
    surface = Color(0xFF0E0D0B),
    onSurface = Color(0xFFF3EDE0),
    surfaceVariant = Color(0xFF2A261F),
    onSurfaceVariant = Color(0xFFA39A88),
    surfaceContainerLowest = Color(0xFF0A0908),
    surfaceContainerLow = Color(0xFF141210),
    surfaceContainer = Color(0xFF181612),
    surfaceContainerHigh = Color(0xFF221F19),
    surfaceContainerHighest = Color(0xFF2B2720),
    outline = Color(0xFF5A5040),
    outlineVariant = Color(0xFF3A3328),
    error = Color(0xFFE0715A),
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
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
