package io.github.finall1008.xiaoaimcp.prompt;

public enum PromptTargetType {
    AGENT_PROMPT("agent_prompt"),
    TOOL_PROMPT("tool_prompt"),
    MEMORY_PROMPT("memory_prompt");

    private final String serializedName;

    PromptTargetType(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static PromptTargetType parse(String value) {
        for (PromptTargetType type : values()) {
            if (type.serializedName.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Prompt target type: " + value);
    }
}
