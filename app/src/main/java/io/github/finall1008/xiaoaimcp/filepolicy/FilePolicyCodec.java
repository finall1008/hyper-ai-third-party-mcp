package io.github.finall1008.xiaoaimcp.filepolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class FilePolicyCodec {
    private FilePolicyCodec() {
    }

    public static String emptyConfig() {
        return encode(FilePolicyConfig.disabled());
    }

    public static String encode(FilePolicyConfig config) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", BridgeContract.FILE_POLICY_SCHEMA_VERSION);
            root.put("enabled", config.enabled());
            JSONArray rules = new JSONArray();
            Set<String> paths = new HashSet<>();
            for (FileAccessRule rule : config.rules()) {
                String path = normalizeConfiguredPath(rule.path());
                if (!paths.add(path)) {
                    throw new IllegalArgumentException("Duplicate file policy path: " + path);
                }
                JSONObject item = new JSONObject();
                item.put("path", path);
                item.put("allow_mutation", rule.allowMutation());
                item.put("allow_lockscreen_read", rule.allowLockscreenRead());
                item.put("allow_lockscreen_mutation", rule.allowLockscreenMutation());
                item.put("allow_background_mutation", rule.allowBackgroundMutation());
                item.put("allow_recursive_delete", rule.allowRecursiveDelete());
                item.put("confirmation_policy", rule.confirmationPolicy().storageValue());
                rules.put(item);
            }
            root.put("rules", rules);
            return root.toString();
        } catch (JSONException error) {
            throw new IllegalArgumentException("Unable to encode file policy", error);
        }
    }

    public static FilePolicyConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return FilePolicyConfig.disabled();
        }
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", -1);
            if (version != 1 && version != BridgeContract.FILE_POLICY_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported file policy version: " + version);
            }
            JSONArray array = root.optJSONArray("rules");
            List<FileAccessRule> rules = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.getJSONObject(index);
                    String path = normalizeConfiguredPath(item.getString("path"));
                    if (!paths.add(path)) {
                        throw new IllegalArgumentException("Duplicate file policy path: " + path);
                    }
                    rules.add(new FileAccessRule(
                            path,
                            item.optBoolean("allow_mutation", false),
                            item.optBoolean("allow_lockscreen_read", false),
                            item.optBoolean("allow_lockscreen_mutation", false),
                            item.optBoolean("allow_background_mutation", false),
                            item.optBoolean("allow_recursive_delete", false),
                            version == 1
                                    ? MutationConfirmationPolicy.ASK_EVERY_TIME
                                    : MutationConfirmationPolicy.fromStorageValue(
                                            item.optString("confirmation_policy", "ask"))
                    ));
                }
            }
            return new FilePolicyConfig(root.optBoolean("enabled", false), rules);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid file policy JSON", error);
        }
    }

    public static String normalizeConfiguredPath(String rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException("Path is required");
        }
        if (rawPath.indexOf('\\') >= 0 || rawPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsafe path: " + rawPath);
        }
        String path = rawPath.trim();
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!(path.equals("/sdcard")
                || path.startsWith("/sdcard/")
                || path.equals("/storage/emulated/0")
                || path.startsWith("/storage/emulated/0/"))) {
            throw new IllegalArgumentException(
                    "Only /sdcard or /storage/emulated/0 paths are supported");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe path: " + rawPath);
            }
        }
        if (path.equals("/storage/emulated/0")) {
            return "/sdcard";
        }
        if (path.startsWith("/storage/emulated/0/")) {
            return "/sdcard" + path.substring("/storage/emulated/0".length());
        }
        return path;
    }
}
