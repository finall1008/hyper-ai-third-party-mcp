package io.github.finall1008.xiaoaimcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutConfig
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutMode
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class FirstOutputTimeoutActivity : ComponentActivity() {
    private var repository: FirstOutputTimeoutRepository? = null
    private var mode by mutableStateOf(FirstOutputTimeoutMode.HOST_DEFAULT)
    private var customSeconds by mutableStateOf(timeoutTextFieldValue(120L))
    private var errorMessage by mutableStateOf<String?>(null)
    private var fatalMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
        setContent {
            BridgeTheme(this) {
                FirstOutputTimeoutScreen(
                    mode = mode,
                    customSeconds = customSeconds,
                    editingEnabled = repository != null,
                    onModeChange = { mode = it },
                    onCustomSecondsChange = { customSeconds = it },
                    onBack = ::finish,
                    onSave = ::save,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                    fatalMessage = fatalMessage,
                    onDismissFatal = ::finish,
                )
            }
        }
    }

    private fun load() {
        val preferences = BridgeApplication.remotePreferences()
        if (preferences == null) {
            fatalMessage = "无法读取或保存首次输出超时配置。"
            return
        }
        repository = FirstOutputTimeoutRepository(preferences)
        val config = requireNotNull(repository).load()
        mode = config.mode()
        customSeconds = timeoutTextFieldValue(config.customSeconds())
    }

    private fun save() {
        try {
            val parsedSeconds = if (mode == FirstOutputTimeoutMode.CUSTOM) {
                parseFirstOutputTimeoutSeconds(customSeconds.text)
            } else {
                customSeconds.text.toLongOrNull()
                    ?.takeIf { it in 1..FirstOutputTimeoutConfig.MAX_CUSTOM_SECONDS }
                    ?: FirstOutputTimeoutConfig.DEFAULT_CUSTOM_SECONDS
            }
            (repository ?: error("API 102 服务未连接")).save(
                FirstOutputTimeoutConfig(mode, parsedSeconds),
            )
            finish()
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }
}

@Composable
private fun FirstOutputTimeoutScreen(
    mode: FirstOutputTimeoutMode,
    customSeconds: TextFieldValue,
    editingEnabled: Boolean,
    onModeChange: (FirstOutputTimeoutMode) -> Unit,
    onCustomSecondsChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    fatalMessage: String?,
    onDismissFatal: () -> Unit,
) {
    BridgePageScaffold(
        title = "模型首次输出超时",
        onBack = onBack,
        bottomBar = {
            BridgeSaveBar(
                text = "保存并返回",
                enabled = editingEnabled,
                onClick = onSave,
            )
        },
    ) { padding, scrollBehavior ->
        BridgePageList(
            scaffoldPadding = padding,
            scrollBehavior = scrollBehavior,
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text("作用范围", style = MiuixTheme.textStyles.body1)
                    Text(
                        "只控制模型开始输出正文或完整工具调用前的等待时间；reasoning 不计为首次可见输出。流式空闲、单次请求总时长和 MCP 超时不受影响。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item { SectionLabel("超时模式") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    TimeoutModePreference(
                        title = "跟随宿主（参考版为 120 秒）",
                        selected = mode == FirstOutputTimeoutMode.HOST_DEFAULT,
                        enabled = editingEnabled,
                        onClick = { onModeChange(FirstOutputTimeoutMode.HOST_DEFAULT) },
                    )
                    TimeoutModePreference(
                        title = "自定义",
                        selected = mode == FirstOutputTimeoutMode.CUSTOM,
                        enabled = editingEnabled,
                        onClick = { onModeChange(FirstOutputTimeoutMode.CUSTOM) },
                    )
                    TimeoutModePreference(
                        title = "不限制",
                        selected = mode == FirstOutputTimeoutMode.UNLIMITED,
                        enabled = editingEnabled,
                        onClick = { onModeChange(FirstOutputTimeoutMode.UNLIMITED) },
                    )
                }
            }
            if (mode == FirstOutputTimeoutMode.CUSTOM) {
                item { SectionLabel("自定义秒数") }
                item {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Column {
                            TextField(
                                value = customSeconds,
                                onValueChange = onCustomSecondsChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = "超时秒数",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Text(
                                "必须填写大于 0 的整数；保存后需结束并重新启动超级小爱。",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
        MessageDialog(errorMessage, "无法保存", onDismissError)
        MessageDialog(fatalMessage, "API 102 服务未连接", onDismissFatal)
    }
}

@Composable
private fun TimeoutModePreference(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    RadioButtonPreference(
        title = title,
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        radioButtonLocation = RadioButtonLocation.End,
    )
}

internal fun parseFirstOutputTimeoutSeconds(text: String): Long {
    val seconds = text.trim().toLongOrNull()
        ?: throw IllegalArgumentException("首次输出超时必须是大于 0 的整数秒")
    FirstOutputTimeoutConfig(FirstOutputTimeoutMode.CUSTOM, seconds)
    return seconds
}

internal fun timeoutTextFieldValue(seconds: Long): TextFieldValue {
    val text = seconds.toString()
    return TextFieldValue(text = text, selection = TextRange(text.length))
}
