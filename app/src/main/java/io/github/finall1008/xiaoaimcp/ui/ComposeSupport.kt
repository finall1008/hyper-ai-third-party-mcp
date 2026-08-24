package io.github.finall1008.xiaoaimcp.ui

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
internal fun BridgeTheme(
    activity: ComponentActivity,
    content: @Composable () -> Unit,
) {
    val darkMode = isSystemInDarkTheme()
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    DisposableEffect(darkMode) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        onDispose { }
    }
    MiuixTheme(controller = controller, content = content)
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    TextButton(
        text = "‹",
        onClick = onClick,
        minWidth = 44.dp,
        modifier = Modifier.padding(start = 4.dp),
        textStyle = MiuixTheme.textStyles.title4,
    )
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
internal fun MessageDialog(
    message: String?,
    title: String = "提示",
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = message != null,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = message.orEmpty(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            style = MiuixTheme.textStyles.body2,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("确定")
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            style = MiuixTheme.textStyles.body2,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "取消", onClick = onDismiss)
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(confirmText)
            }
        }
    }
}

internal fun safeMessage(error: Throwable): String {
    return error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName
}
