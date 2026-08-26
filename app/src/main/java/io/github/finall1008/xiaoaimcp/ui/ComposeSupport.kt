package io.github.finall1008.xiaoaimcp.ui

import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.window.WindowDialog

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
    MiuixTheme(controller = controller, textStyles = textStyles) {
        val surfaceColor = MiuixTheme.colorScheme.surface
        DisposableEffect(darkMode, surfaceColor) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                ) { darkMode },
                navigationBarStyle = SystemBarStyle.auto(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                ) { darkMode },
            )
            activity.window.setBackgroundDrawable(ColorDrawable(surfaceColor.toArgb()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                activity.window.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = Color.TRANSPARENT
            onDispose { }
        }
        Box(
            modifier = Modifier.fillMaxSize().background(surfaceColor),
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
internal fun BridgePageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            val surfaceColor = MiuixTheme.colorScheme.surface
            val shadowColor = MiuixTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val progress = max(
                                scrollBehavior.state.collapsedFraction,
                                scrollBehavior.state.overlappedFraction,
                            ).coerceIn(0f, 1f)
                            shadowElevation = 10.dp.toPx() * progress
                            ambientShadowColor = shadowColor.copy(alpha = 0.12f)
                            spotShadowColor = shadowColor.copy(alpha = 0.16f)
                        }
                        .drawWithContent {
                            val progress = max(
                                scrollBehavior.state.collapsedFraction,
                                scrollBehavior.state.overlappedFraction,
                            ).coerceIn(0f, 1f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to surfaceColor,
                                        0.72f to surfaceColor.copy(
                                            alpha = 1f - 0.08f * progress,
                                        ),
                                        1f to surfaceColor.copy(
                                            alpha = 0.82f - 0.18f * progress,
                                        ),
                                    ),
                                ),
                            )
                            drawContent()
                        },
                    color = ComposeColor.Transparent,
                    navigationIcon = {
                        if (onBack != null) BackButton(onBack)
                    },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { padding -> content(padding, scrollBehavior) }
}

@Composable
internal fun BridgePageList(
    scaffoldPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = scaffoldPadding.calculateTopPadding() + 12.dp,
            end = 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 12.dp + extraBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun PreferenceIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(24.dp),
        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

@Composable
internal fun BridgeSaveBar(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.widthIn(min = 144.dp),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(text)
        }
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
    WindowDialog(
        show = message != null,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = message.orEmpty(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            style = MiuixTheme.textStyles.body2,
        )
        TextButton(
            text = "确定",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
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
    WindowDialog(
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

internal fun safeMessage(error: Throwable): String {
    return error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName
}
