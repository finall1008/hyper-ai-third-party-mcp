package io.github.finall1008.xiaoaimcp.prompt;

import java.util.Objects;

public record PromptPatch(
        String id,
        boolean enabled,
        String agentId,
        String fileName,
        String findText,
        String replacementText
) {
    public PromptPatch {
        id = Objects.requireNonNull(id, "id");
        agentId = Objects.requireNonNull(agentId, "agentId");
        fileName = Objects.requireNonNull(fileName, "fileName");
        findText = Objects.requireNonNull(findText, "findText");
        replacementText = Objects.requireNonNull(replacementText, "replacementText");
    }

    public PromptPatch withEnabled(boolean value) {
        return new PromptPatch(id, value, agentId, fileName, findText, replacementText);
    }
}
