package io.github.finall1008.xiaoaimcp.filepolicy;

import java.util.List;
import java.util.Objects;

public final class FilePolicyConfig {
    private final boolean enabled;
    private final List<FileAccessRule> rules;

    public FilePolicyConfig(boolean enabled, List<FileAccessRule> rules) {
        this.enabled = enabled;
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public static FilePolicyConfig disabled() {
        return new FilePolicyConfig(false, List.of());
    }

    public boolean enabled() {
        return enabled;
    }

    public List<FileAccessRule> rules() {
        return rules;
    }
}
