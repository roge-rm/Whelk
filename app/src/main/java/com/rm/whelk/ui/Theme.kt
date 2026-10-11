package com.rm.whelk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.whelk.data.AppTheme
import com.rm.whelk.data.Settings

/** The icon's colours. */
object Whelk {
    val slate = Color(0xFF1B2A2C)
    val cream = Color(0xFFEFE6D2)
    val lime = Color(0xFFC6F24E)
}

private val dark = darkColorScheme(
    primary = Whelk.lime,
    onPrimary = Whelk.slate,
    primaryContainer = Color(0xFF3A4A12),
    onPrimaryContainer = Color(0xFFDDF7A0),
    secondary = Color(0xFF8FB8B4),
    onSecondary = Color(0xFF0F2224),
    secondaryContainer = Color(0xFF2C4446),
    onSecondaryContainer = Color(0xFFD3E8E5),
    tertiary = Whelk.cream,
    onTertiary = Whelk.slate,
    background = Color(0xFF142022),
    onBackground = Whelk.cream,
    surface = Color(0xFF142022),
    onSurface = Whelk.cream,
    surfaceVariant = Color(0xFF223336),
    onSurfaceVariant = Color(0xFFB9B4A6),
    surfaceContainerLowest = Color(0xFF0F191A),
    surfaceContainerLow = Color(0xFF182628),
    surfaceContainer = Whelk.slate,
    surfaceContainerHigh = Color(0xFF223336),
    surfaceContainerHighest = Color(0xFF2A3D40),
    inverseSurface = Whelk.cream,
    inverseOnSurface = Whelk.slate,
    inversePrimary = Color(0xFF4E6A00),
    outline = Color(0xFF4A5E60),
    outlineVariant = Color(0xFF2E4245),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val light = lightColorScheme(
    primary = Color(0xFF4E6A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Whelk.lime,
    onPrimaryContainer = Whelk.slate,
    secondary = Color(0xFF3D6462),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E5E1),
    onSecondaryContainer = Color(0xFF0F2224),
    tertiary = Whelk.slate,
    onTertiary = Whelk.cream,
    background = Color(0xFFF4EEDF),
    onBackground = Whelk.slate,
    surface = Color(0xFFF4EEDF),
    onSurface = Whelk.slate,
    surfaceVariant = Color(0xFFE6DFCC),
    onSurfaceVariant = Color(0xFF4A5654),
    surfaceContainerLowest = Color(0xFFFFFBF2),
    surfaceContainerLow = Color(0xFFF0E9D9),
    surfaceContainer = Color(0xFFEAE3D1),
    surfaceContainerHigh = Color(0xFFE4DCC9),
    surfaceContainerHighest = Color(0xFFDDD5C1),
    inverseSurface = Whelk.slate,
    inverseOnSurface = Whelk.cream,
    inversePrimary = Whelk.lime,
    outline = Color(0xFF77817D),
    outlineVariant = Color(0xFFCBC4B2),
    error = Color(0xFFBA1A1A),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/** Fira Mono, the default terminal font, for titles and labels. */
@Composable
fun monoFamily(): FontFamily {
    val assets = LocalContext.current.assets
    return remember { FontFamily(Font("fonts/fira-mono.otf", assets)) }
}

@Composable
fun WhelkTheme(content: @Composable () -> Unit) {
    val prefs by Settings.flow.collectAsState()
    val darkMode = when (prefs.appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
    }
    val mono = monoFamily()
    val typography = remember(mono) {
        val t = Typography()
        t.copy(
            headlineSmall = t.headlineSmall.copy(fontFamily = mono, fontSize = 22.sp),
            titleLarge = t.titleLarge.copy(fontFamily = mono, fontSize = 21.sp),
            titleMedium = t.titleMedium.copy(fontFamily = mono),
            titleSmall = t.titleSmall.copy(fontFamily = mono),
            labelLarge = t.labelLarge.copy(fontFamily = mono),
        )
    }
    MaterialTheme(colorScheme = if (darkMode) dark else light, shapes = shapes, typography = typography, content = content)
}
