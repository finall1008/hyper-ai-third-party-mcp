package io.github.finall1008.xiaoaimcp.filepolicy;

import java.util.Objects;

public final class FileAccessRule {
    private final String path;
    private final boolean allowMutation;
    private final boolean allowLockscreenRead;
    private final boolean allowLockscreenMutation;
    private final boolean allowBackgroundMutation;
    private final boolean allowRecursiveDelete;
    private final MutationConfirmationPolicy confirmationPolicy;

    public FileAccessRule(
            String path,
            boolean allowMutation,
            boolean allowLockscreenRead,
            boolean allowLockscreenMutation,
            boolean allowBackgroundMutation,
            boolean allowRecursiveDelete
    ) {
        this(path, allowMutation, allowLockscreenRead, allowLockscreenMutation,
                allowBackgroundMutation, allowRecursiveDelete,
                MutationConfirmationPolicy.ASK_EVERY_TIME);
    }

    public FileAccessRule(
            String path,
            boolean allowMutation,
            boolean allowLockscreenRead,
            boolean allowLockscreenMutation,
            boolean allowBackgroundMutation,
            boolean allowRecursiveDelete,
            MutationConfirmationPolicy confirmationPolicy
    ) {
        this.path = Objects.requireNonNull(path, "path");
        this.allowMutation = allowMutation;
        this.allowLockscreenRead = allowLockscreenRead;
        this.allowLockscreenMutation = allowLockscreenMutation;
        this.allowBackgroundMutation = allowBackgroundMutation;
        this.allowRecursiveDelete = allowRecursiveDelete;
        this.confirmationPolicy = Objects.requireNonNull(
                confirmationPolicy, "confirmationPolicy");
    }

    public String path() {
        return path;
    }

    public boolean allowMutation() {
        return allowMutation;
    }

    public boolean allowLockscreenRead() {
        return allowLockscreenRead;
    }

    public boolean allowLockscreenMutation() {
        return allowLockscreenMutation;
    }

    public boolean allowBackgroundMutation() {
        return allowBackgroundMutation;
    }

    public boolean allowRecursiveDelete() {
        return allowRecursiveDelete;
    }

    public MutationConfirmationPolicy confirmationPolicy() {
        return confirmationPolicy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FileAccessRule rule)) {
            return false;
        }
        return allowMutation == rule.allowMutation
                && allowLockscreenRead == rule.allowLockscreenRead
                && allowLockscreenMutation == rule.allowLockscreenMutation
                && allowBackgroundMutation == rule.allowBackgroundMutation
                && allowRecursiveDelete == rule.allowRecursiveDelete
                && confirmationPolicy == rule.confirmationPolicy
                && path.equals(rule.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, allowMutation, allowLockscreenRead,
                allowLockscreenMutation, allowBackgroundMutation, allowRecursiveDelete,
                confirmationPolicy);
    }
}
