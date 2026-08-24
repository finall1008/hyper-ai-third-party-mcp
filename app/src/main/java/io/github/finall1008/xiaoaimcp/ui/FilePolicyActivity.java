package io.github.finall1008.xiaoaimcp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.finall1008.xiaoaimcp.BridgeApplication;
import io.github.finall1008.xiaoaimcp.filepolicy.FileAccessRule;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyCodec;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyConfig;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyRepository;
import io.github.finall1008.xiaoaimcp.filepolicy.MutationConfirmationPolicy;

public final class FilePolicyActivity extends Activity {
    private FilePolicyRepository repository;
    private FilePolicyConfig config = FilePolicyConfig.disabled();
    private LinearLayout rulesContainer;
    private Switch enabledSwitch;
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("文件访问权限");
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences preferences = BridgeApplication.remotePreferences();
        if (preferences == null) {
            repository = null;
            config = FilePolicyConfig.disabled();
            enabledSwitch.setEnabled(false);
            Toast.makeText(this, "API 102 服务未连接", Toast.LENGTH_LONG).show();
            render();
            return;
        }
        repository = new FilePolicyRepository(preferences);
        enabledSwitch.setEnabled(true);
        try {
            config = repository.load();
        } catch (RuntimeException error) {
            config = FilePolicyConfig.disabled();
            Toast.makeText(this, "文件策略损坏：" + safeMessage(error), Toast.LENGTH_LONG).show();
        }
        render();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiSupport.dp(this, 20), UiSupport.dp(this, 18),
                UiSupport.dp(this, 20), UiSupport.dp(this, 24));
        root.setBackgroundColor(Color.WHITE);

        root.addView(UiSupport.title(this, "文件访问权限"), UiSupport.matchWrap());
        TextView warning = UiSupport.text(this,
                "仅解除所列 /sdcard 目录的宿主限制。规则采用规范化路径最长匹配；"
                        + "锁屏、后台/定时和锁屏递归删除分别授权。",
                13);
        warning.setTextColor(Color.rgb(150, 75, 45));
        warning.setPadding(0, 6, 0, 12);
        root.addView(warning, UiSupport.matchWrap());

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用目录文件扩权");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (rendering || checked == config.enabled()) {
                return;
            }
            save(new FilePolicyConfig(checked, config.rules()));
        });
        root.addView(enabledSwitch, UiSupport.matchWrap());

        Button add = new Button(this);
        add.setText("添加目录规则");
        add.setAllCaps(false);
        add.setOnClickListener(v -> showRuleDialog(-1, null));
        LinearLayout.LayoutParams addParams = UiSupport.matchWrap();
        addParams.topMargin = UiSupport.dp(this, 10);
        root.addView(add, addParams);

        rulesContainer = new LinearLayout(this);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rulesParams = UiSupport.matchWrap();
        rulesParams.topMargin = UiSupport.dp(this, 12);
        root.addView(rulesContainer, rulesParams);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void render() {
        rendering = true;
        enabledSwitch.setChecked(config.enabled());
        rulesContainer.removeAllViews();
        if (config.rules().isEmpty()) {
            TextView empty = UiSupport.text(this, "尚未配置目录；即使总开关开启也不会扩权。", 14);
            empty.setTextColor(Color.rgb(110, 115, 125));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiSupport.dp(this, 24), 0, UiSupport.dp(this, 24));
            rulesContainer.addView(empty, UiSupport.matchWrap());
        } else {
            for (int index = 0; index < config.rules().size(); index++) {
                rulesContainer.addView(ruleView(index, config.rules().get(index)),
                        UiSupport.matchWrap());
            }
        }
        rendering = false;
    }

    private LinearLayout ruleView(int index, FileAccessRule rule) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiSupport.dp(this, 12), UiSupport.dp(this, 10),
                UiSupport.dp(this, 12), UiSupport.dp(this, 10));
        card.setBackgroundColor(Color.rgb(246, 247, 250));

        TextView path = UiSupport.text(this, rule.path(), 16);
        path.setTextColor(Color.rgb(35, 40, 50));
        card.addView(path, UiSupport.matchWrap());

        List<String> flags = new ArrayList<>();
        if (rule.allowMutation()) flags.add("删改既有文件");
        if (rule.allowLockscreenRead()) flags.add("锁屏读取");
        if (rule.allowLockscreenMutation()) flags.add("锁屏删改");
        if (rule.allowBackgroundMutation()) flags.add("后台/定时删改");
        if (rule.allowRecursiveDelete()) flags.add("锁屏递归删除");
        flags.add(confirmationPolicyLabel(rule.confirmationPolicy()));
        TextView summary = UiSupport.text(this,
                flags.isEmpty() ? "未授予额外能力" : String.join(" · ", flags), 12);
        summary.setTextColor(Color.rgb(90, 95, 108));
        summary.setPadding(0, 4, 0, 6);
        card.addView(summary, UiSupport.matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = new Button(this);
        edit.setText("编辑");
        edit.setAllCaps(false);
        edit.setOnClickListener(v -> showRuleDialog(index, rule));
        actions.addView(edit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button delete = new Button(this);
        delete.setText("删除");
        delete.setAllCaps(false);
        delete.setOnClickListener(v -> confirmDelete(index, rule));
        actions.addView(delete, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(actions, UiSupport.matchWrap());

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(0, 0, 0, UiSupport.dp(this, 8));
        wrapper.addView(card, UiSupport.matchWrap());
        return wrapper;
    }

    private void showRuleDialog(int index, FileAccessRule existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiSupport.dp(this, 20), UiSupport.dp(this, 8),
                UiSupport.dp(this, 20), 0);

        EditText path = new EditText(this);
        path.setHint("/sdcard/Download");
        path.setSingleLine(true);
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        path.setText(existing == null ? "/sdcard/Download" : existing.path());
        form.addView(path, UiSupport.matchWrap());

        CheckBox mutation = checkBox("允许删改既有文件",
                existing == null || existing.allowMutation());
        CheckBox lockRead = checkBox("允许锁屏读取",
                existing != null && existing.allowLockscreenRead());
        CheckBox lockMutation = checkBox("允许锁屏新建及删改",
                existing != null && existing.allowLockscreenMutation());
        CheckBox background = checkBox("允许后台/定时 Agent 删改",
                existing != null && existing.allowBackgroundMutation());
        CheckBox recursive = checkBox("允许锁屏递归删除目录",
                existing != null && existing.allowRecursiveDelete());
        form.addView(mutation, UiSupport.matchWrap());
        form.addView(lockRead, UiSupport.matchWrap());
        form.addView(lockMutation, UiSupport.matchWrap());
        form.addView(background, UiSupport.matchWrap());
        form.addView(recursive, UiSupport.matchWrap());

        TextView confirmationTitle = UiSupport.text(this, "操作确认策略", 15);
        confirmationTitle.setPadding(0, UiSupport.dp(this, 10), 0, 0);
        form.addView(confirmationTitle, UiSupport.matchWrap());
        RadioGroup confirmation = new RadioGroup(this);
        confirmation.setOrientation(RadioGroup.VERTICAL);
        addConfirmationChoice(confirmation, "每次询问",
                MutationConfirmationPolicy.ASK_EVERY_TIME);
        addConfirmationChoice(confirmation, "仅后台/定时自动允许（推荐）",
                MutationConfirmationPolicy.BACKGROUND_AUTOMATIC);
        addConfirmationChoice(confirmation, "所有 Agent 自动允许",
                MutationConfirmationPolicy.ALL_AGENTS_AUTOMATIC);
        selectConfirmationPolicy(confirmation, existing == null
                ? MutationConfirmationPolicy.ASK_EVERY_TIME
                : existing.confirmationPolicy());
        form.addView(confirmation, UiSupport.matchWrap());

        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加目录规则" : "编辑目录规则")
                .setView(formScroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        MutationConfirmationPolicy confirmationPolicy =
                                selectedConfirmationPolicy(confirmation);
                        validateRuleFlags(mutation, lockMutation, background, recursive,
                                confirmationPolicy);
                        FileAccessRule rule = new FileAccessRule(
                                FilePolicyCodec.normalizeConfiguredPath(path.getText().toString()),
                                mutation.isChecked(),
                                lockRead.isChecked(),
                                lockMutation.isChecked(),
                                background.isChecked(),
                                recursive.isChecked(),
                                confirmationPolicy
                        );
                        List<FileAccessRule> rules = new ArrayList<>(config.rules());
                        for (int current = 0; current < rules.size(); current++) {
                            if (current != index && rules.get(current).path().equals(rule.path())) {
                                throw new IllegalArgumentException("该目录规则已存在");
                            }
                        }
                        if (index < 0) {
                            rules.add(rule);
                        } else {
                            rules.set(index, rule);
                        }
                        save(new FilePolicyConfig(config.enabled(), rules));
                        dialog.dismiss();
                    } catch (RuntimeException error) {
                        path.setError(safeMessage(error));
                    }
                }));
        dialog.show();
    }

    private static void validateRuleFlags(
            CheckBox mutation,
            CheckBox lockMutation,
            CheckBox background,
            CheckBox recursive,
            MutationConfirmationPolicy confirmationPolicy
    ) {
        if (!mutation.isChecked()
                && (lockMutation.isChecked()
                || background.isChecked()
                || recursive.isChecked())) {
            throw new IllegalArgumentException("锁屏删改、后台删改和锁屏递归删除需先允许删改既有文件");
        }
        if (recursive.isChecked() && !lockMutation.isChecked()) {
            throw new IllegalArgumentException("锁屏递归删除需先允许锁屏新建及删改");
        }
        if (confirmationPolicy != MutationConfirmationPolicy.ASK_EVERY_TIME
                && !mutation.isChecked()) {
            throw new IllegalArgumentException("自动允许确认需先允许删改既有文件");
        }
        if (confirmationPolicy == MutationConfirmationPolicy.BACKGROUND_AUTOMATIC
                && !background.isChecked()) {
            throw new IllegalArgumentException("后台自动允许需先允许后台/定时 Agent 删改");
        }
    }

    private void addConfirmationChoice(
            RadioGroup group,
            String label,
            MutationConfirmationPolicy policy
    ) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTag(policy);
        group.addView(button, UiSupport.matchWrap());
    }

    private static void selectConfirmationPolicy(
            RadioGroup group,
            MutationConfirmationPolicy policy
    ) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof RadioButton button && button.getTag() == policy) {
                group.check(button.getId());
                return;
            }
        }
    }

    private static MutationConfirmationPolicy selectedConfirmationPolicy(RadioGroup group) {
        View selected = group.findViewById(group.getCheckedRadioButtonId());
        if (selected != null && selected.getTag() instanceof MutationConfirmationPolicy policy) {
            return policy;
        }
        throw new IllegalArgumentException("请选择操作确认策略");
    }

    private static String confirmationPolicyLabel(MutationConfirmationPolicy policy) {
        return switch (policy) {
            case ASK_EVERY_TIME -> "确认：每次询问";
            case BACKGROUND_AUTOMATIC -> "确认：仅后台自动";
            case ALL_AGENTS_AUTOMATIC -> "确认：全部自动";
        };
    }

    private CheckBox checkBox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setChecked(checked);
        return box;
    }

    private void confirmDelete(int index, FileAccessRule rule) {
        new AlertDialog.Builder(this)
                .setTitle("删除目录规则")
                .setMessage("删除 “" + rule.path() + "” 的授权规则？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    List<FileAccessRule> rules = new ArrayList<>(config.rules());
                    rules.remove(index);
                    save(new FilePolicyConfig(config.enabled(), rules));
                })
                .show();
    }

    private void save(FilePolicyConfig updated) {
        if (repository == null) {
            Toast.makeText(this, "API 102 服务未连接", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            repository.save(updated);
            config = updated;
            render();
        } catch (RuntimeException error) {
            Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
