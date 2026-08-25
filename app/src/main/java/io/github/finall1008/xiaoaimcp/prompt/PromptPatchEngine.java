package io.github.finall1008.xiaoaimcp.prompt;

import java.util.ArrayList;
import java.util.List;

public final class PromptPatchEngine {
    private PromptPatchEngine() {
    }

    public static PromptPatchResult apply(
            String original,
            String agentId,
            String fileName,
            PromptPatchConfig config
    ) {
        if (original == null || !config.enabled()) {
            return new PromptPatchResult(original, List.of(), List.of());
        }
        String current = original;
        List<String> applied = new ArrayList<>();
        List<PromptPatchResult.SkippedPatch> skipped = new ArrayList<>();
        current = applyAll(current, agentId, fileName, config.patches(), applied, skipped);
        return new PromptPatchResult(current, applied, skipped);
    }

    private static String applyAll(
            String input,
            String agentId,
            String fileName,
            List<PromptPatch> patches,
            List<String> applied,
            List<PromptPatchResult.SkippedPatch> skipped
    ) {
        String current = input;
        for (PromptPatch patch : patches) {
            if (!patch.enabled()
                    || !patch.fileName().equals(fileName)
                    || !(patch.agentId().equals("*") || patch.agentId().equals(agentId))) {
                continue;
            }
            int occurrences = countOccurrences(current, patch.findText());
            if (occurrences != 1) {
                skipped.add(new PromptPatchResult.SkippedPatch(patch.id(), occurrences));
                continue;
            }
            int start = current.indexOf(patch.findText());
            current = current.substring(0, start)
                    + patch.replacementText()
                    + current.substring(start + patch.findText().length());
            applied.add(patch.id());
        }
        return current;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
