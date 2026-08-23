package io.github.finall1008.xiaoaimcp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import io.github.finall1008.xiaoaimcp.BridgeApplication;
import io.github.finall1008.xiaoaimcp.BridgeContract;
import io.github.finall1008.xiaoaimcp.R;
import io.github.finall1008.xiaoaimcp.TargetVersionPolicy;
import io.github.finall1008.xiaoaimcp.config.McpServer;
import io.github.finall1008.xiaoaimcp.config.RemoteConfigRepository;
import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity
        implements BridgeApplication.ServiceStateListener {
    private TextView frameworkStatus;
    private TextView targetStatus;
    private TextView emptyView;
    private Button addButton;
    private ServerAdapter adapter;
    private RemoteConfigRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("超级小爱 MCP Bridge");
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BridgeApplication.addServiceStateListener(this, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStop() {
        BridgeApplication.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        runOnUiThread(this::refresh);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiSupport.dp(this, 20), UiSupport.dp(this, 20),
                UiSupport.dp(this, 20), UiSupport.dp(this, 16));
        root.setBackgroundColor(Color.WHITE);

        root.addView(UiSupport.title(this, "超级小爱 MCP Bridge"), UiSupport.matchWrap());
        TextView subtitle = UiSupport.text(this,
                "为超级小爱 8.0 及以上版本注入 Streamable HTTP / SSE MCP 服务器。",
                14);
        subtitle.setTextColor(Color.rgb(90, 95, 108));
        subtitle.setPadding(0, 0, 0, UiSupport.dp(this, 14));
        root.addView(subtitle, UiSupport.matchWrap());

        frameworkStatus = statusView();
        targetStatus = statusView();
        root.addView(frameworkStatus, UiSupport.matchWrap());
        root.addView(targetStatus, UiSupport.matchWrap());

        addButton = new Button(this);
        addButton.setText(R.string.add_server);
        addButton.setAllCaps(false);
        addButton.setOnClickListener(v -> startActivity(
                new Intent(this, ServerEditActivity.class)));
        LinearLayout.LayoutParams buttonParams = UiSupport.matchWrap();
        buttonParams.topMargin = UiSupport.dp(this, 12);
        root.addView(addButton, buttonParams);

        emptyView = UiSupport.text(this, "尚未添加第三方 MCP 服务器", 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Color.rgb(120, 125, 138));
        emptyView.setPadding(0, UiSupport.dp(this, 40), 0, UiSupport.dp(this, 40));

        ListView listView = new ListView(this);
        adapter = new ServerAdapter();
        listView.setAdapter(adapter);
        listView.setDividerHeight(0);
        listView.setEmptyView(emptyView);
        root.addView(emptyView, UiSupport.matchWrap());
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        );
        root.addView(listView, listParams);
        setContentView(root);
    }

    private TextView statusView() {
        TextView view = UiSupport.text(this, "", 13);
        view.setPadding(UiSupport.dp(this, 10), UiSupport.dp(this, 7),
                UiSupport.dp(this, 10), UiSupport.dp(this, 7));
        return view;
    }

    private void refresh() {
        XposedService service = BridgeApplication.service();
        boolean serviceReady = false;
        if (service == null) {
            setStatus(frameworkStatus, false, "API 102 服务未连接：请安装、启用模块并重新打开此页面");
        } else {
            try {
                int api = service.getApiVersion();
                serviceReady = api >= XposedService.API_102;
                setStatus(frameworkStatus, serviceReady,
                        service.getFrameworkName() + " " + service.getFrameworkVersion()
                                + " · API " + api);
            } catch (RuntimeException error) {
                setStatus(frameworkStatus, false, "读取 Xposed 服务失败：" + safeMessage(error));
            }
        }

        boolean targetReady = updateTargetStatus();
        boolean editingEnabled = serviceReady && targetReady;
        addButton.setEnabled(editingEnabled);
        adapter.setEditingEnabled(editingEnabled);

        SharedPreferences preferences = serviceReady ? BridgeApplication.remotePreferences() : null;
        repository = preferences == null ? null : new RemoteConfigRepository(preferences);
        if (repository == null) {
            adapter.replace(List.of());
            return;
        }
        try {
            adapter.replace(repository.load());
        } catch (Exception error) {
            adapter.replace(List.of());
            setStatus(frameworkStatus, false, "MCP 配置损坏：" + safeMessage(error));
        }
    }

    private boolean updateTargetStatus() {
        try {
            @SuppressWarnings("deprecation")
            PackageInfo info = getPackageManager().getPackageInfo(
                    BridgeContract.TARGET_PACKAGE,
                    0
            );
            @SuppressWarnings("deprecation")
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : (info.versionCode & 0xffffffffL);
            String versionName = info.versionName;
            boolean supported = TargetVersionPolicy.isSupported(versionName);
            boolean reference = supported
                    && BridgeContract.REFERENCE_VERSION_NAME.equals(versionName)
                    && code == BridgeContract.REFERENCE_VERSION_CODE;
            String version = "超级小爱 " + String.valueOf(versionName) + " (" + code + ")";
            if (reference) {
                setStatus(targetStatus, StatusLevel.SUCCESS, version + " · 已验证");
            } else if (supported) {
                setStatus(targetStatus, StatusLevel.WARNING,
                        version + " · 将在目标进程中自动探测兼容性");
            } else {
                OptionalInt major = TargetVersionPolicy.parseMajor(versionName);
                String reason = major.isPresent()
                        ? "要求超级小爱 8.0 或更高版本"
                        : "无法识别版本号，要求超级小爱 8.0 或更高版本";
                setStatus(targetStatus, StatusLevel.ERROR, version + " · " + reason);
            }
            return supported;
        } catch (PackageManager.NameNotFoundException error) {
            setStatus(targetStatus, false, "未安装超级小爱：" + BridgeContract.TARGET_PACKAGE);
            return false;
        }
    }

    private void setStatus(TextView view, boolean success, String text) {
        setStatus(view, success ? StatusLevel.SUCCESS : StatusLevel.ERROR, text);
    }

    private void setStatus(TextView view, StatusLevel level, String text) {
        if (level == StatusLevel.SUCCESS) {
            view.setText(getString(R.string.status_success, text));
            view.setTextColor(Color.rgb(27, 125, 75));
            view.setBackgroundColor(Color.rgb(235, 248, 240));
        } else if (level == StatusLevel.WARNING) {
            view.setText(getString(R.string.status_warning, text));
            view.setTextColor(Color.rgb(145, 96, 16));
            view.setBackgroundColor(Color.rgb(255, 247, 226));
        } else {
            view.setText(getString(R.string.status_error, text));
            view.setTextColor(Color.rgb(180, 62, 52));
            view.setBackgroundColor(Color.rgb(253, 239, 237));
        }
    }

    private void edit(McpServer server) {
        Intent intent = new Intent(this, ServerEditActivity.class);
        intent.putExtra(ServerEditActivity.EXTRA_SERVER_ID, server.id());
        startActivity(intent);
    }

    private void delete(McpServer server) {
        new AlertDialog.Builder(this)
                .setTitle("删除服务器")
                .setMessage("确认删除 “" + server.name() + "”？超级小爱中的对应工具会在线注销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        requireRepository().delete(server.id());
                        refresh();
                    } catch (Exception error) {
                        showError(error);
                    }
                })
                .show();
    }

    private RemoteConfigRepository requireRepository() {
        if (repository == null) {
            throw new IllegalStateException("API 102 服务未连接");
        }
        return repository;
    }

    private void showError(Throwable error) {
        Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private final class ServerAdapter extends BaseAdapter {
        private final List<McpServer> servers = new ArrayList<>();
        private boolean editingEnabled;

        void setEditingEnabled(boolean enabled) {
            if (editingEnabled != enabled) {
                editingEnabled = enabled;
                notifyDataSetChanged();
            }
        }

        void replace(List<McpServer> updated) {
            servers.clear();
            servers.addAll(updated);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return servers.size();
        }

        @Override
        public McpServer getItem(int position) {
            return servers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).id().hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            McpServer server = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(UiSupport.dp(MainActivity.this, 12), UiSupport.dp(MainActivity.this, 12),
                    UiSupport.dp(MainActivity.this, 8), UiSupport.dp(MainActivity.this, 12));
            row.setBackgroundColor(Color.rgb(247, 248, 251));

            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView title = UiSupport.text(MainActivity.this, server.name(), 17);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            copy.addView(title, UiSupport.matchWrap());
            String detail = server.transport().toUpperCase(Locale.ROOT) + " · " + server.url()
                    + (server.headers().isEmpty() ? "" : " · " + server.headers().size() + " 个请求头");
            TextView details = UiSupport.text(MainActivity.this, detail, 12);
            details.setTextColor(Color.rgb(100, 105, 118));
            details.setMaxLines(2);
            copy.addView(details, UiSupport.matchWrap());
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            row.addView(copy, copyParams);

            Switch enabled = new Switch(MainActivity.this);
            enabled.setChecked(server.enabled());
            enabled.setContentDescription("启用 " + server.name());
            enabled.setEnabled(editingEnabled);
            if (editingEnabled) {
                enabled.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
                    if (checked == server.enabled()) {
                        return;
                    }
                    try {
                        requireRepository().setEnabled(server.id(), checked);
                        refresh();
                    } catch (Exception error) {
                        button.setOnCheckedChangeListener(null);
                        button.setChecked(server.enabled());
                        showError(error);
                    }
                });
            }
            row.addView(enabled, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            if (editingEnabled) {
                row.setOnClickListener(v -> edit(server));
                row.setOnLongClickListener(v -> {
                    delete(server);
                    return true;
                });
            }

            LinearLayout wrapper = new LinearLayout(MainActivity.this);
            wrapper.setPadding(0, UiSupport.dp(MainActivity.this, 5), 0,
                    UiSupport.dp(MainActivity.this, 5));
            wrapper.addView(row, UiSupport.matchWrap());
            return wrapper;
        }
    }

    private enum StatusLevel {
        SUCCESS,
        WARNING,
        ERROR
    }
}
