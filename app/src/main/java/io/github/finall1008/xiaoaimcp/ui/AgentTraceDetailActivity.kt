package io.github.finall1008.xiaoaimcp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.trace.AgentTraceContract
import io.github.finall1008.xiaoaimcp.trace.AgentTraceDatabase
import io.github.finall1008.xiaoaimcp.trace.TraceCardKind
import io.github.finall1008.xiaoaimcp.trace.TraceSessionDetail
import io.github.finall1008.xiaoaimcp.trace.TraceTimelineCard
import io.github.finall1008.xiaoaimcp.trace.TraceTimelineProjector
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.theme.MiuixTheme

class AgentTraceDetailActivity : ComponentActivity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var detail by mutableStateOf<TraceSessionDetail?>(null)
    private var cards by mutableStateOf<List<TraceTimelineCard>>(emptyList())
    private var selectedKind by mutableStateOf<TraceCardKind?>(null)
    private var search by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)
    private var showDeleteConfirmation by mutableStateOf(false)

    private val sessionKey by lazy {
        intent.getStringExtra(AgentTraceContract.EXTRA_SESSION_KEY).orEmpty()
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (sessionKey.isBlank()) {
            finish()
            return
        }
        setContent {
            BridgeTheme(this) {
                AgentTraceDetailScreen(
                    detail = detail,
                    cards = cards,
                    selectedKind = selectedKind,
                    search = search,
                    onKindChange = { selectedKind = it },
                    onSearchChange = { search = it },
                    onCopy = ::copy,
                    onBack = ::finish,
                    onRequestDelete = { showDeleteConfirmation = true },
                    showDeleteConfirmation = showDeleteConfirmation,
                    onDismissDelete = { showDeleteConfirmation = false },
                    onConfirmDelete = ::deleteSession,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(AgentTraceContract.CONTENT_URI, true, observer)
        load()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        ioExecutor.execute {
            try {
                val loaded = AgentTraceDatabase.get(applicationContext).loadSession(sessionKey)
                runOnUiThread {
                    if (loaded == null) {
                        finish()
                    } else {
                        detail = loaded
                        cards = TraceTimelineProjector.project(loaded)
                    }
                }
            } catch (error: RuntimeException) {
                runOnUiThread { errorMessage = safeMessage(error) }
            }
        }
    }

    private fun copy(label: String, value: String) {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
                ?: error("系统剪贴板不可用")
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    private fun deleteSession() {
        showDeleteConfirmation = false
        ioExecutor.execute {
            try {
                AgentTraceDatabase.get(applicationContext).deleteSession(sessionKey)
                runOnUiThread(::finish)
            } catch (error: RuntimeException) {
                runOnUiThread { errorMessage = safeMessage(error) }
            }
        }
    }
}

@Composable
private fun AgentTraceDetailScreen(
    detail: TraceSessionDetail?,
    cards: List<TraceTimelineCard>,
    selectedKind: TraceCardKind?,
    search: String,
    onKindChange: (TraceCardKind?) -> Unit,
    onSearchChange: (String) -> Unit,
    onCopy: (String, String) -> Unit,
    onBack: () -> Unit,
    onRequestDelete: () -> Unit,
    showDeleteConfirmation: Boolean,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
) {
    val summary = detail?.summary()
    val query = search.trim()
    val visibleCards = cards.filter { card ->
        (selectedKind == null || card.kind == selectedKind) &&
            (query.isEmpty() || listOf(
                card.title,
                card.summary,
                card.detail,
                card.rawJson,
            ).any { it.contains(query, ignoreCase = true) })
    }
    BridgePageScaffold(
        title = summary?.let { traceTitle(it.preview()) } ?: "Agent Trace",
        onBack = onBack,
        actions = {
            IconButton(onClick = onRequestDelete) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "删除会话轨迹",
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        },
    ) { padding, scrollBehavior ->
        BridgePageList(scaffoldPadding = padding, scrollBehavior = scrollBehavior) {
            if (summary != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            summary.agentName().ifBlank {
                                summary.agentId().ifBlank { "未知 Agent" }
                            },
                            style = MiuixTheme.textStyles.body1,
                        )
                        Text(
                            traceSessionMetadata(summary),
                            modifier = Modifier.padding(top = 6.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (summary.partial()) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
            }
            item { SectionLabel("筛选") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(12.dp),
                ) {
                    TextField(
                        value = search,
                        onValueChange = onSearchChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "搜索当前轨迹",
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TraceFilterButton("全部", selectedKind == null) {
                            onKindChange(null)
                        }
                        TraceCardKind.entries.forEach { kind ->
                            TraceFilterButton(kind.label, selectedKind == kind) {
                                onKindChange(kind)
                            }
                        }
                    }
                }
            }
            if (visibleCards.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            if (detail == null) "正在加载…" else "没有匹配的轨迹事件。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            var previousTurn = -1
            visibleCards.forEach { card ->
                if (card.turnIndex != previousTurn) {
                    previousTurn = card.turnIndex
                    item(key = "turn:${card.turnIndex}") {
                        SectionLabel("Turn ${card.turnIndex}")
                    }
                }
                item(key = card.id) {
                    TraceEventCard(card = card, onCopy = onCopy)
                }
            }
        }
    }
    ConfirmDialog(
        show = showDeleteConfirmation,
        title = "删除会话轨迹",
        message = "将永久删除这个会话的 System Prompt、用户输入、模型输出及全部工具记录，无法恢复。",
        confirmText = "删除",
        onDismiss = onDismissDelete,
        onConfirm = onConfirmDelete,
    )
    MessageDialog(errorMessage, "无法读取 Agent Trace", onDismissError)
}

@Composable
private fun TraceFilterButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        text = if (selected) "● $text" else text,
        onClick = onClick,
    )
}

@Composable
private fun TraceEventCard(
    card: TraceTimelineCard,
    onCopy: (String, String) -> Unit,
) {
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    var showRaw by rememberSaveable("${card.id}:raw") { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${card.kind.label} · ${card.title}",
                    style = MiuixTheme.textStyles.body1,
                    color = traceKindColor(card.kind),
                )
                Text(
                    card.summary,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    traceTime(card.observedAt, card.status),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Icon(
                imageVector = if (expanded) {
                    MiuixIcons.ExpandLess
                } else {
                    MiuixIcons.ExpandMore
                },
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.padding(start = 12.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        if (expanded) {
            Text(
                if (showRaw) card.rawJson else card.detail,
                modifier = Modifier.padding(top = 12.dp),
                style = MiuixTheme.textStyles.body2,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        ) {
            IconButton(
                onClick = {
                    showRaw = !showRaw
                    expanded = true
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.File,
                    contentDescription = if (showRaw) "查看内容" else "查看原始 JSON",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            IconButton(
                onClick = {
                    onCopy(card.title, if (showRaw) card.rawJson else card.detail)
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = if (showRaw) "复制原始 JSON" else "复制内容",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}

@Composable
private fun traceKindColor(kind: TraceCardKind) = when (kind) {
    TraceCardKind.SYSTEM -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    TraceCardKind.USER -> MiuixTheme.colorScheme.primary
    TraceCardKind.ASSISTANT -> MiuixTheme.colorScheme.onSecondaryContainer
    TraceCardKind.TOOL -> MiuixTheme.colorScheme.onTertiaryContainer
    TraceCardKind.CONTROL -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    TraceCardKind.ERROR -> MiuixTheme.colorScheme.error
}

private fun traceSessionMetadata(summary: io.github.finall1008.xiaoaimcp.trace.TraceSessionSummary): String {
    val status = when (summary.status()) {
        "RUNNING" -> "运行中"
        "COMPLETED" -> "已完成"
        "ERROR" -> "失败"
        else -> summary.status()
    }
    val incomplete = if (summary.partial()) " · 轨迹不完整" else " · 轨迹完整"
    val started = DateFormat.getDateTimeInstance().format(Date(summary.startedAt()))
    return "$status$incomplete\n${summary.turnCount()} Turn · ${summary.toolCount()} 工具\n$started"
}

private fun traceTime(time: Long, status: String?): String {
    val formatted = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(time))
    return if (status.isNullOrBlank()) formatted else "$formatted · $status"
}
