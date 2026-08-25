package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

record PromptHookTargets(
        String mode,
        Method resolvePrompt,
        Field fileNameField,
        List<Method> callSites,
        Object cacheInvalidator,
        Method invalidateMemoryCache
) {
    PromptHookTargets {
        resolvePrompt.setAccessible(true);
        fileNameField.setAccessible(true);
        callSites = List.copyOf(callSites);
        for (Method callSite : callSites) {
            callSite.setAccessible(true);
        }
        if (invalidateMemoryCache != null) {
            invalidateMemoryCache.setAccessible(true);
        }
    }

    boolean canInvalidateMemoryCache() {
        return invalidateMemoryCache != null
                && (cacheInvalidator != null
                || Modifier.isStatic(invalidateMemoryCache.getModifiers()));
    }
}
