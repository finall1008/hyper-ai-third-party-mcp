package io.github.finall1008.xiaoaimcp.prompt;

import java.util.List;

public record PromptPatchConfig(
        boolean enabled,
        List<PromptPatch> patches
) {
    public PromptPatchConfig {
        patches = List.copyOf(patches);
    }

    public static PromptPatchConfig defaults() {
        return new PromptPatchConfig(true, DefaultPromptPatches.load());
    }
}
