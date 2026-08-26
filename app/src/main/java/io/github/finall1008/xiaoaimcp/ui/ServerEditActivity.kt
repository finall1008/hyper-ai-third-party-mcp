package io.github.finall1008.xiaoaimcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.config.McpConfigValidator
import io.github.finall1008.xiaoaimcp.config.McpServer
import io.github.finall1008.xiaoaimcp.config.RemoteConfigRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.LinkedHashMap
import java.util.UUID

class ServerEditActivity : ComponentActivity() {
    private val serverId: String? by lazy { intent.getStringExtra(EXTRA_SERVER_ID) }
    private var repository: RemoteConfigRepository? = null
    private var form by mutableStateOf(ServerForm())
    private var errorMessage by mutableStateOf<String?>(null)
    private var fatalMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = BridgeApplication.remotePreferences()
        if (preferences == null) {
            fatalMessage = "无法读取或保存服务器配置。"
        } else {
            repository = RemoteConfigRepository(preferences)
            if (serverId != null) loadExisting()
        }
        setContent {
            BridgeTheme(this) {
                ServerEditScreen(
                    editing = serverId != null,
                    form = form,
                    onFormChange = { form = it },
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

    private fun loadExisting() {
        try {
            val server = repository?.findById(serverId)
                ?: error("服务器不存在或已删除")
            form = ServerForm(
                name = server.name(),
                description = server.description(),
                url = server.url(),
                transportIndex = if (server.transport() == "sse") 1 else 0,
                enabled = server.enabled(),
                headers = formatHeaders(server.headers()),
            )
        } catch (error: Exception) {
            errorMessage = safeMessage(error)
        }
    }

    private fun save() {
        try {
            val server = McpServer(
                serverId ?: UUID.randomUUID().toString(),
                form.name,
                form.description,
                form.url,
                TRANSPORTS[form.transportIndex],
                form.enabled,
                parseHeaders(form.headers),
            )
            McpConfigValidator.validate(server)
            (repository ?: error("API 102 服务未连接")).upsert(server)
            finish()
        } catch (error: Exception) {
            errorMessage = safeMessage(error)
        }
    }

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        private val TRANSPORTS = listOf("http", "sse")

        @JvmStatic
        internal fun parseHeaders(text: String?): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            val normalized = text.orEmpty().replace("\r\n", "\n")
            normalized.split('\n').forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val separator = line.indexOf(':')
                require(separator > 0) { "请求头每行必须使用 Name: Value 格式" }
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(!headers.containsKey(name)) { "请求头名称重复：$name" }
                headers[name] = value
            }
            return headers
        }

        private fun formatHeaders(headers: Map<String, String>): String {
            return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }
}

private data class ServerForm(
    val name: String = "",
    val description: String = "",
    val url: String = "",
    val transportIndex: Int = 0,
    val enabled: Boolean = true,
    val headers: String = "",
    val showHeaders: Boolean = false,
)

@Composable
private fun ServerEditScreen(
    editing: Boolean,
    form: ServerForm,
    onFormChange: (ServerForm) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    fatalMessage: String?,
    onDismissFatal: () -> Unit,
) {
    BridgePageScaffold(
        title = if (editing) "编辑 MCP 服务器" else "添加 MCP 服务器",
        onBack = onBack,
        bottomBar = {
            BridgeSaveBar(
                text = "保存并应用",
                enabled = fatalMessage == null,
                onClick = onSave,
            )
        },
    ) { padding, scrollBehavior ->
        BridgePageList(
            scaffoldPadding = padding,
            scrollBehavior = scrollBehavior,
        ) {
            item { SectionLabel("基本信息") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(
                            value = form.name,
                            onValueChange = { onFormChange(form.copy(name = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "名称，例如 my-search",
                            singleLine = true,
                        )
                        TextField(
                            value = form.description,
                            onValueChange = { onFormChange(form.copy(description = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "说明（可选）",
                            minLines = 3,
                            maxLines = 6,
                        )
                        TextField(
                            value = form.url,
                            onValueChange = { onFormChange(form.copy(url = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "服务器 URL",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                }
            }
            item { SectionLabel("连接设置", Modifier.padding(top = 8.dp)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        items = listOf("http", "sse"),
                        selectedIndex = form.transportIndex,
                        title = "传输类型",
                        summary = "HTTP 为 Streamable HTTP；SSE 为旧式连接",
                        onSelectedIndexChange = {
                            onFormChange(form.copy(transportIndex = it))
                        },
                    )
                    SwitchPreference(
                        checked = form.enabled,
                        onCheckedChange = { onFormChange(form.copy(enabled = it)) },
                        title = "启用服务器",
                        summary = "关闭后保留配置，但不会向超级小爱注册工具",
                    )
                }
            }
            item { SectionLabel("请求头", Modifier.padding(top = 8.dp)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(
                        "每行使用 Name: Value 格式",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = form.headers,
                        onValueChange = { onFormChange(form.copy(headers = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Authorization: Bearer token\nX-API-Key: secret",
                        minLines = 4,
                        maxLines = 8,
                        visualTransformation = if (form.showHeaders) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    )
                    CheckboxPreference(
                        title = "显示请求头值",
                        checked = form.showHeaders,
                        onCheckedChange = { onFormChange(form.copy(showHeaders = it)) },
                        checkboxLocation = CheckboxLocation.End,
                    )
                }
            }
        }
        MessageDialog(
            message = errorMessage,
            title = "无法保存",
            onDismiss = onDismissError,
        )
        MessageDialog(
            message = fatalMessage,
            title = "API 102 服务未连接",
            onDismiss = onDismissFatal,
        )
    }
}
