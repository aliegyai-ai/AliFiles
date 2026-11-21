package com.ali.files.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F5E57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEDE5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF8A6D1F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E3AE),
    onSecondaryContainer = Color(0xFF2A1F00),
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFE2E9E7),
    onSurfaceVariant = Color(0xFF3F4947),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6CB),
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF0F5E57),
    onPrimaryContainer = Color(0xFFBDEDE5),
    secondary = Color(0xFFE8C872),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5B4400),
    onSecondaryContainer = Color(0xFFF7E3AE),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceVariant = Color(0xFF26302E),
    onSurfaceVariant = Color(0xFFBEC9C6),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
