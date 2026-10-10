package com.rm.whelk.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.rm.whelk.data.AppTheme
import com.rm.whelk.data.Settings

private val dark = darkColorScheme(
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

private val light = lightColorScheme(
    primary = Color(0xFF7A5A2E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6E3C8),
    onPrimaryContainer = Color(0xFF2A1E10),
    secondary = Color(0xFF2F6576),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEAF3),
    onSecondaryContainer = Color(0xFF0E2A33),
    background = Color(0xFFFBF8F4),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFBF8F4),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFEDE6DD),
    onSurfaceVariant = Color(0xFF4D4740),
    surfaceContainer = Color(0xFFF3EEE8),
    surfaceContainerHigh = Color(0xFFEDE8E2),
    surfaceContainerHighest = Color(0xFFE7E2DC),
    outline = Color(0xFF7F776E),
    error = Color(0xFFBA1A1A),
)

@Composable
fun WhelkTheme(content: @Composable () -> Unit) {
    val prefs by Settings.flow.collectAsState()
    val darkMode = when (prefs.appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
    }
    val context = LocalContext.current
    val colours = when {
        prefs.dynamicColour && Build.VERSION.SDK_INT >= 31 -> if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkMode -> dark
        else -> light
    }
    MaterialTheme(colorScheme = colours, content = content)
}
