package com.rm.whelk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.rm.whelk.ui.EditConnectionScreen
import com.rm.whelk.ui.EditForwardScreen
import com.rm.whelk.ui.EditGroupScreen
import com.rm.whelk.ui.EditIdentityScreen
import com.rm.whelk.ui.EditSnippetScreen
import com.rm.whelk.ui.HomeScreen
import com.rm.whelk.ui.Nav
import com.rm.whelk.ui.PromptHost
import com.rm.whelk.ui.Screen
import com.rm.whelk.ui.SettingsScreen
import com.rm.whelk.ui.TerminalScreen
import com.rm.whelk.ui.WhelkTheme

class MainActivity : FragmentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            WhelkTheme {
                Surface(Modifier.fillMaxSize()) {
                    App(nav)
                }
            }
        }
    }
}

@Composable
fun App(nav: Nav) {
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    Box(Modifier.fillMaxSize()) {
        when (val s = nav.current) {
            is Screen.Home -> HomeScreen(nav, s.tab)
            is Screen.Terminal -> TerminalScreen(nav, s.sessionId)
            is Screen.EditConnection -> EditConnectionScreen(nav, s.id)
            is Screen.EditIdentity -> EditIdentityScreen(nav, s.id)
            is Screen.EditSnippet -> EditSnippetScreen(nav, s.id)
            is Screen.EditForward -> EditForwardScreen(nav, s.id, s.connectionId)
            is Screen.EditGroup -> EditGroupScreen(nav, s.id)
            Screen.Settings -> SettingsScreen(nav)
            Screen.Backup -> SettingsScreen(nav)
        }
        PromptHost()
    }
}
