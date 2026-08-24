package io.github.finall1008.xiaoaimcp.filepolicy;

public enum MutationConfirmationPolicy {
    ASK_EVERY_TIME("ask"),
    BACKGROUND_AUTOMATIC("background_automatic"),
    ALL_AGENTS_AUTOMATIC("all_agents_automatic");

    private final String storageValue;

    MutationConfirmationPolicy(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }

    public boolean automaticallyAllows(String agentId) {
        return this == ALL_AGENTS_AUTOMATIC
                || this == BACKGROUND_AUTOMATIC
                && PathPolicyEvaluator.isBackgroundAgent(agentId);
    }

    public static MutationConfirmationPolicy fromStorageValue(String value) {
        for (MutationConfirmationPolicy policy : values()) {
            if (policy.storageValue.equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown mutation confirmation policy: " + value);
    }
}
