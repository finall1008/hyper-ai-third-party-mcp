package io.github.finall1008.xiaoaimcp.prompt;

import java.util.List;
import java.util.Objects;

public record PromptPreviewDocument(
        String agentId,
        String fileName,
        String originalText,
        String patchedText,
        List<String> appliedPatchIds,
        List<PromptPatchResult.SkippedPatch> skippedPatches,
        String error
) {
    public PromptPreviewDocument {
        agentId = Objects.requireNonNull(agentId, "agentId");
        fileName = Objects.requireNonNull(fileName, "fileName");
        appliedPatchIds = List.copyOf(appliedPatchIds);
        skippedPatches = List.copyOf(skippedPatches);
    }

    public static PromptPreviewDocument available(
            String agentId,
            String fileName,
            String originalText,
            PromptPatchResult result
    ) {
        return new PromptPreviewDocument(
                agentId,
                fileName,
                Objects.requireNonNull(originalText, "originalText"),
                Objects.requireNonNull(result.text(), "patchedText"),
                result.appliedPatchIds(),
                result.skippedPatches(),
                null
        );
    }

    public static PromptPreviewDocument unavailable(
            String agentId,
            String fileName,
            String error
    ) {
        return new PromptPreviewDocument(
                agentId,
                fileName,
                null,
                null,
                List.of(),
                List.of(),
                Objects.requireNonNull(error, "error")
        );
    }

    public boolean available() {
        return error == null;
    }
}
