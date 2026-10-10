package com.rm.whelk.ui

import androidx.compose.runtime.mutableStateListOf

/** Where the user is. The last screen on the stack is showing. */
sealed class Screen {
    data class Home(val tab: Tab = Tab.CONNECTIONS) : Screen()
    data class Terminal(val sessionId: Int) : Screen()
    data class EditConnection(val id: String?) : Screen()
    data class EditIdentity(val id: String?) : Screen()
    data class EditSnippet(val id: String?) : Screen()
    data class EditForward(val id: String?, val connectionId: String? = null) : Screen()
    data class EditGroup(val id: String?) : Screen()
    data class Files(val connectionId: String, val sessionId: Int? = null) : Screen()
    object Settings : Screen()
    object Backup : Screen()
}

enum class Tab(val label: String) {
    CONNECTIONS("Connections"),
    IDENTITIES("Identities"),
    SNIPPETS("Snippets"),
    FORWARDS("Port forwards"),
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home())
    val current: Screen get() = stack.last()

    fun go(screen: Screen) {
        stack += screen
    }

    /** Replaces the top screen. */
    fun swap(screen: Screen) {
        stack[stack.size - 1] = screen
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    /** Goes back to the home screen and then to [screen]. */
    fun reset(screen: Screen? = null) {
        val home = stack.first()
        stack.clear()
        stack += home
        if (screen != null) stack += screen
    }
}
