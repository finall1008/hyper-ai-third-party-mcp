package io.github.finall1008.xiaoaimcp.hook;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record DexDiscoveryHints(
        List<String> classNames,
        Set<String> mcpManagerClassNames,
        int matchedMethods,
        long elapsedMillis
) {
    DexDiscoveryHints {
        classNames = List.copyOf(new LinkedHashSet<>(classNames));
        mcpManagerClassNames = Set.copyOf(mcpManagerClassNames);
    }

    static DexDiscoveryHints empty() {
        return new DexDiscoveryHints(List.of(), Set.of(), 0, 0L);
    }

    boolean isEmpty() {
        return classNames.isEmpty();
    }
}
