package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Method;

record AuxiliaryPromptHookTargets(
        String mode,
        Method toolPromptLoader,
        Method memoryPromptLoader
) {
    AuxiliaryPromptHookTargets {
        if (toolPromptLoader != null) {
            toolPromptLoader.setAccessible(true);
        }
        if (memoryPromptLoader != null) {
            memoryPromptLoader.setAccessible(true);
        }
    }

    boolean hasToolPrompt() {
        return toolPromptLoader != null;
    }

    boolean hasMemoryPrompt() {
        return memoryPromptLoader != null;
    }

    boolean hasAllCapabilities() {
        return hasToolPrompt() && hasMemoryPrompt();
    }

    boolean isEmpty() {
        return !hasToolPrompt() && !hasMemoryPrompt();
    }

    AuxiliaryPromptHookTargets withFallback(AuxiliaryPromptHookTargets fallback) {
        if (fallback == null) {
            return this;
        }
        boolean changed = (!hasToolPrompt() && fallback.hasToolPrompt())
                || (!hasMemoryPrompt() && fallback.hasMemoryPrompt());
        return new AuxiliaryPromptHookTargets(
                changed ? mode + "+" + fallback.mode() : mode,
                hasToolPrompt() ? toolPromptLoader : fallback.toolPromptLoader(),
                hasMemoryPrompt() ? memoryPromptLoader : fallback.memoryPromptLoader()
        );
    }
}
