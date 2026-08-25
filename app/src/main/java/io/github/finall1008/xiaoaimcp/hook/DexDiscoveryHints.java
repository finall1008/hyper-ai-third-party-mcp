package io.github.finall1008.xiaoaimcp.hook;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record DexDiscoveryHints(
        List<String> classNames,
        Set<String> mcpManagerClassNames,
        Set<String> agentTraceClassNames,
        int matchedMethods,
        long elapsedMillis
) {
    DexDiscoveryHints {
        classNames = List.copyOf(new LinkedHashSet<>(classNames));
        mcpManagerClassNames = Set.copyOf(mcpManagerClassNames);
        agentTraceClassNames = Set.copyOf(agentTraceClassNames);
    }

    DexDiscoveryHints(
            List<String> classNames,
            Set<String> mcpManagerClassNames,
            int matchedMethods,
            long elapsedMillis
    ) {
        this(classNames, mcpManagerClassNames, Set.of(), matchedMethods, elapsedMillis);
    }

    static DexDiscoveryHints empty() {
        return new DexDiscoveryHints(List.of(), Set.of(), Set.of(), 0, 0L);
    }

    boolean isEmpty() {
        return classNames.isEmpty() && agentTraceClassNames.isEmpty();
    }

    boolean hasAgentTraceHints() {
        return !agentTraceClassNames.isEmpty();
    }
}
