package io.github.finall1008.xiaoaimcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.trace.TraceRetentionConfig
import io.github.finall1008.xiaoaimcp.trace.TraceSessionSummary
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class AgentTraceActivity : ComponentActivity() {
    private val controller by lazy { AgentTraceUiController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme(this) {
                AgentTraceRoute(
                    controller = controller,
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onResume() {
        super.onResume()
        controller.resume()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }
}

@Composable
internal fun AgentTraceRoute(
    controller: AgentTraceUiController,
    onBack: (() -> Unit)?,
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    AgentTraceScreen(
        sessions = controller.sessions,
        traceEnabled = controller.traceEnabled,
        preferencesReady = controller.preferencesReady,
        retention = controller.retention,
        search = controller.search,
        onSearchChange = { controller.search = it },
        onTraceEnabledChange = controller::updateTraceEnabled,
        onOpenRetention = controller::openRetention,
        onOpenSession = controller::openSession,
        onRequestClear = { controller.showClearConfirmation = true },
        onBack = onBack,
        errorMessage = controller.errorMessage,
        onDismissError = { controller.errorMessage = null },
        showClearConfirmation = controller.showClearConfirmation,
        onDismissClear = { controller.showClearConfirmation = false },
        onConfirmClear = controller::clearAll,
        extraBottomPadding = extraBottomPadding,
    )
}

@Composable
private fun AgentTraceScreen(
    sessions: List<TraceSessionSummary>,
    traceEnabled: Boolean,
    preferencesReady: Boolean,
    retention: TraceRetentionConfig,
    search: String,
    onSearchChange: (String) -> Unit,
    onTraceEnabledChange: (Boolean) -> Unit,
    onOpenRetention: () -> Unit,
    onOpenSession: (TraceSessionSummary) -> Unit,
    onRequestClear: () -> Unit,
    onBack: (() -> Unit)?,
    errorMessage: String?,
    onDismissError: () -> Unit,
    showClearConfirmation: Boolean,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
    extraBottomPadding: androidx.compose.ui.unit.Dp,
) {
    val query = search.trim()
    val filtered = sessions.filter { session ->
        query.isEmpty() || listOf(
            session.preview(),
            session.agentName(),
            session.agentId(),
            session.hostSessionId().orEmpty(),
        ).any { it.contains(query, ignoreCase = true) }
    }
    BridgePageScaffold(title = "Agent Trace", onBack = onBack) { padding, scrollBehavior ->
        BridgePageList(
            scaffoldPadding = padding,
            scrollBehavior = scrollBehavior,
            extraBottomPadding = extraBottomPadding,
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text("本地完整轨迹", style = MiuixTheme.textStyles.body1)
                    Text(
                        "记录升级后新产生的 System Prompt、用户输入、reasoning、输出和工具调用。内容可能包含账号、路径、凭据或私人信息，仅保存在模块私有数据库。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item { SectionLabel("采集设置") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = traceEnabled,
                        onCheckedChange = onTraceEnabledChange,
                        title = "启用 Agent Trace",
                        summary = "同时控制宿主内轨迹展示与模块内采集；重启超级小爱后生效",
                        startAction = { PreferenceIcon(MiuixIcons.ListView) },
                        enabled = preferencesReady,
                    )
                    ArrowPreference(
                        title = "保留策略",
                        summary = retention.summary(),
                        startAction = { PreferenceIcon(MiuixIcons.Timer) },
                        enabled = preferencesReady,
                        onClick = onOpenRetention,
                    )
                    ArrowPreference(
                        title = "清空全部轨迹",
                        summary = "永久删除模块数据库中的 ${sessions.size} 个会话",
                        startAction = { PreferenceIcon(MiuixIcons.Lock) },
                        enabled = sessions.isNotEmpty(),
                        onClick = onRequestClear,
                    )
                }
            }
            item { SectionLabel("会话 · ${sessions.size}") }
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    TextField(
                        value = search,
                        onValueChange = onSearchChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "搜索用户输入、Agent 或会话 ID",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text(
                            if (sessions.isEmpty()) {
                                "尚无轨迹。启用后重启超级小爱，再发起一个新会话。"
                            } else {
                                "没有匹配的会话。"
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            filtered.forEach { session ->
                item(key = session.sessionKey()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = traceTitle(session.preview()),
                            summary = sessionSummaryText(session),
                            startAction = { PreferenceIcon(MiuixIcons.ListView) },
                            onClick = { onOpenSession(session) },
                        )
                    }
                }
            }
        }
    }
    ConfirmDialog(
        show = showClearConfirmation,
        title = "清空全部轨迹",
        message = "将永久删除模块私有数据库中的全部 System Prompt、用户输入、模型输出和工具记录，无法恢复。",
        confirmText = "清空",
        onDismiss = onDismissClear,
        onConfirm = onConfirmClear,
    )
    MessageDialog(errorMessage, "Agent Trace 操作失败", onDismissError)
}

private fun sessionSummaryText(session: TraceSessionSummary): String {
    val agent = session.agentName().ifBlank { session.agentId().ifBlank { "未知 Agent" } }
    val status = when (session.status()) {
        "RUNNING" -> "运行中"
        "COMPLETED" -> "已完成"
        "ERROR" -> "失败"
        else -> session.status()
    }
    val completeness = if (session.partial()) " · 轨迹不完整" else ""
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(session.updatedAt()))
    return "$agent · $status$completeness\n${session.turnCount()} Turn · ${session.toolCount()} 工具 · $time"
}

internal fun traceTitle(value: String, maximum: Int = 80): String {
    val compact = value.replace(Regex("\\s+"), " ").trim()
    if (compact.isEmpty()) return "无用户正文"
    return if (compact.length <= maximum) compact else compact.take(maximum) + "…"
}
