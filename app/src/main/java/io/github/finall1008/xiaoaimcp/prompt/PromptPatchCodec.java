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
                item.put("target_type", patch.targetType().serializedName());
                item.put("target_id", patch.agentId());
                item.put("target_part", patch.fileName());
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
            if (version != 1 && version != 2
                    && version != BridgeContract.PROMPT_PATCH_SCHEMA_VERSION) {
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
                    patches.add(parsePatch(item, version));
                }
            }
            if (version == 2) {
                appendNewVersionThreeDefaults(patches);
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
        requireText(patch.agentId(), targetIdLabel(patch.targetType()), MAX_SELECTOR_LENGTH);
        requireText(patch.fileName(), targetPartLabel(patch.targetType()), MAX_SELECTOR_LENGTH);
        requireText(patch.findText(), "Find text", MAX_FIND_LENGTH);
        if (patch.replacementText().length() > MAX_REPLACEMENT_LENGTH) {
            throw new IllegalArgumentException("Replacement text is too long");
        }
        if (patch.targetType() != PromptTargetType.AGENT_PROMPT
                && patch.agentId().equals("*")) {
            throw new IllegalArgumentException(targetIdLabel(patch.targetType())
                    + " must be an exact ID");
        }
        if (patch.targetType() == PromptTargetType.MEMORY_PROMPT
                ? containsUnsafeRelativePath(patch.agentId())
                : containsUnsafeSelector(patch.agentId())) {
            throw new IllegalArgumentException(targetIdLabel(patch.targetType())
                    + " must be an exact ID or *");
        }
        if (containsPathSeparator(patch.fileName())
                || patch.fileName().equals(".")
                || patch.fileName().equals("..")
                || patch.fileName().indexOf('\0') >= 0) {
            throw new IllegalArgumentException(targetPartLabel(patch.targetType())
                    + " must be a basename");
        }
        if (patch.targetType() == PromptTargetType.MEMORY_PROMPT
                && !(patch.agentId().endsWith(".md") || patch.agentId().endsWith(".txt"))) {
            throw new IllegalArgumentException("Memory Prompt key must include .md or .txt");
        }
        if (patch.targetType() == PromptTargetType.MEMORY_PROMPT
                && !(patch.fileName().equals("systemPrompt")
                || patch.fileName().equals("userPrompt"))) {
            throw new IllegalArgumentException(
                    "Memory Prompt section must be systemPrompt or userPrompt");
        }
    }

    private static PromptPatch parsePatch(JSONObject item, int version) throws JSONException {
        PromptTargetType targetType = version >= 3
                ? PromptTargetType.parse(item.getString("target_type"))
                : PromptTargetType.AGENT_PROMPT;
        return new PromptPatch(
                item.getString("id"),
                item.optBoolean("enabled", true),
                targetType,
                item.getString(version >= 3 ? "target_id" : "agent_id"),
                item.getString(version >= 3 ? "target_part" : "file_name"),
                item.getString("find"),
                item.optString("replacement", "")
        );
    }

    private static void appendNewVersionThreeDefaults(List<PromptPatch> patches) {
        Set<String> existingIds = new HashSet<>();
        for (PromptPatch patch : patches) {
            existingIds.add(patch.id());
        }
        for (PromptPatch patch : DefaultPromptPatches.load()) {
            if (patch.id().startsWith("default-v3-") && existingIds.add(patch.id())) {
                patches.add(patch);
            }
        }
    }

    private static String targetIdLabel(PromptTargetType targetType) {
        return switch (targetType) {
            case AGENT_PROMPT -> "Agent ID";
            case TOOL_PROMPT -> "Tool name";
            case MEMORY_PROMPT -> "Memory Prompt key";
        };
    }

    private static String targetPartLabel(PromptTargetType targetType) {
        return switch (targetType) {
            case AGENT_PROMPT -> "Prompt file name";
            case TOOL_PROMPT, MEMORY_PROMPT -> "Prompt section";
        };
    }

    private static boolean containsUnsafeSelector(String value) {
        return !value.equals("*") && (containsPathSeparator(value)
                || value.equals(".") || value.equals("..") || value.indexOf('\0') >= 0);
    }

    private static boolean containsUnsafeRelativePath(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.endsWith("/")) {
            return true;
        }
        for (String segment : value.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
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
