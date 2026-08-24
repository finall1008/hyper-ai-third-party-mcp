package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Method;

record ResolvedHookTargets(
        String mode,
        Class<?> managerClass,
        Method textConfigMethod,
        Method objectConfigMethod,
        Method syncMethod,
        Method reloadMethod,
        Method loadCatalogMethod,
        Class<?> continuationClass,
        ObjectConfigAdapter objectAdapter
) {
    boolean hasTextConfig() {
        return textConfigMethod != null;
    }

    boolean hasObjectConfig() {
        return objectConfigMethod != null && objectAdapter != null;
    }

    boolean hasAllCapabilities() {
        return hasTextConfig()
                && hasObjectConfig()
                && syncMethod != null
                && reloadMethod != null
                && loadCatalogMethod != null
                && continuationClass != null;
    }

    ResolvedHookTargets withFallback(ResolvedHookTargets fallback) {
        if (managerClass != fallback.managerClass) {
            // A stable structural manager is safer than merging an unrelated marker-only class.
            return fallback;
        }
        boolean objectFromPrimary = hasObjectConfig();
        boolean reloadFromPrimary = reloadMethod != null;
        boolean usedFallback = (!hasTextConfig() && fallback.hasTextConfig())
                || (!objectFromPrimary && fallback.hasObjectConfig())
                || (syncMethod == null && fallback.syncMethod != null)
                || (!reloadFromPrimary && fallback.reloadMethod != null)
                || (loadCatalogMethod == null && fallback.loadCatalogMethod != null);
        return new ResolvedHookTargets(
                usedFallback ? mode + "+" + fallback.mode : mode,
                managerClass,
                hasTextConfig() ? textConfigMethod : fallback.textConfigMethod,
                objectFromPrimary ? objectConfigMethod : fallback.objectConfigMethod,
                syncMethod != null ? syncMethod : fallback.syncMethod,
                reloadFromPrimary ? reloadMethod : fallback.reloadMethod,
                loadCatalogMethod != null ? loadCatalogMethod : fallback.loadCatalogMethod,
                reloadFromPrimary ? continuationClass : fallback.continuationClass,
                objectFromPrimary ? objectAdapter : fallback.objectAdapter
        );
    }
}
