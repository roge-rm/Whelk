package com.rm.whelk.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** A screen with a back arrow, a title, an optional delete and a Save button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    title: String,
    onBack: () -> Unit,
    onSave: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            WhelkTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    if (onSave != null) TextButton(onClick = onSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    monospace: Boolean = false,
    error: String? = null,
    onDone: (() -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        textStyle = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                secret -> KeyboardType.Password
                else -> KeyboardType.Text
            },
            autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = onDone?.let { { it() } }),
        trailingIcon = if (secret) {
            { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } }
        } else null,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A field that opens a menu of choices. */
@Composable
fun <T> Choice(label: String, value: T, options: List<T>, name: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = name(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Covers the field so a tap anywhere opens the menu.
        Box(Modifier.matchParentSize().combinedClickableCompat(onClick = { open = true }))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(text = { Text(name(o)) }, onClick = {
                    open = false
                    onChange(o)
                })
            }
        }
    }
}

@Composable
fun Section(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** The title lowercase in mono with a blinking block cursor after it. */
@Composable
fun CursorTitle(text: String, style: TextStyle = MaterialTheme.typography.titleLarge) {
    val blink = rememberInfiniteTransition(label = "cursor")
    val on by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(keyframes { durationMillis = 1060; 1f at 529; 0f at 530 }),
        label = "cursor",
    )
    val colour = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text.lowercase(), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val h = with(LocalDensity.current) { style.fontSize.toDp() }
        Box(Modifier.padding(start = 3.dp).size(width = h * 0.55f, height = h * 1.05f).graphicsLayer { alpha = on }.background(colour))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhelkTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(title = { CursorTitle(title) }, navigationIcon = navigationIcon, actions = actions)
}

/**
 * A password field that never shows a saved one. With one saved and nothing typed it says so, and
 * Remove clears it; typing replaces it, and only what's typed can be shown.
 */
@Composable
fun SecretField(label: String, value: String, onChange: (String) -> Unit, saved: Boolean, onRemove: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    val holdingSaved = saved && value.isEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (holdingSaved) "$label, saved" else label) },
        placeholder = if (holdingSaved) { { Text("Type to change") } } else null,
        singleLine = true,
        visualTransformation = if (show && !holdingSaved) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            when {
                holdingSaved -> TextButton(onClick = onRemove) { Text("Remove") }
                value.isNotEmpty() -> TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
