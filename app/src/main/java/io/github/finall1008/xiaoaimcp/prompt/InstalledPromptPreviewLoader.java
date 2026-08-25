package io.github.finall1008.xiaoaimcp.prompt;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class InstalledPromptPreviewLoader {
    static final int MAX_PROMPT_BYTES = 1024 * 1024;

    private final PromptAssetSource assets;

    public InstalledPromptPreviewLoader(Context context) {
        this(new PackagePromptAssetSource(context));
    }

    InstalledPromptPreviewLoader(PromptAssetSource assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    public List<PromptPreviewDocument> load(PromptPatchConfig config) {
        Objects.requireNonNull(config, "config");
        PromptPatchCodec.validate(config);
        List<String> installedAgents = null;
        Map<Target, String> targets = new LinkedHashMap<>();
        Map<Target, String> originals = new LinkedHashMap<>();
        for (PromptPatch patch : config.patches()) {
            if (!patch.enabled()) {
                continue;
            }
            if (!patch.agentId().equals("*")) {
                targets.putIfAbsent(new Target(patch.agentId(), patch.fileName()), null);
                continue;
            }
            if (installedAgents == null) {
                installedAgents = listAgentIds();
            }
            boolean matched = false;
            for (String agentId : installedAgents) {
                Target target = new Target(agentId, patch.fileName());
                try {
                    String original = assets.readPrompt(target.agentId(), target.fileName());
                    if (original == null) {
                        continue;
                    }
                    targets.putIfAbsent(target, null);
                    originals.put(target, original);
                    matched = true;
                } catch (IOException error) {
                    targets.putIfAbsent(target, safeMessage(error));
                    matched = true;
                }
            }
            if (!matched) {
                Target wildcard = new Target("*", patch.fileName());
                targets.putIfAbsent(wildcard,
                        "已安装的超级小爱中没有 Agent 包含该 Prompt 文件");
            }
        }

        List<PromptPreviewDocument> result = new ArrayList<>();
        for (Map.Entry<Target, String> entry : targets.entrySet()) {
            Target target = entry.getKey();
            if (entry.getValue() != null) {
                result.add(PromptPreviewDocument.unavailable(
                        target.agentId(), target.fileName(), entry.getValue()));
                continue;
            }
            try {
                String original = originals.containsKey(target)
                        ? originals.get(target) : assets.readPrompt(target.agentId(), target.fileName());
                if (original == null) {
                    result.add(PromptPreviewDocument.unavailable(
                            target.agentId(),
                            target.fileName(),
                            "当前安装的超级小爱 APK 中不存在该 Prompt 文件"
                    ));
                    continue;
                }
                PromptPatchResult patched = PromptPatchEngine.apply(
                        original, target.agentId(), target.fileName(), config);
                result.add(PromptPreviewDocument.available(
                        target.agentId(), target.fileName(), original, patched));
            } catch (IOException error) {
                result.add(PromptPreviewDocument.unavailable(
                        target.agentId(), target.fileName(), safeMessage(error)));
            }
        }
        return List.copyOf(result);
    }

    private List<String> listAgentIds() {
        try {
            List<String> result = new ArrayList<>(assets.listAgentIds());
            result.sort(Comparator.naturalOrder());
            return result;
        } catch (IOException error) {
            throw new IllegalStateException(
                    "无法读取已安装超级小爱的 Agent 列表：" + safeMessage(error), error);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    interface PromptAssetSource {
        List<String> listAgentIds() throws IOException;

        String readPrompt(String agentId, String fileName) throws IOException;
    }

    private static final class PackagePromptAssetSource implements PromptAssetSource {
        private final Context context;
        private volatile AssetManager assetManager;

        private PackagePromptAssetSource(Context context) {
            Context supplied = Objects.requireNonNull(context, "context");
            Context application = supplied.getApplicationContext();
            this.context = application == null ? supplied : application;
        }

        @Override
        public List<String> listAgentIds() throws IOException {
            String[] names = targetAssets().list("agents");
            return names == null ? List.of() : Arrays.asList(names);
        }

        @Override
        public String readPrompt(String agentId, String fileName) throws IOException {
            String path = "agents/" + agentId + "/" + fileName;
            try (InputStream input = targetAssets().open(path, AssetManager.ACCESS_STREAMING)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_PROMPT_BYTES) {
                        throw new IOException("Prompt 文件超过 1 MiB 预览上限");
                    }
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            } catch (FileNotFoundException error) {
                return null;
            }
        }

        private synchronized AssetManager targetAssets() throws IOException {
            if (assetManager != null) {
                return assetManager;
            }
            try {
                assetManager = context.createPackageContext(
                        BridgeContract.TARGET_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY
                ).getAssets();
                return assetManager;
            } catch (PackageManager.NameNotFoundException error) {
                throw new IOException("未安装超级小爱", error);
            }
        }
    }

    private record Target(String agentId, String fileName) {
    }
}
