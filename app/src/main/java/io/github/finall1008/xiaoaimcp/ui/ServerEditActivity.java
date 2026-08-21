package io.github.finall1008.xiaoaimcp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.finall1008.xiaoaimcp.BridgeApplication;
import io.github.finall1008.xiaoaimcp.config.McpConfigValidator;
import io.github.finall1008.xiaoaimcp.config.McpServer;
import io.github.finall1008.xiaoaimcp.config.RemoteConfigRepository;

public final class ServerEditActivity extends Activity {
    public static final String EXTRA_SERVER_ID = "server_id";

    private String serverId;
    private EditText nameInput;
    private EditText descriptionInput;
    private EditText urlInput;
    private EditText headersInput;
    private Spinner transportInput;
    private Switch enabledInput;
    private CheckBox showHeadersInput;
    private RemoteConfigRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);
        SharedPreferences preferences = BridgeApplication.remotePreferences();
        if (preferences == null) {
            new AlertDialog.Builder(this)
                    .setTitle("API 102 服务未连接")
                    .setMessage("无法读取或保存服务器配置。")
                    .setPositiveButton("关闭", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }
        repository = new RemoteConfigRepository(preferences);
        buildUi();
        if (serverId != null) {
            loadExisting();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiSupport.dp(this, 20), UiSupport.dp(this, 20),
                UiSupport.dp(this, 20), UiSupport.dp(this, 28));
        scroll.addView(root, UiSupport.matchWrap());

        root.addView(UiSupport.title(this,
                serverId == null ? "添加 MCP 服务器" : "编辑 MCP 服务器"), UiSupport.matchWrap());
        TextView hint = UiSupport.text(this,
                "HTTP 对应 Streamable HTTP；SSE 对应旧式 Server-Sent Events。配置保存后会通知超级小爱在线重载。",
                13);
        root.addView(hint, UiSupport.matchWrap());

        nameInput = UiSupport.labeledEdit(root, "名称", "例如 my-search");
        descriptionInput = UiSupport.labeledEdit(root, "说明（可选）", "此服务器提供什么能力");
        urlInput = UiSupport.labeledEdit(root, "服务器 URL", "https://example.com/mcp");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        TextView transportLabel = UiSupport.text(this, "传输类型", 13);
        transportLabel.setPadding(0, UiSupport.dp(this, 12), 0, 0);
        root.addView(transportLabel, UiSupport.matchWrap());
        transportInput = new Spinner(this);
        transportInput.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"http", "sse"}));
        root.addView(transportInput, UiSupport.matchWrap());

        enabledInput = new Switch(this);
        enabledInput.setText("启用服务器");
        enabledInput.setChecked(true);
        enabledInput.setPadding(0, UiSupport.dp(this, 12), 0, UiSupport.dp(this, 6));
        root.addView(enabledInput, UiSupport.matchWrap());

        headersInput = UiSupport.labeledEdit(root, "请求头（每行 Name: Value）",
                "Authorization: Bearer token\nX-API-Key: secret");
        headersInput.setSingleLine(false);
        headersInput.setMinLines(4);
        headersInput.setGravity(android.view.Gravity.TOP);
        headersInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        headersInput.setTransformationMethod(PasswordTransformationMethod.getInstance());

        showHeadersInput = new CheckBox(this);
        showHeadersInput.setText("显示请求头值");
        showHeadersInput.setOnCheckedChangeListener((button, checked) -> {
            int selection = headersInput.getSelectionStart();
            headersInput.setTransformationMethod(
                    checked ? null : PasswordTransformationMethod.getInstance());
            headersInput.setSelection(Math.max(0, selection));
        });
        root.addView(showHeadersInput, UiSupport.matchWrap());

        Button save = new Button(this);
        save.setText("保存并应用");
        save.setAllCaps(false);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        saveParams.topMargin = UiSupport.dp(this, 16);
        root.addView(save, saveParams);
        setContentView(scroll);
    }

    private void loadExisting() {
        try {
            McpServer server = repository.findById(serverId);
            if (server == null) {
                throw new IllegalStateException("服务器不存在或已删除");
            }
            nameInput.setText(server.name());
            descriptionInput.setText(server.description());
            urlInput.setText(server.url());
            transportInput.setSelection(server.transport().equals("sse") ? 1 : 0);
            enabledInput.setChecked(server.enabled());
            headersInput.setText(formatHeaders(server.headers()));
        } catch (Exception error) {
            showError(error);
        }
    }

    private void save() {
        try {
            McpServer server = new McpServer(
                    serverId == null ? UUID.randomUUID().toString() : serverId,
                    nameInput.getText().toString(),
                    descriptionInput.getText().toString(),
                    urlInput.getText().toString(),
                    transportInput.getSelectedItem().toString(),
                    enabledInput.isChecked(),
                    parseHeaders(headersInput.getText().toString())
            );
            McpConfigValidator.validate(server);
            repository.upsert(server);
            finish();
        } catch (Exception error) {
            showError(error);
        }
    }

    static Map<String, String> parseHeaders(String text) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        String normalized = text == null ? "" : text.replace("\r\n", "\n");
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("请求头每行必须使用 Name: Value 格式");
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (headers.containsKey(name)) {
                throw new IllegalArgumentException("请求头名称重复：" + name);
            }
            headers.put(name, value);
        }
        return headers;
    }

    private static String formatHeaders(Map<String, String> headers) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return builder.toString();
    }

    private void showError(Throwable error) {
        String message = error.getMessage();
        new AlertDialog.Builder(this)
                .setTitle("无法保存")
                .setMessage(message == null || message.isBlank()
                        ? error.getClass().getSimpleName() : message)
                .setPositiveButton("确定", null)
                .show();
    }
}
