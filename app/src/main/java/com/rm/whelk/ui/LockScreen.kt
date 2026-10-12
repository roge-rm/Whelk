package com.rm.whelk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rm.whelk.R

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The icon's foreground on its slate. The launcher icon itself is an adaptive icon, which Compose can't draw.
        Box(Modifier.size(96.dp).background(Whelk.slate, MaterialTheme.shapes.large), contentAlignment = Alignment.Center) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, Modifier.size(144.dp))
        }
        Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CursorTitle("whelk is locked")
        }
        Button(shape = MaterialTheme.shapes.small, onClick = onUnlock) { Text("Unlock") }
    }
}
