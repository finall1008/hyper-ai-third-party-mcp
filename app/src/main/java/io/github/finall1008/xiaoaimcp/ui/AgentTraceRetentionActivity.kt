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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.trace.AgentTraceContract
import io.github.finall1008.xiaoaimcp.trace.AgentTraceDatabase
import io.github.finall1008.xiaoaimcp.trace.TraceRetentionConfig
import io.github.finall1008.xiaoaimcp.trace.TraceRetentionStore
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class AgentTraceRetentionActivity : ComponentActivity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var daysEnabled by mutableStateOf(true)
    private var daysText by mutableStateOf(AgentTraceContract.DEFAULT_RETENTION_DAYS.toString())
    private var sessionsEnabled by mutableStateOf(true)
    private var sessionsText by mutableStateOf(
        AgentTraceContract.DEFAULT_RETENTION_SESSIONS.toString(),
    )
    private var errorMessage by mutableStateOf<String?>(null)
    private var fatalMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
        setContent {
            BridgeTheme(this) {
                AgentTraceRetentionScreen(
                    daysEnabled = daysEnabled,
                    daysText = daysText,
                    sessionsEnabled = sessionsEnabled,
                    sessionsText = sessionsText,
                    onDaysEnabledChange = { daysEnabled = it },
                    onDaysTextChange = { daysText = it },
                    onSessionsEnabledChange = { sessionsEnabled = it },
                    onSessionsTextChange = { sessionsText = it },
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

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val preferences = BridgeApplication.remotePreferences()
        if (preferences == null) {
            fatalMessage = "无法读取或保存 Agent Trace 保留策略。"
            return
        }
        val config = TraceRetentionStore.load(applicationContext, preferences)
        daysEnabled = !config.daysUnlimited()
        daysText = config.days().toString()
        sessionsEnabled = !config.sessionsUnlimited()
        sessionsText = config.sessions().toString()
    }

    private fun save() {
        try {
            val preferences = BridgeApplication.remotePreferences()
                ?: error("API 102 服务未连接")
            val days = if (daysEnabled) {
                parseRetentionValue(
                    daysText,
                    AgentTraceContract.MAX_RETENTION_DAYS,
                    "保留天数",
                )
            } else {
                daysText.toIntOrNull()
                    ?.takeIf { it in 1..AgentTraceContract.MAX_RETENTION_DAYS }
                    ?: AgentTraceContract.DEFAULT_RETENTION_DAYS
            }
            val sessions = if (sessionsEnabled) {
                parseRetentionValue(
                    sessionsText,
                    AgentTraceContract.MAX_RETENTION_SESSIONS,
                    "最大会话数",
                )
            } else {
                sessionsText.toIntOrNull()
                    ?.takeIf { it in 1..AgentTraceContract.MAX_RETENTION_SESSIONS }
                    ?: AgentTraceContract.DEFAULT_RETENTION_SESSIONS
            }
            val config = TraceRetentionConfig(
                !daysEnabled,
                days,
                !sessionsEnabled,
                sessions,
            )
            config.save(preferences)
            TraceRetentionStore.saveMirror(applicationContext, config)
            ioExecutor.execute {
                try {
                    AgentTraceDatabase.get(applicationContext).prune(config)
                    runOnUiThread(::finish)
                } catch (error: RuntimeException) {
                    runOnUiThread { errorMessage = safeMessage(error) }
                }
            }
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }
}

@Composable
private fun AgentTraceRetentionScreen(
    daysEnabled: Boolean,
    daysText: String,
    sessionsEnabled: Boolean,
    sessionsText: String,
    onDaysEnabledChange: (Boolean) -> Unit,
    onDaysTextChange: (String) -> Unit,
    onSessionsEnabledChange: (Boolean) -> Unit,
    onSessionsTextChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    fatalMessage: String?,
    onDismissFatal: () -> Unit,
) {
    BridgePageScaffold(
        title = "轨迹保留策略",
        onBack = onBack,
        bottomBar = {
            BridgeSaveBar(text = "保存并立即清理", enabled = fatalMessage == null, onClick = onSave)
        },
    ) { padding, scrollBehavior ->
        BridgePageList(scaffoldPadding = padding, scrollBehavior = scrollBehavior) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text("任一已启用的限制达到后，删除最旧会话。关闭两项限制即永久保留，直到手动删除。")
                    Text(
                        "默认保留 30 天且最多 100 个会话。修改只影响模块轨迹数据库，无需重启超级小爱。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item { SectionLabel("按时间清理") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = daysEnabled,
                        onCheckedChange = onDaysEnabledChange,
                        title = "限制保留天数",
                        summary = if (daysEnabled) "$daysText 天" else "不限制",
                    )
                }
            }
            if (daysEnabled) {
                item {
                    RetentionNumberField(
                        label = "保留天数",
                        value = daysText,
                        onValueChange = onDaysTextChange,
                        hint = "1–${AgentTraceContract.MAX_RETENTION_DAYS}",
                    )
                }
            }
            item { SectionLabel("按数量清理") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = sessionsEnabled,
                        onCheckedChange = onSessionsEnabledChange,
                        title = "限制最大会话数",
                        summary = if (sessionsEnabled) "$sessionsText 个会话" else "不限制",
                    )
                }
            }
            if (sessionsEnabled) {
                item {
                    RetentionNumberField(
                        label = "最大会话数",
                        value = sessionsText,
                        onValueChange = onSessionsTextChange,
                        hint = "1–${AgentTraceContract.MAX_RETENTION_SESSIONS}",
                    )
                }
            }
        }
    }
    MessageDialog(errorMessage, "无法保存保留策略", onDismissError)
    MessageDialog(fatalMessage, "API 102 服务未连接", onDismissFatal)
}

@Composable
private fun RetentionNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = label,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                "允许范围：$hint",
                modifier = Modifier.padding(top = 8.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

internal fun parseRetentionValue(text: String, maximum: Int, label: String): Int {
    val value = text.trim().toIntOrNull()
        ?: throw IllegalArgumentException("$label 必须是整数")
    if (value !in 1..maximum) {
        throw IllegalArgumentException("$label 必须在 1–$maximum 之间")
    }
    return value
}
