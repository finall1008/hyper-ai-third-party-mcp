package io.github.finall1008.xiaoaimcp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.BridgeContract
import io.github.finall1008.xiaoaimcp.BuildConfig
import io.github.finall1008.xiaoaimcp.R
import io.github.finall1008.xiaoaimcp.TargetVersionPolicy
import io.github.finall1008.xiaoaimcp.config.LegacyMcpConfigRepository
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity :
    ComponentActivity(),
    BridgeApplication.ServiceStateListener,
    RestartStateListener {
    private var state by mutableStateOf(MainUiState())
    private var errorMessage by mutableStateOf<String?>(null)
    private var preferences: SharedPreferences? = null
    private var legacyMcpRepository: LegacyMcpConfigRepository? = null
    private var restartInFlight by mutableStateOf(false)
    private var legacyMigrationNoticePending by mutableStateOf(false)
    private val agentTraceController by lazy { AgentTraceUiController(this) }

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
                    onOpenFilePolicy = {
                        startActivity(Intent(this, FilePolicyActivity::class.java))
                    },
                    onOpenPromptPatches = {
                        startActivity(Intent(this, PromptPatchActivity::class.java))
                    },
                    agentTraceController = agentTraceController,
                    onOpenFirstOutputTimeout = {
                        startActivity(Intent(this, FirstOutputTimeoutActivity::class.java))
                    },
                    restartInFlight = restartInFlight,
                    onRestartXiaoAi = ::restartXiaoAi,
                    onOpenReleases = ::openReleases,
                    showLegacyMcpMigrationNotice = legacyMigrationNoticePending,
                    onDismissLegacyMcpMigrationNotice = ::dismissLegacyMcpMigrationNotice,
                    onCopyLegacyMcpConfig = ::copyLegacyMcpConfig,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BridgeApplication.addServiceStateListener(this, true)
        XiaoAiRootRestarter.addListener(this)
        agentTraceController.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        agentTraceController.resume()
    }

    override fun onStop() {
        XiaoAiRootRestarter.removeListener(this)
        BridgeApplication.removeServiceStateListener(this)
        agentTraceController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        agentTraceController.destroy()
        super.onDestroy()
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

        val (targetStatus, targetReady, nativeMcpAvailable) = targetStatus()
        preferences = if (serviceReady) {
            BridgeApplication.remotePreferences()
        } else {
            null
        }
        legacyMcpRepository = preferences?.let(::LegacyMcpConfigRepository)
        val legacyServers = try {
            legacyMcpRepository?.load().orEmpty()
        } catch (error: Exception) {
            frameworkStatus = StatusUi(
                StatusLevel.ERROR,
                "旧版 MCP 配置损坏，无法导出：${safeMessage(error)}",
            )
            emptyList()
        }
        legacyMigrationNoticePending = legacyServers.isNotEmpty() &&
            nativeMcpAvailable &&
            preferences?.getBoolean(
                BridgeContract.PREF_LEGACY_MCP_MIGRATION_NOTICE_SEEN,
                false,
            ) != true
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
            firstOutputTimeout = preferences?.let {
                FirstOutputTimeoutRepository(it).load()
            } ?: FirstOutputTimeoutConfig.hostDefault(),
            filePolicySummary = filePolicySummary,
            legacyMcpServerCount = legacyServers.size,
            nativeMcpAvailable = nativeMcpAvailable,
        )
    }

    @Suppress("DEPRECATION")
    private fun targetStatus(): Triple<StatusUi, Boolean, Boolean> {
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
            val nativeMcp = TargetVersionPolicy.hasNativeMcp(versionName)
            when {
                !nativeMcp && supported -> StatusUi(
                    StatusLevel.WARNING,
                    "$version · 增强功能可用；原生 MCP 需 ${BridgeContract.NATIVE_MCP_VERSION_NAME}+",
                ).withReadiness(true, false)
                reference -> StatusUi(StatusLevel.SUCCESS, "$version · 已验证")
                    .withReadiness(true, true)
                supported -> StatusUi(
                    StatusLevel.WARNING,
                    "$version · 将在目标进程中自动探测兼容性",
                ).withReadiness(true, true)
                else -> {
                    val reason = if (TargetVersionPolicy.parseMajor(versionName).isPresent) {
                        "要求超级小爱 8.0 或更高版本"
                    } else {
                        "无法识别版本号，要求超级小爱 8.0 或更高版本"
                    }
                    StatusUi(StatusLevel.ERROR, "$version · $reason")
                        .withReadiness(false, false)
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            StatusUi(StatusLevel.ERROR, "未安装超级小爱：${BridgeContract.TARGET_PACKAGE}")
                .withReadiness(false, false)
        }
    }

    private fun copyLegacyMcpConfig() {
        try {
            val json = legacyMcpRepository?.exportNativeConfig()
                ?: error("API 102 服务未连接")
            val clipboard = getSystemService(ClipboardManager::class.java)
                ?: error("系统剪贴板不可用")
            clipboard.setPrimaryClip(ClipData.newPlainText("超级小爱 MCP 配置", json))
            Toast.makeText(
                this,
                "已复制，请粘贴到超级小爱 → 设置 → MCP 服务",
                Toast.LENGTH_LONG,
            ).show()
            dismissLegacyMcpMigrationNotice()
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

    private fun dismissLegacyMcpMigrationNotice() {
        preferences?.edit()
            ?.putBoolean(BridgeContract.PREF_LEGACY_MCP_MIGRATION_NOTICE_SEEN, true)
            ?.apply()
        legacyMigrationNoticePending = false
    }
}

private data class MainUiState(
    val frameworkStatus: StatusUi = StatusUi(StatusLevel.ERROR, "正在连接 API 102 服务…"),
    val targetStatus: StatusUi = StatusUi(StatusLevel.WARNING, "正在检查超级小爱版本…"),
    val editingEnabled: Boolean = false,
    val firstOutputTimeout: FirstOutputTimeoutConfig =
        FirstOutputTimeoutConfig.hostDefault(),
    val filePolicySummary: String = "正在读取文件权限配置…",
    val legacyMcpServerCount: Int = 0,
    val nativeMcpAvailable: Boolean = false,
)

private data class StatusUi(val level: StatusLevel, val text: String)

private enum class StatusLevel { SUCCESS, WARNING, ERROR }

internal enum class RootPage(val label: String) {
    HOME("首页"),
    TRACE("轨迹"),
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
    onOpenFilePolicy: () -> Unit,
    onOpenPromptPatches: () -> Unit,
    agentTraceController: AgentTraceUiController,
    onOpenFirstOutputTimeout: () -> Unit,
    restartInFlight: Boolean,
    onRestartXiaoAi: () -> Unit,
    onOpenReleases: () -> Unit,
    showLegacyMcpMigrationNotice: Boolean,
    onDismissLegacyMcpMigrationNotice: () -> Unit,
    onCopyLegacyMcpConfig: () -> Unit,
) {
    var showRestartConfirmation by remember { mutableStateOf(false) }
    var selectedPage by rememberSaveable { mutableStateOf(DEFAULT_ROOT_PAGE) }
    val appName = stringResource(R.string.app_name)
    Box(modifier = Modifier.fillMaxSize()) {
        key(selectedPage) {
            when (selectedPage) {
                RootPage.TRACE -> AgentTraceRoute(
                    controller = agentTraceController,
                    onBack = null,
                    extraBottomPadding = 108.dp,
                )
                RootPage.HOME, RootPage.ABOUT -> BridgePageScaffold(
                    title = if (selectedPage == RootPage.HOME) appName else "关于",
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
                    if (selectedPage == RootPage.HOME) {
                        HomePage(
                            state = state,
                            scaffoldPadding = padding,
                            scrollBehavior = scrollBehavior,
                            onOpenFilePolicy = onOpenFilePolicy,
                            onOpenPromptPatches = onOpenPromptPatches,
                            onEditTimeout = onOpenFirstOutputTimeout,
                            onCopyLegacyMcpConfig = onCopyLegacyMcpConfig,
                        )
                    } else {
                        AboutPage(
                            scaffoldPadding = padding,
                            scrollBehavior = scrollBehavior,
                            onOpenReleases = onOpenReleases,
                        )
                    }
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
                    selected = selectedPage == RootPage.TRACE,
                    onClick = { selectedPage = RootPage.TRACE },
                    icon = MiuixIcons.ListView,
                    label = RootPage.TRACE.label,
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
        show = showLegacyMcpMigrationNotice,
        title = "迁移旧版 MCP 配置",
        message = "模块已停用第三方 MCP 注入，检测到旧配置。可以复制为超级小爱原生 JSON；内容可能包含请求头和访问凭据，请勿分享。",
        confirmText = "复制配置",
        onDismiss = onDismissLegacyMcpMigrationNotice,
        onConfirm = onCopyLegacyMcpConfig,
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
    onOpenFilePolicy: () -> Unit,
    onOpenPromptPatches: () -> Unit,
    onEditTimeout: () -> Unit,
    onCopyLegacyMcpConfig: () -> Unit,
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
        if (state.legacyMcpServerCount > 0) {
            item { SectionLabel("旧版 MCP 配置", Modifier.padding(top = 4.dp)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "复制原生 MCP JSON",
                        summary = if (state.nativeMcpAvailable) {
                            "${state.legacyMcpServerCount} 个旧服务器；模块不再注入，请迁移到超级小爱设置"
                        } else {
                            "${state.legacyMcpServerCount} 个旧服务器；升级到 ${BridgeContract.NATIVE_MCP_VERSION_NAME}+ 后迁移"
                        },
                        startAction = { PreferenceIcon(MiuixIcons.Link) },
                        enabled = state.editingEnabled && state.nativeMcpAvailable,
                        onClick = onCopyLegacyMcpConfig,
                    )
                    BasicComponent(
                        title = "迁移位置",
                        summary = "超级小爱 → 设置 → MCP 服务；剪贴板内容可能包含访问凭据",
                        startAction = { PreferenceIcon(MiuixIcons.Info) },
                        enabled = false,
                    )
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

private fun StatusUi.withReadiness(
    targetReady: Boolean,
    nativeMcpAvailable: Boolean,
): Triple<StatusUi, Boolean, Boolean> {
    return Triple(this, targetReady, nativeMcpAvailable)
}
