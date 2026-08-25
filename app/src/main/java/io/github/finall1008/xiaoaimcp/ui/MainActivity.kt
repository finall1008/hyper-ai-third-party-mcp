package io.github.finall1008.xiaoaimcp.ui

import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.BridgeContract
import io.github.finall1008.xiaoaimcp.TargetVersionPolicy
import io.github.finall1008.xiaoaimcp.config.McpServer
import io.github.finall1008.xiaoaimcp.config.RemoteConfigRepository
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

class MainActivity : ComponentActivity(), BridgeApplication.ServiceStateListener {
    private var state by mutableStateOf(MainUiState())
    private var errorMessage by mutableStateOf<String?>(null)
    private var preferences: SharedPreferences? = null
    private var repository: RemoteConfigRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme(this) {
                MainScreen(
                    state = state,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                    onAdd = { startActivity(Intent(this, ServerEditActivity::class.java)) },
                    onOpenFilePolicy = {
                        startActivity(Intent(this, FilePolicyActivity::class.java))
                    },
                    onAgentTraceEnabledChange = ::setAgentTraceEnabled,
                    onEdit = ::edit,
                    onEnabledChange = ::setServerEnabled,
                    onDelete = ::delete,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BridgeApplication.addServiceStateListener(this, true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        BridgeApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread(::refresh)
    }

    private fun refresh() {
        val service = BridgeApplication.service()
        var serviceReady = false
        var frameworkStatus = if (service == null) {
            StatusUi(StatusLevel.ERROR, "API 102 服务未连接：请安装、启用模块并重新打开此页面")
        } else {
            try {
                val api = service.apiVersion
                serviceReady = api >= XposedService.API_102
                StatusUi(
                    if (serviceReady) StatusLevel.SUCCESS else StatusLevel.ERROR,
                    "${service.frameworkName} ${service.frameworkVersion} · API $api",
                )
            } catch (error: RuntimeException) {
                StatusUi(StatusLevel.ERROR, "读取 Xposed 服务失败：${safeMessage(error)}")
            }
        }

        val (targetStatus, targetReady) = targetStatus()
        preferences = if (serviceReady) {
            BridgeApplication.remotePreferences()
        } else {
            null
        }
        repository = preferences?.let(::RemoteConfigRepository)
        val servers = try {
            repository?.load().orEmpty()
        } catch (error: Exception) {
            frameworkStatus = StatusUi(StatusLevel.ERROR, "MCP 配置损坏：${safeMessage(error)}")
            emptyList()
        }
        state = MainUiState(
            frameworkStatus = frameworkStatus,
            targetStatus = targetStatus,
            editingEnabled = serviceReady && targetReady,
            agentTraceEnabled = preferences?.getBoolean(
                BridgeContract.PREF_AGENT_TRACE_ENABLED,
                BridgeContract.DEFAULT_AGENT_TRACE_ENABLED,
            ) ?: BridgeContract.DEFAULT_AGENT_TRACE_ENABLED,
            servers = servers,
        )
    }

    @Suppress("DEPRECATION")
    private fun targetStatus(): Pair<StatusUi, Boolean> {
        return try {
            val info = packageManager.getPackageInfo(BridgeContract.TARGET_PACKAGE, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong() and 0xffffffffL
            }
            val versionName = info.versionName
            val supported = TargetVersionPolicy.isSupported(versionName)
            val reference = supported &&
                BridgeContract.REFERENCE_VERSION_NAME == versionName &&
                BridgeContract.REFERENCE_VERSION_CODE == code
            val version = "超级小爱 ${versionName.toString()} ($code)"
            when {
                reference -> StatusUi(StatusLevel.SUCCESS, "$version · 已验证") to true
                supported -> StatusUi(
                    StatusLevel.WARNING,
                    "$version · 将在目标进程中自动探测兼容性",
                ) to true
                else -> {
                    val reason = if (TargetVersionPolicy.parseMajor(versionName).isPresent) {
                        "要求超级小爱 8.0 或更高版本"
                    } else {
                        "无法识别版本号，要求超级小爱 8.0 或更高版本"
                    }
                    StatusUi(StatusLevel.ERROR, "$version · $reason") to false
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            StatusUi(StatusLevel.ERROR, "未安装超级小爱：${BridgeContract.TARGET_PACKAGE}") to false
        }
    }

    private fun edit(server: McpServer) {
        startActivity(
            Intent(this, ServerEditActivity::class.java)
                .putExtra(ServerEditActivity.EXTRA_SERVER_ID, server.id()),
        )
    }

    private fun setServerEnabled(server: McpServer, enabled: Boolean) {
        try {
            requireRepository().setEnabled(server.id(), enabled)
            refresh()
        } catch (error: Exception) {
            errorMessage = safeMessage(error)
        }
    }

    private fun setAgentTraceEnabled(enabled: Boolean) {
        try {
            val editor = preferences?.edit()
                ?: error("API 102 服务未连接")
            editor.putBoolean(BridgeContract.PREF_AGENT_TRACE_ENABLED, enabled).apply()
            state = state.copy(agentTraceEnabled = enabled)
        } catch (error: Exception) {
            errorMessage = safeMessage(error)
        }
    }

    private fun delete(server: McpServer) {
        try {
            requireRepository().delete(server.id())
            refresh()
        } catch (error: Exception) {
            errorMessage = safeMessage(error)
        }
    }

    private fun requireRepository(): RemoteConfigRepository {
        return repository ?: error("API 102 服务未连接")
    }
}

private data class MainUiState(
    val frameworkStatus: StatusUi = StatusUi(StatusLevel.ERROR, "正在连接 API 102 服务…"),
    val targetStatus: StatusUi = StatusUi(StatusLevel.WARNING, "正在检查超级小爱版本…"),
    val editingEnabled: Boolean = false,
    val agentTraceEnabled: Boolean = BridgeContract.DEFAULT_AGENT_TRACE_ENABLED,
    val servers: List<McpServer> = emptyList(),
)

private data class StatusUi(val level: StatusLevel, val text: String)

private enum class StatusLevel { SUCCESS, WARNING, ERROR }

@Composable
private fun MainScreen(
    state: MainUiState,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onAdd: () -> Unit,
    onOpenFilePolicy: () -> Unit,
    onAgentTraceEnabledChange: (Boolean) -> Unit,
    onEdit: (McpServer) -> Unit,
    onEnabledChange: (McpServer, Boolean) -> Unit,
    onDelete: (McpServer) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<McpServer?>(null) }
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "超级小爱 MCP Bridge",
                subtitle = "第三方 MCP 与文件权限配置",
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 12.dp + navigationBarPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("运行状态") }
            item { StatusCard("Xposed 框架", state.frameworkStatus) }
            item { StatusCard("目标应用", state.targetStatus) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Agent 完整轨迹", style = MiuixTheme.textStyles.body1)
                            Text(
                                "显示公开 reasoning 和工具输入、输出详情；切换后需重启超级小爱",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(
                            checked = state.agentTraceEnabled,
                            onCheckedChange = if (state.editingEnabled) {
                                onAgentTraceEnabledChange
                            } else {
                                null
                            },
                            enabled = state.editingEnabled,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onAdd,
                    enabled = state.editingEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("添加 MCP 服务器")
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                    onClick = if (state.editingEnabled) onOpenFilePolicy else null,
                ) {
                    Text("文件访问权限", style = MiuixTheme.textStyles.body1)
                    Text(
                        "配置允许超级小爱访问的 /sdcard 目录及风险确认策略",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item {
                SectionLabel(
                    text = "MCP 服务器",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.servers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(24.dp),
                    ) {
                        Text(
                            "尚未添加第三方 MCP 服务器",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                items(state.servers, key = { it.id() }) { server ->
                    ServerCard(
                        server = server,
                        editingEnabled = state.editingEnabled,
                        onEdit = { onEdit(server) },
                        onEnabledChange = { onEnabledChange(server, it) },
                        onDelete = { pendingDelete = server },
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        ConfirmDialog(
            show = pendingDelete != null,
            title = "删除服务器",
            message = pendingDelete?.let {
                "确认删除 “${it.name()}”？超级小爱中的对应工具会在线注销。"
            }.orEmpty(),
            confirmText = "删除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete?.let(onDelete)
                pendingDelete = null
            },
        )
        MessageDialog(
            message = errorMessage,
            title = "操作失败",
            onDismiss = onDismissError,
        )
    }
}

@Composable
private fun StatusCard(title: String, status: StatusUi) {
    val colors = when (status.level) {
        StatusLevel.SUCCESS -> CardDefaults.defaultColors(
            MiuixTheme.colorScheme.primaryContainer,
            MiuixTheme.colorScheme.onPrimaryContainer,
        )
        StatusLevel.WARNING -> CardDefaults.defaultColors(
            MiuixTheme.colorScheme.tertiaryContainer,
            MiuixTheme.colorScheme.onTertiaryContainer,
        )
        StatusLevel.ERROR -> CardDefaults.defaultColors(
            MiuixTheme.colorScheme.errorContainer,
            MiuixTheme.colorScheme.onErrorContainer,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp),
        colors = colors,
    ) {
        Text(title, style = MiuixTheme.textStyles.footnote1)
        Text(status.text, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun ServerCard(
    server: McpServer,
    editingEnabled: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        onClick = if (editingEnabled) onEdit else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name(),
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = buildString {
                    append(server.transport().uppercase(Locale.ROOT))
                    append(" · ")
                    append(server.url())
                    if (server.headers().isNotEmpty()) {
                        append(" · ${server.headers().size} 个请求头")
                    }
                }
                Text(
                    detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = server.enabled(),
                onCheckedChange = if (editingEnabled) onEnabledChange else null,
                enabled = editingEnabled,
            )
        }
        if (editingEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = "编辑 ${server.name()}",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "删除 ${server.name()}",
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
