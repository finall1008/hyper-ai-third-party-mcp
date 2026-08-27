package io.github.finall1008.xiaoaimcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.prompt.PromptPatch
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchCodec
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchConfig
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchRepository
import io.github.finall1008.xiaoaimcp.prompt.PromptLineDiff
import io.github.finall1008.xiaoaimcp.prompt.InstalledPromptPreviewLoader
import io.github.finall1008.xiaoaimcp.prompt.PromptPreviewDocument
import io.github.finall1008.xiaoaimcp.prompt.PromptTargetType
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

class PromptPatchActivity : ComponentActivity() {
    private var repository: PromptPatchRepository? = null
    private var config by mutableStateOf(PromptPatchConfig.defaults())
    private var previewDocuments by mutableStateOf<List<PromptPreviewDocument>>(emptyList())
    private var previewError by mutableStateOf<String?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private val previewLoader by lazy { InstalledPromptPreviewLoader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme(this) {
                PromptPatchScreen(
                    config = config,
                    previewDocuments = previewDocuments,
                    previewError = previewError,
                    editingEnabled = repository != null,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                    onBack = ::finish,
                    onEnabledChange = { save(configWith(enabled = it)) },
                    onReloadPreview = ::reloadPreview,
                    onSavePatch = ::savePatch,
                    onPatchEnabledChange = ::setPatchEnabled,
                    onDeletePatch = ::deletePatch,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val preferences = BridgeApplication.remotePreferences()
        if (preferences == null) {
            repository = null
            config = PromptPatchConfig.defaults()
            reloadPreview()
            errorMessage = "API 102 服务未连接"
            return
        }
        repository = PromptPatchRepository(preferences)
        try {
            config = requireNotNull(repository).load()
            reloadPreview()
        } catch (error: RuntimeException) {
            config = PromptPatchConfig.defaults()
            reloadPreview()
            errorMessage = "Prompt 补丁配置损坏：${safeMessage(error)}"
        }
    }

    private fun configWith(
        enabled: Boolean = config.enabled(),
        patches: List<PromptPatch> = config.patches(),
    ) = PromptPatchConfig(enabled, patches)

    private fun savePatch(index: Int, draft: PromptPatchDraft): String? {
        return try {
            val patch = draft.toPatch(
                if (index < 0) UUID.randomUUID().toString() else config.patches()[index].id(),
            )
            val patches = config.patches().toMutableList()
            if (index < 0) patches.add(patch) else patches[index] = patch
            saveOrThrow(configWith(patches = patches))
            null
        } catch (error: RuntimeException) {
            safeMessage(error)
        }
    }

    private fun setPatchEnabled(index: Int, enabled: Boolean) {
        try {
            val patches = config.patches().toMutableList()
            patches[index] = patches[index].withEnabled(enabled)
            saveOrThrow(configWith(patches = patches))
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    private fun deletePatch(index: Int) {
        try {
            val patches = config.patches().toMutableList()
            patches.removeAt(index)
            saveOrThrow(configWith(patches = patches))
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    private fun save(updated: PromptPatchConfig) {
        try {
            saveOrThrow(updated)
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    private fun saveOrThrow(updated: PromptPatchConfig) {
        (repository ?: error("API 102 服务未连接")).save(updated)
        config = updated
        reloadPreview()
    }

    private fun reloadPreview() {
        try {
            previewDocuments = previewLoader.load(config)
            previewError = null
        } catch (error: RuntimeException) {
            previewDocuments = emptyList()
            previewError = "无法读取超级小爱 Prompt：${safeMessage(error)}"
        }
    }
}

internal data class PromptPatchDraft(
    val enabled: Boolean = true,
    val targetType: PromptTargetType = PromptTargetType.AGENT_PROMPT,
    val agentId: String = "*",
    val fileName: String = "tool_selection_rules.md",
    val findText: String = "",
    val replacementText: String = "",
) {
    fun toPatch(id: String): PromptPatch {
        val patch = PromptPatch(
            id,
            enabled,
            targetType,
            agentId.trim(),
            fileName.trim(),
            findText,
            replacementText,
        )
        PromptPatchCodec.validatePatch(patch)
        return patch
    }

    companion object {
        fun from(patch: PromptPatch) = PromptPatchDraft(
            patch.enabled(),
            patch.targetType(),
            patch.agentId(),
            patch.fileName(),
            patch.findText(),
            patch.replacementText(),
        )
    }
}

private data class PromptPatchEditor(val index: Int, val draft: PromptPatchDraft)

@Composable
private fun PromptPatchScreen(
    config: PromptPatchConfig,
    previewDocuments: List<PromptPreviewDocument>,
    previewError: String?,
    editingEnabled: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onReloadPreview: () -> Unit,
    onSavePatch: (Int, PromptPatchDraft) -> String?,
    onPatchEnabledChange: (Int, Boolean) -> Unit,
    onDeletePatch: (Int) -> Unit,
) {
    var editor by remember { mutableStateOf<PromptPatchEditor?>(null) }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    BridgePageScaffold(
        title = "System Prompt 补丁",
        onBack = onBack,
    ) { padding, scrollBehavior ->
        BridgePageList(
            scaffoldPadding = padding,
            scrollBehavior = scrollBehavior,
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text("生效说明", style = MiuixTheme.textStyles.body1)
                    Text(
                        "补丁在超级小爱读取 Agent、工具或记忆 Prompt 时应用。查找原文必须恰好匹配一次，否则跳过；保存后请重启超级小爱，避免继续使用已缓存的工具说明或会话头。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "启用 Prompt 补丁",
                        summary = "关闭时使用超级小爱原始 Prompt",
                        checked = config.enabled(),
                        onCheckedChange = onEnabledChange,
                        startAction = { PreferenceIcon(MiuixIcons.Settings) },
                        enabled = editingEnabled,
                    )
                    ArrowPreference(
                        title = "System Prompt 变更预览",
                        summary = previewSummary(previewDocuments, previewError),
                        startAction = { PreferenceIcon(MiuixIcons.ListView) },
                        onClick = {
                            onReloadPreview()
                            showPreview = true
                        },
                    )
                    ArrowPreference(
                        title = "添加自定义补丁",
                        summary = "新增一条精确替换规则",
                        startAction = { PreferenceIcon(MiuixIcons.Add) },
                        enabled = editingEnabled,
                        onClick = { editor = PromptPatchEditor(-1, PromptPatchDraft()) },
                    )
                }
            }
            item { SectionLabel("Prompt 补丁规则", Modifier.padding(top = 4.dp)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (config.patches().isEmpty()) {
                        BasicComponent(
                            title = "尚未添加补丁规则",
                            summary = "使用上方入口添加第一条规则",
                            startAction = { PreferenceIcon(MiuixIcons.File) },
                            enabled = false,
                        )
                    } else {
                        config.patches().forEachIndexed { index, patch ->
                            PromptPatchRow(
                                patch,
                                editingEnabled,
                                { editor = PromptPatchEditor(index, PromptPatchDraft.from(patch)) },
                                { onPatchEnabledChange(index, it) },
                                { pendingDelete = index },
                            )
                        }
                    }
                }
            }
        }
        PromptPatchEditDialog(editor, { editor = it }, { editor = null }, onSavePatch)
        PromptDiffDialog(
            show = showPreview,
            documents = previewDocuments,
            previewError = previewError,
            onDismiss = { showPreview = false },
        )
        ConfirmDialog(
            show = pendingDelete != null,
            title = "删除 Prompt 补丁",
            message = "删除该精确替换规则？",
            confirmText = "删除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete?.let(onDeletePatch)
                pendingDelete = null
            },
        )
        MessageDialog(errorMessage, "操作失败", onDismissError)
    }
}

@Composable
private fun PromptDiffDialog(
    show: Boolean,
    documents: List<PromptPreviewDocument>,
    previewError: String?,
    onDismiss: () -> Unit,
) {
    var selectedIndex by remember(show, documents) {
        mutableStateOf(if (documents.size == 1) 0 else -1)
    }
    val selected = documents.getOrNull(selectedIndex)
    val lines = remember(selected) {
        selected?.takeIf { it.available() }?.let {
            PromptLineDiff.calculate(it.originalText(), it.patchedText())
        }.orEmpty()
    }
    WindowDialog(
        show = show,
        title = "System Prompt 变更预览",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp)) {
            when {
                previewError != null -> {
                    Text(
                        previewError,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                documents.isEmpty() -> {
                    Text(
                        "没有指向可预览 Prompt 文件的已启用规则。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                selected == null -> {
                    Text(
                        "选择要查看的完整 Prompt 文件",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LazyColumn(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(documents) { index, document ->
                            PromptPreviewTargetCard(document) { selectedIndex = index }
                        }
                    }
                }
                !selected.available() -> {
                    Text(
                        previewTargetLabel(selected),
                        style = MiuixTheme.textStyles.body1,
                    )
                    Text(
                        selected.error(),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                else -> {
                    Text(
                        "${previewTargetLabel(selected)} · " +
                            "${selected.appliedPatchIds().size} 条已应用，" +
                            "${selected.skippedPatches().size} 条跳过",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LazyColumn(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        itemsIndexed(lines) { _, line ->
                            FullPromptDiffLine(line)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                if (documents.size > 1 && selected != null) {
                    TextButton(
                        text = "文件列表",
                        onClick = { selectedIndex = -1 },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                }
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun PromptPreviewTargetCard(
    document: PromptPreviewDocument,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(12.dp),
        onClick = onClick,
    ) {
        Text(
            previewTargetLabel(document),
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (document.available()) {
                "${document.appliedPatchIds().size} 条已应用，" +
                    "${document.skippedPatches().size} 条跳过"
            } else {
                document.error()
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (document.available()) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.error
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun previewSummary(
    documents: List<PromptPreviewDocument>,
    previewError: String?,
): String {
    if (previewError != null) return previewError
    if (documents.isEmpty()) return "没有可预览的已启用规则"
    val applied = documents.sumOf { it.appliedPatchIds().size }
    val unavailable = documents.count { !it.available() }
    return buildString {
        append("${documents.size} 个文件 · $applied 条已应用")
        if (unavailable > 0) append(" · $unavailable 个不可读取")
    }
}

@Composable
private fun FullPromptDiffLine(line: PromptLineDiff.Line) {
    val color = when (line.type()) {
        PromptLineDiff.Type.REMOVED -> MiuixTheme.colorScheme.error
        PromptLineDiff.Type.ADDED -> DIFF_ADDITION_COLOR
        PromptLineDiff.Type.UNCHANGED -> MiuixTheme.colorScheme.onBackground
    }
    val background = when (line.type()) {
        PromptLineDiff.Type.REMOVED, PromptLineDiff.Type.ADDED -> color.copy(alpha = 0.12f)
        PromptLineDiff.Type.UNCHANGED -> Color.Transparent
    }
    val prefix = when (line.type()) {
        PromptLineDiff.Type.REMOVED -> "-"
        PromptLineDiff.Type.ADDED -> "+"
        PromptLineDiff.Type.UNCHANGED -> " "
    }
    val lineNumber = (line.newLine() ?: line.oldLine())?.toString().orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            lineNumber,
            modifier = Modifier.width(38.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            prefix,
            modifier = Modifier.width(18.dp),
            style = MiuixTheme.textStyles.body2,
            color = color,
        )
        Text(
            line.text(),
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body2,
            color = color,
        )
    }
}

private val DIFF_ADDITION_COLOR = Color(0xFF238636)

@Composable
private fun PromptPatchRow(
    patch: PromptPatch,
    editingEnabled: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(
        startAction = { PreferenceIcon(MiuixIcons.File) },
        endActions = {
            Switch(
                checked = patch.enabled(),
                onCheckedChange = if (editingEnabled) onEnabledChange else null,
                enabled = editingEnabled,
            )
        },
        bottomAction = if (editingEnabled) {
            {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onEdit) {
                        Icon(MiuixIcons.Edit, "编辑补丁")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(MiuixIcons.Delete, "删除补丁", tint = MiuixTheme.colorScheme.error)
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
            patchTargetLabel(patch.targetType(), patch.agentId(), patch.fileName()),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            patch.findText().replace('\n', ' ').take(100),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PromptPatchEditDialog(
    editor: PromptPatchEditor?,
    onEditorChange: (PromptPatchEditor) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int, PromptPatchDraft) -> String?,
) {
    var validationMessage by remember(editor?.index) { mutableStateOf<String?>(null) }
    WindowDialog(
        show = editor != null,
        title = if (editor?.index == -1) "添加 Prompt 补丁" else "编辑 Prompt 补丁",
        onDismissRequest = onDismiss,
    ) {
        val current = editor ?: return@WindowDialog
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                CheckboxPreference(
                    title = "启用此补丁",
                    checked = current.draft.enabled,
                    onCheckedChange = { onEditorChange(current.copy(draft = current.draft.copy(enabled = it))) },
                    checkboxLocation = CheckboxLocation.End,
                )
                OverlayDropdownPreference(
                    items = PromptTargetType.entries.map(::targetTypeLabel),
                    selectedIndex = PromptTargetType.entries.indexOf(current.draft.targetType),
                    title = "Prompt 目标类型",
                    summary = targetTypeSummary(current.draft.targetType),
                    onSelectedIndexChange = { selected ->
                        val type = PromptTargetType.entries[selected]
                        onEditorChange(current.copy(draft = current.draft.withTargetType(type)))
                    },
                )
                TextField(
                    value = current.draft.agentId,
                    onValueChange = { onEditorChange(current.copy(draft = current.draft.copy(agentId = it))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = targetIdLabel(current.draft.targetType),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = current.draft.fileName,
                    onValueChange = { onEditorChange(current.copy(draft = current.draft.copy(fileName = it))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = targetPartLabel(current.draft.targetType),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = current.draft.findText,
                    onValueChange = { onEditorChange(current.copy(draft = current.draft.copy(findText = it))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = "精确查找原文",
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = current.draft.replacementText,
                    onValueChange = { onEditorChange(current.copy(draft = current.draft.copy(replacementText = it))) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = "替换内容（留空即删除）",
                )
                validationMessage?.let {
                    Text(it, Modifier.padding(top = 8.dp), color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.footnote1)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                TextButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    "保存",
                    onClick = {
                        validationMessage = onSave(current.index, current.draft)
                        if (validationMessage == null) onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private fun PromptPatchDraft.withTargetType(type: PromptTargetType): PromptPatchDraft {
    return when (type) {
        PromptTargetType.AGENT_PROMPT -> copy(
            targetType = type,
            agentId = "*",
            fileName = "tool_selection_rules.md",
        )
        PromptTargetType.TOOL_PROMPT -> copy(
            targetType = type,
            agentId = "cli",
            fileName = "description",
        )
        PromptTargetType.MEMORY_PROMPT -> copy(
            targetType = type,
            agentId = "memorygate/prompt_query_gate.txt",
            fileName = "systemPrompt",
        )
    }
}

private fun targetTypeLabel(type: PromptTargetType): String = when (type) {
    PromptTargetType.AGENT_PROMPT -> "Agent Prompt"
    PromptTargetType.TOOL_PROMPT -> "工具 Prompt"
    PromptTargetType.MEMORY_PROMPT -> "记忆 Prompt"
}

private fun targetTypeSummary(type: PromptTargetType): String = when (type) {
    PromptTargetType.AGENT_PROMPT -> "Agent ID 与 Prompt 文件"
    PromptTargetType.TOOL_PROMPT -> "工具名与 [[section]]"
    PromptTargetType.MEMORY_PROMPT -> "记忆模板 key 与 systemPrompt/userPrompt"
}

private fun targetIdLabel(type: PromptTargetType): String = when (type) {
    PromptTargetType.AGENT_PROMPT -> "Agent ID（* 表示全部）"
    PromptTargetType.TOOL_PROMPT -> "工具名"
    PromptTargetType.MEMORY_PROMPT -> "记忆 Prompt key"
}

private fun targetPartLabel(type: PromptTargetType): String = when (type) {
    PromptTargetType.AGENT_PROMPT -> "Prompt 文件名"
    PromptTargetType.TOOL_PROMPT -> "工具 Prompt section"
    PromptTargetType.MEMORY_PROMPT -> "记忆 Prompt section"
}

private fun patchTargetLabel(
    type: PromptTargetType,
    targetId: String,
    targetPart: String,
): String = "${targetTypeLabel(type)} · $targetId · $targetPart"

private fun previewTargetLabel(document: PromptPreviewDocument): String = patchTargetLabel(
    document.targetType(),
    document.agentId(),
    document.fileName(),
)
