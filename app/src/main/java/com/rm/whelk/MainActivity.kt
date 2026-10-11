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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.rm.whelk.data.Lock
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import com.rm.whelk.data.Sync
import com.rm.whelk.session.Sessions
import com.rm.whelk.ui.BackupScreen
import com.rm.whelk.ui.LockScreen
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

    override fun onStart() {
        super.onStart()
        Lock.onStart()
        Sync.schedule()
    }

    override fun onStop() {
        super.onStop()
        Lock.onStop()
    }

    override fun onPause() {
        super.onPause()
        com.rm.whelk.session.Alerts.appVisible = false
        com.rm.whelk.session.SecurityKey.stop()
    }

    override fun onResume() {
        super.onResume()
        com.rm.whelk.session.Alerts.appVisible = true
        if (com.rm.whelk.session.SecurityKey.pending.value != null) com.rm.whelk.session.SecurityKey.listen(this)
        // Keeps the screen out of the recent apps view and screenshots while the lock is on.
        if (Settings.value.lockAfter >= 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        debugClip(intent)
        handle(intent)
    }

    /** A connection widget's tap opens a session for it; a session notification's tap shows it. */
    private fun handle(intent: android.content.Intent?) {
        if (intent?.action == ACTION_SHOW_SESSION) {
            val id = intent.getIntExtra(EXTRA_SESSION, -1)
            intent.action = null
            Sessions.current(id)?.let { nav.reset(Screen.Terminal(it.id)) }
            return
        }
        if (intent?.action != ACTION_CONNECT) return
        val c = Store.vault.value.connection(intent.getStringExtra(EXTRA_CONNECTION)) ?: return
        intent.action = null
        nav.reset(Screen.Terminal(Sessions.open(this, c).id))
    }

    companion object {
        const val ACTION_CONNECT = "com.rm.whelk.CONNECT"
        const val EXTRA_CONNECTION = "connection"
        const val ACTION_SHOW_SESSION = "com.rm.whelk.SHOW_SESSION"
        const val EXTRA_SESSION = "session"
    }

    /**
     * Debug builds only: puts text on the clipboard from adb, as plain text with an HTML copy the
     * way a browser does, for testing paste. am start ... --es clip "text"
     */
    private fun debugClip(intent: android.content.Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val text = intent?.getStringExtra("clip") ?: return
        val html = "<p style=\"font-family:serif\"><b>" + android.text.TextUtils.htmlEncode(text) + "</b></p>"
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newHtmlText("web", text, html))
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Fingerprint or screen lock, so a key in the phone's hardware can sign. */
    private fun unlockKey(request: com.rm.whelk.session.UnlockRequest) {
        val keys = com.rm.whelk.session.HardwareKeys
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = keys.answer(request, true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = keys.answer(request, false)
        })
        // Strong biometrics or the screen lock unlock Keystore keys; Android 10 and older can't ask for that pair.
        val allowed = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG
        else BiometricManager.Authenticators.BIOMETRIC_WEAK
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(request.title)
                .setAllowedAuthenticators(allowed or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        )
    }

    fun unlock() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Lock.unlock()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Whelk")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The splash is only for opening from the launcher; widgets, shortcuts and notifications go straight in.
        val showSplash = savedInstanceState == null && intent?.action == android.content.Intent.ACTION_MAIN
        debugClip(intent)
        handle(intent)
        setContent {
            var splash by remember { mutableStateOf(showSplash) }
            WhelkTheme {
                Surface(Modifier.fillMaxSize()) {
                    val locked by Lock.locked.collectAsState()
                    val keyJob by com.rm.whelk.session.SecurityKey.pending.collectAsState()
                    val unlockRequest by com.rm.whelk.session.HardwareKeys.pending.collectAsState()
                    LaunchedEffect(unlockRequest) { unlockRequest?.let { unlockKey(it) } }
                    // Listen for security keys only while something is waiting on one.
                    LaunchedEffect(keyJob) {
                        if (keyJob != null) com.rm.whelk.session.SecurityKey.listen(this@MainActivity)
                        else com.rm.whelk.session.SecurityKey.stop()
                    }
                    if (locked) {
                        LockScreen(onUnlock = ::unlock)
                        if (!splash) LaunchedEffect(Unit) { unlock() }
                    } else {
                        App(nav)
                    }
                    if (splash) com.rm.whelk.ui.Splash(onDone = { splash = false })
                    else LaunchedEffect(Unit) { askForNotifications() }
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
            is Screen.Files -> com.rm.whelk.ui.FilesScreen(nav, s.connectionId, s.sessionId)
            Screen.Settings -> SettingsScreen(nav)
            Screen.KnownHosts -> com.rm.whelk.ui.KnownHostsScreen(nav)
            Screen.ExtraKeys -> com.rm.whelk.ui.ExtraKeysScreen(nav)
            Screen.Backup -> BackupScreen(nav)
        }
        PromptHost()
    }
}
