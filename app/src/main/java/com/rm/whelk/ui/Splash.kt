package com.rm.whelk.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rm.whelk.BuildConfig
import com.rm.whelk.R
import kotlinx.coroutines.delay

/** The icon with the name and version under it, on slate. Fades in, holds, then grows a little
 * as it and the slate fade into the screen underneath. */
@Composable
fun Splash(onDone: () -> Unit) {
    val content = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        content.animateTo(1f, tween(200))
        delay(300)
        exit.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - exit.value }.background(Whelk.slate),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            Modifier.size(240.dp).graphicsLayer {
                alpha = content.value
                scaleX = 1f + 0.2f * exit.value
                scaleY = scaleX
            },
        )
        Column(
            Modifier.offset(y = 120.dp).graphicsLayer {
                alpha = content.value
                translationY = 24.dp.toPx() * exit.value
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Whelk.lime)) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Whelk.cream,
                ) {
                    CursorTitle("whelk")
                }
            }
            Text(
                BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelLarge,
                color = Whelk.cream.copy(alpha = 0.6f),
            )
        }
    }
}
