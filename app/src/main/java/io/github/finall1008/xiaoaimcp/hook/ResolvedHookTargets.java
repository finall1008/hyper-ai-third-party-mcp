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
}
