package io.github.finall1008.xiaoaimcp.prompt;

import java.util.Objects;

public record PromptPatch(
        String id,
        boolean enabled,
        PromptTargetType targetType,
        String agentId,
        String fileName,
        String findText,
        String replacementText
) {
    public PromptPatch {
        id = Objects.requireNonNull(id, "id");
        targetType = Objects.requireNonNull(targetType, "targetType");
        agentId = Objects.requireNonNull(agentId, "agentId");
        fileName = Objects.requireNonNull(fileName, "fileName");
        findText = Objects.requireNonNull(findText, "findText");
        replacementText = Objects.requireNonNull(replacementText, "replacementText");
    }

    public PromptPatch(
            String id,
            boolean enabled,
            String agentId,
            String fileName,
            String findText,
            String replacementText
    ) {
        this(id, enabled, PromptTargetType.AGENT_PROMPT, agentId, fileName,
                findText, replacementText);
    }

    public PromptPatch withEnabled(boolean value) {
        return new PromptPatch(id, value, targetType, agentId, fileName,
                findText, replacementText);
    }
}
