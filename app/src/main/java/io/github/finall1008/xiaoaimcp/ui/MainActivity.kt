package io.github.finall1008.xiaoaimcp.ui

import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.BridgeContract
import io.github.finall1008.xiaoaimcp.BuildConfig
import io.github.finall1008.xiaoaimcp.R
import io.github.finall1008.xiaoaimcp.TargetVersionPolicy
import io.github.finall1008.xiaoaimcp.config.McpServer
import io.github.finall1008.xiaoaimcp.config.RemoteConfigRepository
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyConfig
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyRepository
import io.github.finall1008.xiaoaimcp.restart.RestartStateListener
import io.github.finall1008.xiaoaimcp.restart.XiaoAiRootRestarter
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutConfig
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutMode
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutRepository
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

class MainActivity :
    ComponentActivity(),
    BridgeApplication.ServiceStateListener,
    RestartStateListener {
    private var state by mutableStateOf(MainUiState())
    private var errorMessage by mutableStateOf<String?>(null)
    private var preferences: SharedPreferences? = null
    private var repository: RemoteConfigRepository? = null
    private var restartInFlight by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme(this) {
                MainScreen(
                    state = state,
                    errorMessage = errorMessage,
                    onDismissError = {
                        XiaoAiRootRestarter.clearError()
                        errorMessage = null
                    },
                    onAdd = { startActivity(Intent(this, ServerEditActivity::class.java)) },
                    onOpenFilePolicy = {
                        startActivity(Intent(this, FilePolicyActivity::class.java))
                    },
                    onOpenPromptPatches = {
                        startActivity(Intent(this, PromptPatchActivity::class.java))
                    },
                    onAgentTraceEnabledChange = ::setAgentTraceEnabled,
                    onOpenFirstOutputTimeout = {
                        startActivity(Intent(this, FirstOutputTimeoutActivity::class.java))
                    },
                    restartInFlight = restartInFlight,
                    onRestartXiaoAi = ::restartXiaoAi,
                    onOpenReleases = ::openReleases,
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
        XiaoAiRootRestarter.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        XiaoAiRootRestarter.removeListener(this)
        BridgeApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread(::refresh)
    }

    override fun onRestartStateChanged(inFlight: Boolean, errorMessage: String?) {
        runOnUiThread {
            restartInFlight = inFlight
            if (errorMessage != null) this.errorMessage = errorMessage
        }
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
        val filePolicySummary = if (preferences == null) {
            "API 102 服务未连接"
        } else {
            try {
                filePolicySummary(FilePolicyRepository(requireNotNull(preferences)).load())
            } catch (error: RuntimeException) {
                "配置损坏：${safeMessage(error)}"
            }
        }
        state = MainUiState(
            frameworkStatus = frameworkStatus,
            targetStatus = targetStatus,
            editingEnabled = serviceReady && targetReady,
            agentTraceEnabled = preferences?.getBoolean(
                BridgeContract.PREF_AGENT_TRACE_ENABLED,
                BridgeContract.DEFAULT_AGENT_TRACE_ENABLED,
            ) ?: BridgeContract.DEFAULT_AGENT_TRACE_ENABLED,
            firstOutputTimeout = preferences?.let {
                FirstOutputTimeoutRepository(it).load()
            } ?: FirstOutputTimeoutConfig.hostDefault(),
            filePolicySummary = filePolicySummary,
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

    private fun openReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL)))
        } catch (error: Exception) {
            errorMessage = "无法打开 GitHub Releases：${safeMessage(error)}"
        }
    }

    private fun restartXiaoAi() {
        XiaoAiRootRestarter.restart(applicationContext)
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
    val firstOutputTimeout: FirstOutputTimeoutConfig =
        FirstOutputTimeoutConfig.hostDefault(),
    val filePolicySummary: String = "正在读取文件权限配置…",
    val servers: List<McpServer> = emptyList(),
)

private data class StatusUi(val level: StatusLevel, val text: String)

private enum class StatusLevel { SUCCESS, WARNING, ERROR }

internal enum class RootPage(val label: String) {
    HOME("首页"),
    ABOUT("关于"),
}

internal val ROOT_PAGES = RootPage.entries.toList()
internal val DEFAULT_ROOT_PAGE = RootPage.HOME
internal const val GITHUB_RELEASES_URL =
    "https://github.com/finall1008/hyper-ai-third-party-mcp/releases"

@Composable
private fun MainScreen(
    state: MainUiState,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onAdd: () -> Unit,
    onOpenFilePolicy: () -> Unit,
    onOpenPromptPatches: () -> Unit,
    onAgentTraceEnabledChange: (Boolean) -> Unit,
    onOpenFirstOutputTimeout: () -> Unit,
    restartInFlight: Boolean,
    onRestartXiaoAi: () -> Unit,
    onOpenReleases: () -> Unit,
    onEdit: (McpServer) -> Unit,
    onEnabledChange: (McpServer, Boolean) -> Unit,
    onDelete: (McpServer) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<McpServer?>(null) }
    var showRestartConfirmation by remember { mutableStateOf(false) }
    var selectedPage by rememberSaveable { mutableStateOf(DEFAULT_ROOT_PAGE) }
    val appName = stringResource(R.string.app_name)
    Box(modifier = Modifier.fillMaxSize()) {
        key(selectedPage) {
            BridgePageScaffold(
                title = if (selectedPage == RootPage.HOME) {
                    appName
                } else {
                    "关于"
                },
                actions = {
                    if (selectedPage == RootPage.HOME) {
                        IconButton(
                            onClick = { showRestartConfirmation = true },
                            enabled = !restartInFlight,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "重启超级小爱",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            ) { padding, scrollBehavior ->
                when (selectedPage) {
                    RootPage.HOME -> HomePage(
                        state = state,
                        scaffoldPadding = padding,
                        scrollBehavior = scrollBehavior,
                        onAdd = onAdd,
                        onOpenFilePolicy = onOpenFilePolicy,
                        onOpenPromptPatches = onOpenPromptPatches,
                        onAgentTraceEnabledChange = onAgentTraceEnabledChange,
                        onEditTimeout = onOpenFirstOutputTimeout,
                        onEdit = onEdit,
                        onEnabledChange = onEnabledChange,
                        onDelete = { pendingDelete = it },
                    )
                    RootPage.ABOUT -> AboutPage(
                        scaffoldPadding = padding,
                        scrollBehavior = scrollBehavior,
                        onOpenReleases = onOpenReleases,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            FloatingNavigationBar {
                FloatingNavigationBarItem(
                    selected = selectedPage == RootPage.HOME,
                    onClick = { selectedPage = RootPage.HOME },
                    icon = MiuixIcons.Home,
                    label = RootPage.HOME.label,
                )
                FloatingNavigationBarItem(
                    selected = selectedPage == RootPage.ABOUT,
                    onClick = { selectedPage = RootPage.ABOUT },
                    icon = MiuixIcons.Info,
                    label = RootPage.ABOUT.label,
                )
            }
        }
    }
    ConfirmDialog(
        show = showRestartConfirmation,
        title = "重启超级小爱",
        message = "需要 root 权限，并将结束超级小爱全部进程后重新打开。正在进行的对话、模型响应和工具调用会立即中断。",
        confirmText = "重启",
        onDismiss = { showRestartConfirmation = false },
        onConfirm = {
            showRestartConfirmation = false
            onRestartXiaoAi()
        },
    )
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

@Composable
private fun HomePage(
    state: MainUiState,
    scaffoldPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onAdd: () -> Unit,
    onOpenFilePolicy: () -> Unit,
    onOpenPromptPatches: () -> Unit,
    onAgentTraceEnabledChange: (Boolean) -> Unit,
    onEditTimeout: () -> Unit,
    onEdit: (McpServer) -> Unit,
    onEnabledChange: (McpServer, Boolean) -> Unit,
    onDelete: (McpServer) -> Unit,
) {
    BridgePageList(
        scaffoldPadding = scaffoldPadding,
        scrollBehavior = scrollBehavior,
        extraBottomPadding = 108.dp,
    ) {
        item { SectionLabel("运行状态") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                StatusRow("Xposed 框架", state.frameworkStatus)
                StatusRow("目标应用", state.targetStatus)
            }
        }
        item { SectionLabel("Agent 设置", Modifier.padding(top = 4.dp)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.agentTraceEnabled,
                    onCheckedChange = onAgentTraceEnabledChange,
                    title = "Agent 完整轨迹",
                    summary = "显示 reasoning 与工具详情；重启后生效",
                    startAction = { PreferenceIcon(MiuixIcons.ListView) },
                    enabled = state.editingEnabled,
                )
                ArrowPreference(
                    title = "模型首次输出超时",
                    summary = firstOutputTimeoutSummary(state.firstOutputTimeout),
                    startAction = { PreferenceIcon(MiuixIcons.Timer) },
                    enabled = state.editingEnabled,
                    onClick = onEditTimeout,
                )
                ArrowPreference(
                    title = "System Prompt 补丁",
                    summary = "精确替换规则与变更预览",
                    startAction = { PreferenceIcon(MiuixIcons.Messages) },
                    enabled = state.editingEnabled,
                    onClick = onOpenPromptPatches,
                )
            }
        }
        item { SectionLabel("权限设置", Modifier.padding(top = 4.dp)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "文件访问权限",
                    summary = state.filePolicySummary,
                    startAction = { PreferenceIcon(MiuixIcons.Lock) },
                    enabled = state.editingEnabled,
                    onClick = onOpenFilePolicy,
                )
            }
        }
        item { SectionLabel("MCP 服务器", Modifier.padding(top = 4.dp)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "添加 MCP 服务器",
                    summary = "连接第三方 Streamable HTTP 或 SSE 服务",
                    startAction = { PreferenceIcon(MiuixIcons.Add) },
                    enabled = state.editingEnabled,
                    onClick = onAdd,
                )
                if (state.servers.isEmpty()) {
                    BasicComponent(
                        title = "尚未添加服务器",
                        summary = "添加后可在这里启停、编辑或删除",
                        startAction = { PreferenceIcon(MiuixIcons.Link) },
                        enabled = false,
                    )
                } else {
                    state.servers.forEach { server ->
                        ServerPreference(
                            server = server,
                            editingEnabled = state.editingEnabled,
                            onEdit = { onEdit(server) },
                            onEnabledChange = { onEnabledChange(server, it) },
                            onDelete = { onDelete(server) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, status: StatusUi) {
    val (label, color) = when (status.level) {
        StatusLevel.SUCCESS -> "正常" to MiuixTheme.colorScheme.primary
        StatusLevel.WARNING -> "注意" to MiuixTheme.colorScheme.onTertiaryContainer
        StatusLevel.ERROR -> "异常" to MiuixTheme.colorScheme.error
    }
    BasicComponent(
        title = title,
        summary = status.text,
        startAction = { PreferenceIcon(MiuixIcons.Info) },
        endActions = { Text(label, style = MiuixTheme.textStyles.body2, color = color) },
    )
}

@Composable
private fun AboutPage(
    scaffoldPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onOpenReleases: () -> Unit,
) {
    BridgePageList(
        scaffoldPadding = scaffoldPadding,
        scrollBehavior = scrollBehavior,
        extraBottomPadding = 108.dp,
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.app_name),
                    summary = aboutVersionLabel(BuildConfig.VERSION_NAME),
                    startAction = { PreferenceIcon(MiuixIcons.Info) },
                )
                ArrowPreference(
                    title = "GitHub Releases",
                    summary = "查看版本更新与安装包",
                    startAction = { PreferenceIcon(MiuixIcons.Link) },
                    onClick = onOpenReleases,
                )
            }
        }
    }
}

internal fun firstOutputTimeoutSummary(config: FirstOutputTimeoutConfig): String {
    return when (config.mode()) {
        FirstOutputTimeoutMode.HOST_DEFAULT -> "跟随宿主（参考版 120 秒）"
        FirstOutputTimeoutMode.CUSTOM -> "自定义 ${config.customSeconds()} 秒"
        FirstOutputTimeoutMode.UNLIMITED -> "不限制"
    }
}

internal fun filePolicySummary(config: FilePolicyConfig): String {
    val enabled = if (config.enabled()) "已启用" else "未启用"
    val rules = if (config.rules().isEmpty()) {
        "尚未配置目录规则"
    } else {
        "${config.rules().size} 条目录规则"
    }
    return "$enabled · $rules"
}

internal fun aboutVersionLabel(versionName: String): String {
    return "当前版本 $versionName"
}

@Composable
private fun ServerPreference(
    server: McpServer,
    editingEnabled: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(
        startAction = { PreferenceIcon(MiuixIcons.Folder) },
        endActions = {
            Switch(
                checked = server.enabled(),
                onCheckedChange = if (editingEnabled) onEnabledChange else null,
                enabled = editingEnabled,
            )
        },
        bottomAction = if (editingEnabled) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
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
        } else {
            null
        },
        onClick = if (editingEnabled) onEdit else null,
        enabled = editingEnabled,
    ) {
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
            if (server.headers().isNotEmpty()) append(" · ${server.headers().size} 个请求头")
        }
        Text(
            detail,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
