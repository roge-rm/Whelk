package com.rm.whelk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colours = darkColorScheme(
    primary = Color(0xFFE8C9A0),
    onPrimary = Color(0xFF2A1E10),
    primaryContainer = Color(0xFF4A3A26),
    onPrimaryContainer = Color(0xFFF6E3C8),
    secondary = Color(0xFF8EC5D6),
    onSecondary = Color(0xFF0E2A33),
    secondaryContainer = Color(0xFF1F3B45),
    onSecondaryContainer = Color(0xFFCDEAF3),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE3E3E6),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE3E3E6),
    surfaceVariant = Color(0xFF23272C),
    onSurfaceVariant = Color(0xFFB9BEC6),
    surfaceContainer = Color(0xFF1A1D21),
    surfaceContainerHigh = Color(0xFF20242A),
    surfaceContainerHighest = Color(0xFF282C32),
    outline = Color(0xFF5A6068),
    error = Color(0xFFFFB4AB),
)

@Composable
fun WhelkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colours, content = content)
}
