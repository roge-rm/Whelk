package com.rm.whelk.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rm.whelk.MainActivity
import com.rm.whelk.R
import com.rm.whelk.data.Store
import com.rm.whelk.session.SessionState
import com.rm.whelk.session.Sessions
import com.rm.whelk.ui.WhelkTheme
import com.rm.whelk.ui.combinedClickableCompat

/** Which connection or forward each widget is for, by widget id. */
object WidgetPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun get(context: Context, widgetId: Int): String? = prefs(context).getString("w$widgetId", null)
    fun set(context: Context, widgetId: Int, itemId: String) = prefs(context).edit().putString("w$widgetId", itemId).apply()
    fun remove(context: Context, widgetId: Int) = prefs(context).edit().remove("w$widgetId").apply()
}

/** Opens a connection in a new session. */
class ConnectionWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) draw(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetPrefs.remove(context, id)
    }

    companion object {
        fun draw(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val vault = Store.vault.value
            val c = vault.connection(WidgetPrefs.get(context, widgetId))
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_label, c?.title(vault) ?: "Gone")
            val open = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CONNECT)
                .putExtra(MainActivity.EXTRA_CONNECTION, c?.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, widgetId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            manager.updateAppWidget(widgetId, views)
        }
    }
}

/** Starts or stops a port forward, connecting in the background if needed. */
class ForwardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) draw(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetPrefs.remove(context, id)
    }

    companion object {
        fun draw(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val f = Store.vault.value.forward(WidgetPrefs.get(context, widgetId))
            val running = f != null && Sessions.list.value.any { it.forwards.value.containsKey(f.id) }
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_label, f?.title() ?: "Gone")
            views.setInt(R.id.widget_root, "setBackgroundResource", if (running) R.drawable.widget_background_on else R.drawable.widget_background)
            val toggle = Intent(context, ForwardToggle::class.java).putExtra(ForwardToggle.EXTRA_FORWARD, f?.id)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getBroadcast(context, widgetId, toggle, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            manager.updateAppWidget(widgetId, views)
        }

        /** Redraws every forward widget, after a forward starts or stops. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (id in manager.getAppWidgetIds(ComponentName(context, ForwardWidget::class.java))) draw(context, manager, id)
        }
    }
}

/** A tap on a forward widget. */
class ForwardToggle : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val f = Store.vault.value.forward(intent.getStringExtra(EXTRA_FORWARD)) ?: return
        val conn = Store.vault.value.connection(f.connectionId) ?: return
        val session = Sessions.list.value.firstOrNull { it.connection?.id == conn.id && it.state.value != SessionState.CLOSED }
        when {
            session != null && session.forwards.value.containsKey(f.id) -> session.stopForward(f.id)
            session?.ssh != null -> session.startForward(f)
            session != null -> session.autoForwards += f.id
            else -> Sessions.open(context, conn, setOf(f.id))
        }
    }

    companion object {
        const val EXTRA_FORWARD = "forward"
    }
}

/** Picks the connection or forward when a widget is added. */
class WidgetSetup : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val manager = AppWidgetManager.getInstance(this)
        // Only for one of our own widgets being added, as any app can start this screen.
        val provider = manager.getAppWidgetInfo(widgetId)?.provider
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || provider?.packageName != packageName) {
            finish()
            return
        }
        // With the app lock on, the list waits for the fingerprint or screen lock, and stays out of screenshots.
        if (com.rm.whelk.data.Settings.value.lockAfter >= 0) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) = show(widgetId, provider)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish()
                })
            prompt.authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Whelk")
                    .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build(),
            )
        } else {
            show(widgetId, provider)
        }
    }

    private fun show(widgetId: Int, provider: ComponentName) {
        val manager = AppWidgetManager.getInstance(this)
        val forForward = provider.className == ForwardWidget::class.java.name
        val vault = Store.vault.value
        val items: List<Pair<String, String>> = if (forForward) {
            vault.liveForwards().map { it.id to it.title() }
        } else {
            vault.liveConnections().map { it.id to it.title(vault) }
        }.sortedBy { it.second.lowercase() }

        setContent {
            WhelkTheme {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn {
                        item {
                            Text(
                                if (forForward) "Port forward for the widget" else "Connection for the widget",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        if (items.isEmpty()) item { Text(if (forForward) "No port forwards yet." else "No connections yet.", Modifier.padding(16.dp)) }
                        items(items) { (id, title) ->
                            ListItem(
                                headlineContent = { Text(title) },
                                modifier = Modifier.combinedClickableCompat(onClick = {
                                    WidgetPrefs.set(this@WidgetSetup, widgetId, id)
                                    if (forForward) ForwardWidget.draw(this@WidgetSetup, manager, widgetId)
                                    else ConnectionWidget.draw(this@WidgetSetup, manager, widgetId)
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                    finish()
                                }),
                            )
                        }
                    }
                }
            }
        }
    }
}
