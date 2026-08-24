package io.github.finall1008.xiaoaimcp.ui

import android.database.ContentObserver
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles

@Composable
internal fun BridgeTheme(
    activity: ComponentActivity,
    content: @Composable () -> Unit,
) {
    val darkMode = isSystemInDarkTheme()
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    val useBoldText = rememberSystemBoldText(activity)
    val textStyles = remember(useBoldText) {
        defaultTextStyles().let { styles ->
            if (useBoldText) styles.withFontWeight(FontWeight.Bold) else styles
        }
    }
    DisposableEffect(darkMode) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = Color.TRANSPARENT
        onDispose { }
    }
    MiuixTheme(controller = controller, textStyles = textStyles) {
        Box(
            modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}

@Composable
private fun rememberSystemBoldText(activity: ComponentActivity): Boolean {
    val resolver = activity.contentResolver
    var miuiFontWeightScale by remember {
        mutableIntStateOf(
            Settings.System.getInt(resolver, MIUI_FONT_WEIGHT_SCALE, DEFAULT_MIUI_FONT_WEIGHT),
        )
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                miuiFontWeightScale = Settings.System.getInt(
                    resolver,
                    MIUI_FONT_WEIGHT_SCALE,
                    DEFAULT_MIUI_FONT_WEIGHT,
                )
            }
        }
        resolver.registerContentObserver(
            Settings.System.getUriFor(MIUI_FONT_WEIGHT_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val standardAdjustment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        activity.resources.configuration.fontWeightAdjustment
    } else {
        0
    }
    return shouldUseBoldText(miuiFontWeightScale, standardAdjustment)
}

internal fun shouldUseBoldText(miuiFontWeightScale: Int, standardAdjustment: Int): Boolean {
    return miuiFontWeightScale >= BOLD_MIUI_FONT_WEIGHT || standardAdjustment >= 300
}

private fun TextStyles.withFontWeight(weight: FontWeight): TextStyles {
    return copy(
        main = main.copy(fontWeight = weight),
        paragraph = paragraph.copy(fontWeight = weight),
        body1 = body1.copy(fontWeight = weight),
        body2 = body2.copy(fontWeight = weight),
        button = button.copy(fontWeight = weight),
        footnote1 = footnote1.copy(fontWeight = weight),
        footnote2 = footnote2.copy(fontWeight = weight),
        headline1 = headline1.copy(fontWeight = weight),
        headline2 = headline2.copy(fontWeight = weight),
        subtitle = subtitle.copy(fontWeight = weight),
        title1 = title1.copy(fontWeight = weight),
        title2 = title2.copy(fontWeight = weight),
        title3 = title3.copy(fontWeight = weight),
        title4 = title4.copy(fontWeight = weight),
    )
}

private const val MIUI_FONT_WEIGHT_SCALE = "key_miui_font_weight_scale"
private const val DEFAULT_MIUI_FONT_WEIGHT = 50
private const val BOLD_MIUI_FONT_WEIGHT = 100

@Composable
internal fun BackButton(onClick: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
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
