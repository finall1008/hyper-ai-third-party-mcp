package io.github.finall1008.xiaoaimcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.filepolicy.FileAccessRule
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyCodec
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyConfig
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyRepository
import io.github.finall1008.xiaoaimcp.filepolicy.MutationConfirmationPolicy
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class FilePolicyActivity : ComponentActivity() {
    private var repository: FilePolicyRepository? = null
    private var config by mutableStateOf(FilePolicyConfig.disabled())
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme(this) {
                FilePolicyScreen(
                    config = config,
                    editingEnabled = repository != null,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                    onBack = ::finish,
                    onEnabledChange = { enabled ->
                        save(FilePolicyConfig(enabled, config.rules()))
                    },
                    onSaveRule = ::saveRule,
                    onDeleteRule = ::deleteRule,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val preferences = BridgeApplication.remotePreferences()
        if (preferences == null) {
            repository = null
            config = FilePolicyConfig.disabled()
            errorMessage = "API 102 服务未连接"
            return
        }
        repository = FilePolicyRepository(preferences)
        try {
            config = requireNotNull(repository).load()
        } catch (error: RuntimeException) {
            config = FilePolicyConfig.disabled()
            errorMessage = "文件策略损坏：${safeMessage(error)}"
        }
    }

    private fun saveRule(index: Int, draft: RuleDraft): String? {
        return try {
            validateRuleFlags(draft)
            val rule = draft.toRule()
            val rules = config.rules().toMutableList()
            rules.forEachIndexed { current, configured ->
                require(current == index || configured.path() != rule.path()) {
                    "该目录规则已存在"
                }
            }
            if (index < 0) rules.add(rule) else rules[index] = rule
            saveOrThrow(FilePolicyConfig(config.enabled(), rules))
            null
        } catch (error: RuntimeException) {
            safeMessage(error)
        }
    }

    private fun deleteRule(index: Int) {
        try {
            val rules = config.rules().toMutableList()
            rules.removeAt(index)
            saveOrThrow(FilePolicyConfig(config.enabled(), rules))
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    private fun save(updated: FilePolicyConfig) {
        try {
            saveOrThrow(updated)
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    private fun saveOrThrow(updated: FilePolicyConfig) {
        (repository ?: error("API 102 服务未连接")).save(updated)
        config = updated
    }
}

internal data class RuleDraft(
    val path: String = "/sdcard/Download",
    val allowMutation: Boolean = true,
    val allowLockscreenRead: Boolean = false,
    val allowLockscreenMutation: Boolean = false,
    val allowBackgroundMutation: Boolean = false,
    val allowRecursiveDelete: Boolean = false,
    val confirmationPolicy: MutationConfirmationPolicy = MutationConfirmationPolicy.ASK_EVERY_TIME,
) {
    fun toRule(): FileAccessRule {
        return FileAccessRule(
            FilePolicyCodec.normalizeConfiguredPath(path),
            allowMutation,
            allowLockscreenRead,
            allowLockscreenMutation,
            allowBackgroundMutation,
            allowRecursiveDelete,
            confirmationPolicy,
        )
    }

    companion object {
        fun from(rule: FileAccessRule): RuleDraft {
            return RuleDraft(
                path = rule.path(),
                allowMutation = rule.allowMutation(),
                allowLockscreenRead = rule.allowLockscreenRead(),
                allowLockscreenMutation = rule.allowLockscreenMutation(),
                allowBackgroundMutation = rule.allowBackgroundMutation(),
                allowRecursiveDelete = rule.allowRecursiveDelete(),
                confirmationPolicy = rule.confirmationPolicy(),
            )
        }
    }
}

internal fun validateRuleFlags(draft: RuleDraft) {
    require(
        draft.allowMutation ||
            !(draft.allowLockscreenMutation ||
                draft.allowBackgroundMutation ||
                draft.allowRecursiveDelete),
    ) { "锁屏删改、后台删改和锁屏递归删除需先允许删改既有文件" }
    require(!draft.allowRecursiveDelete || draft.allowLockscreenMutation) {
        "锁屏递归删除需先允许锁屏新建及删改"
    }
    require(
        draft.confirmationPolicy == MutationConfirmationPolicy.ASK_EVERY_TIME ||
            draft.allowMutation,
    ) { "自动允许确认需先允许删改既有文件" }
    require(
        draft.confirmationPolicy != MutationConfirmationPolicy.BACKGROUND_AUTOMATIC ||
            draft.allowBackgroundMutation,
    ) { "后台自动允许需先允许后台/定时 Agent 删改" }
}

private data class RuleEditor(val index: Int, val draft: RuleDraft)

@Composable
private fun FilePolicyScreen(
    config: FilePolicyConfig,
    editingEnabled: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSaveRule: (Int, RuleDraft) -> String?,
    onDeleteRule: (Int) -> Unit,
) {
    var editor by remember { mutableStateOf<RuleEditor?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Int, FileAccessRule>?>(null) }
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "文件访问权限",
                subtitle = "按目录精确授权超级小爱文件能力",
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.tertiaryContainer,
                        contentColor = MiuixTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text("安全说明", style = MiuixTheme.textStyles.body1)
                    Text(
                        "仅解除所列 /sdcard 目录的宿主限制。规则采用规范化路径最长匹配；锁屏、后台/定时和锁屏递归删除分别授权。",
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = config.enabled(),
                        onCheckedChange = onEnabledChange,
                        title = "启用目录文件扩权",
                        summary = if (config.rules().isEmpty()) {
                            "尚未配置目录；打开总开关也不会扩权"
                        } else {
                            "仅对下方 ${config.rules().size} 条目录规则生效"
                        },
                        enabled = editingEnabled,
                    )
                }
            }
            item {
                Button(
                    onClick = { editor = RuleEditor(-1, RuleDraft()) },
                    enabled = editingEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("添加目录规则")
                }
            }
            item { SectionLabel("目录规则", Modifier.padding(top = 8.dp)) }
            if (config.rules().isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(24.dp),
                    ) {
                        Text(
                            "尚未配置目录规则",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = config.rules(),
                    key = { _, rule -> rule.path() },
                ) { index, rule ->
                    RuleCard(
                        rule = rule,
                        editingEnabled = editingEnabled,
                        onEdit = { editor = RuleEditor(index, RuleDraft.from(rule)) },
                        onDelete = { pendingDelete = index to rule },
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    RuleEditDialog(
        editor = editor,
        onEditorChange = { editor = it },
        onDismiss = { editor = null },
        onSave = onSaveRule,
    )
    ConfirmDialog(
        show = pendingDelete != null,
        title = "删除目录规则",
        message = pendingDelete?.let { "删除 “${it.second.path()}” 的授权规则？" }.orEmpty(),
        confirmText = "删除",
        onDismiss = { pendingDelete = null },
        onConfirm = {
            pendingDelete?.first?.let(onDeleteRule)
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
private fun RuleCard(
    rule: FileAccessRule,
    editingEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val flags = buildList {
        if (rule.allowMutation()) add("删改既有文件")
        if (rule.allowLockscreenRead()) add("锁屏读取")
        if (rule.allowLockscreenMutation()) add("锁屏删改")
        if (rule.allowBackgroundMutation()) add("后台/定时删改")
        if (rule.allowRecursiveDelete()) add("锁屏递归删除")
        add(confirmationPolicyLabel(rule.confirmationPolicy()))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        onClick = if (editingEnabled) onEdit else null,
    ) {
        Text(
            rule.path(),
            style = MiuixTheme.textStyles.body1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            flags.joinToString(" · "),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (editingEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = "编辑 ${rule.path()}",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "删除 ${rule.path()}",
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleEditDialog(
    editor: RuleEditor?,
    onEditorChange: (RuleEditor) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int, RuleDraft) -> String?,
) {
    var validationMessage by remember(editor?.index) { mutableStateOf<String?>(null) }
    OverlayDialog(
        show = editor != null,
        title = if (editor?.index == -1) "添加目录规则" else "编辑目录规则",
        onDismissRequest = onDismiss,
    ) {
        val current = editor ?: return@OverlayDialog
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(
                value = current.draft.path,
                onValueChange = {
                    validationMessage = null
                    onEditorChange(current.copy(draft = current.draft.copy(path = it)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = "/sdcard/Download",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            validationMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            CheckboxPreference(
                title = "允许删改既有文件",
                checked = current.draft.allowMutation,
                onCheckedChange = {
                    onEditorChange(current.copy(draft = current.draft.copy(allowMutation = it)))
                },
                checkboxLocation = CheckboxLocation.End,
            )
            CheckboxPreference(
                title = "允许锁屏读取",
                checked = current.draft.allowLockscreenRead,
                onCheckedChange = {
                    onEditorChange(
                        current.copy(draft = current.draft.copy(allowLockscreenRead = it)),
                    )
                },
                checkboxLocation = CheckboxLocation.End,
            )
            CheckboxPreference(
                title = "允许锁屏新建及删改",
                checked = current.draft.allowLockscreenMutation,
                onCheckedChange = {
                    onEditorChange(
                        current.copy(draft = current.draft.copy(allowLockscreenMutation = it)),
                    )
                },
                checkboxLocation = CheckboxLocation.End,
            )
            CheckboxPreference(
                title = "允许后台/定时 Agent 删改",
                checked = current.draft.allowBackgroundMutation,
                onCheckedChange = {
                    onEditorChange(
                        current.copy(draft = current.draft.copy(allowBackgroundMutation = it)),
                    )
                },
                checkboxLocation = CheckboxLocation.End,
            )
            CheckboxPreference(
                title = "允许锁屏递归删除目录",
                checked = current.draft.allowRecursiveDelete,
                onCheckedChange = {
                    onEditorChange(
                        current.copy(draft = current.draft.copy(allowRecursiveDelete = it)),
                    )
                },
                checkboxLocation = CheckboxLocation.End,
            )
            SectionLabel("操作确认策略", Modifier.padding(top = 12.dp))
            ConfirmationPolicyChoices(
                selected = current.draft.confirmationPolicy,
                onSelected = {
                    onEditorChange(current.copy(draft = current.draft.copy(confirmationPolicy = it)))
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Button(
                    onClick = {
                        validationMessage = onSave(current.index, current.draft)
                        if (validationMessage == null) onDismiss()
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun ConfirmationPolicyChoices(
    selected: MutationConfirmationPolicy,
    onSelected: (MutationConfirmationPolicy) -> Unit,
) {
    val choices = listOf(
        MutationConfirmationPolicy.ASK_EVERY_TIME to "每次询问",
        MutationConfirmationPolicy.BACKGROUND_AUTOMATIC to "仅后台/定时自动允许（推荐）",
        MutationConfirmationPolicy.ALL_AGENTS_AUTOMATIC to "所有 Agent 自动允许",
    )
    choices.forEach { (policy, label) ->
        RadioButtonPreference(
            title = label,
            selected = selected == policy,
            onClick = { onSelected(policy) },
            radioButtonLocation = RadioButtonLocation.End,
        )
    }
}

private fun confirmationPolicyLabel(policy: MutationConfirmationPolicy): String {
    return when (policy) {
        MutationConfirmationPolicy.ASK_EVERY_TIME -> "确认：每次询问"
        MutationConfirmationPolicy.BACKGROUND_AUTOMATIC -> "确认：仅后台自动"
        MutationConfirmationPolicy.ALL_AGENTS_AUTOMATIC -> "确认：全部自动"
    }
}
