package io.github.finall1008.xiaoaimcp.prompt;

import java.util.List;

public record PromptPatchResult(
        String text,
        List<String> appliedPatchIds,
        List<SkippedPatch> skippedPatches
) {
    public PromptPatchResult {
        appliedPatchIds = List.copyOf(appliedPatchIds);
        skippedPatches = List.copyOf(skippedPatches);
    }

    public record SkippedPatch(String id, int occurrences) {
    }
}
