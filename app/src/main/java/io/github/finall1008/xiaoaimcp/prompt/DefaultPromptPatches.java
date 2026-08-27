package io.github.finall1008.xiaoaimcp.prompt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DefaultPromptPatches {
    private static final String RESOURCE_NAME = "prompt/default_prompt_patches.json";
    private static volatile List<PromptPatch> cached;

    private DefaultPromptPatches() {
    }

    public static List<PromptPatch> load() {
        List<PromptPatch> current = cached;
        if (current != null) {
            return current;
        }
        synchronized (DefaultPromptPatches.class) {
            if (cached == null) {
                cached = List.copyOf(readResource());
            }
            return cached;
        }
    }

    private static List<PromptPatch> readResource() {
        ClassLoader loader = DefaultPromptPatches.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Missing default prompt patch resource: "
                        + RESOURCE_NAME);
            }
            JSONObject root = new JSONObject(readUtf8(input));
            if (root.optInt("version", -1) != 2) {
                throw new IllegalArgumentException("Unsupported default prompt patch version");
            }
            JSONArray array = root.getJSONArray("patches");
            List<PromptPatch> patches = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                PromptPatch patch = new PromptPatch(
                        item.getString("id"),
                        item.optBoolean("enabled", true),
                        PromptTargetType.parse(item.getString("target_type")),
                        item.getString("target_id"),
                        item.getString("target_part"),
                        item.getString("find"),
                        item.optString("replacement", "")
                );
                PromptPatchCodec.validatePatch(patch);
                patches.add(patch);
            }
            if (patches.isEmpty()) {
                throw new IllegalArgumentException("Default prompt patch resource is empty");
            }
            return patches;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load default prompt patches", error);
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
