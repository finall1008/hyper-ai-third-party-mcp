package io.github.finall1008.xiaoaimcp.prompt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class PromptPatchCodec {
    public static final int MAX_PATCHES = 64;
    public static final int MAX_SELECTOR_LENGTH = 128;
    public static final int MAX_FIND_LENGTH = 16 * 1024;
    public static final int MAX_REPLACEMENT_LENGTH = 32 * 1024;
    public static final int MAX_CONFIG_LENGTH = 256 * 1024;

    private PromptPatchCodec() {
    }

    public static String defaultConfig() {
        return encode(PromptPatchConfig.defaults());
    }

    public static String encode(PromptPatchConfig config) {
        validate(config);
        try {
            JSONObject root = new JSONObject();
            root.put("version", BridgeContract.PROMPT_PATCH_SCHEMA_VERSION);
            root.put("enabled", config.enabled());
            JSONArray patches = new JSONArray();
            for (PromptPatch patch : config.patches()) {
                JSONObject item = new JSONObject();
                item.put("id", patch.id());
                item.put("enabled", patch.enabled());
                item.put("agent_id", patch.agentId());
                item.put("file_name", patch.fileName());
                item.put("find", patch.findText());
                item.put("replacement", patch.replacementText());
                patches.put(item);
            }
            root.put("patches", patches);
            String encoded = root.toString();
            if (encoded.length() > MAX_CONFIG_LENGTH) {
                throw new IllegalArgumentException("Prompt patch configuration is too large");
            }
            return encoded;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Unable to encode prompt patches", error);
        }
    }

    public static PromptPatchConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return PromptPatchConfig.defaults();
        }
        if (json.length() > MAX_CONFIG_LENGTH) {
            throw new IllegalArgumentException("Prompt patch configuration is too large");
        }
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", -1);
            if (version != 1 && version != BridgeContract.PROMPT_PATCH_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported prompt patch version: " + version);
            }
            JSONArray array = root.optJSONArray("patches");
            List<PromptPatch> patches = new ArrayList<>();
            if (version == 1 && root.optBoolean("built_in_reliability_enabled", true)) {
                patches.addAll(DefaultPromptPatches.load());
            }
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.getJSONObject(index);
                    patches.add(new PromptPatch(
                            item.getString("id"),
                            item.optBoolean("enabled", true),
                            item.getString("agent_id"),
                            item.getString("file_name"),
                            item.getString("find"),
                            item.optString("replacement", "")
                    ));
                }
            }
            PromptPatchConfig config = new PromptPatchConfig(
                    root.optBoolean("enabled", true),
                    patches
            );
            validate(config);
            return config;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid prompt patch JSON", error);
        }
    }

    public static void validate(PromptPatchConfig config) {
        if (config.patches().size() > MAX_PATCHES) {
            throw new IllegalArgumentException("At most " + MAX_PATCHES + " prompt patches are allowed");
        }
        Set<String> ids = new HashSet<>();
        for (PromptPatch patch : config.patches()) {
            validatePatch(patch);
            if (!ids.add(patch.id())) {
                throw new IllegalArgumentException("Duplicate prompt patch ID: " + patch.id());
            }
        }
    }

    public static void validatePatch(PromptPatch patch) {
        requireText(patch.id(), "Patch ID", MAX_SELECTOR_LENGTH);
        requireText(patch.agentId(), "Agent ID", MAX_SELECTOR_LENGTH);
        requireText(patch.fileName(), "Prompt file name", MAX_SELECTOR_LENGTH);
        requireText(patch.findText(), "Find text", MAX_FIND_LENGTH);
        if (patch.replacementText().length() > MAX_REPLACEMENT_LENGTH) {
            throw new IllegalArgumentException("Replacement text is too long");
        }
        if (!patch.agentId().equals("*") && containsPathSeparator(patch.agentId())) {
            throw new IllegalArgumentException("Agent ID must be an exact ID or *");
        }
        if (containsPathSeparator(patch.fileName())
                || patch.fileName().equals(".")
                || patch.fileName().equals("..")
                || patch.fileName().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Prompt file name must be a basename");
        }
    }

    private static void requireText(String value, String label, int maxLength) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(label + " is too long");
        }
    }

    private static boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }
}
