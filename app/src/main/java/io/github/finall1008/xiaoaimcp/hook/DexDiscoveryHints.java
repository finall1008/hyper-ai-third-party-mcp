package io.github.finall1008.xiaoaimcp.hook;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record DexDiscoveryHints(
        List<String> classNames,
        Set<String> agentTraceClassNames,
        List<String> promptClassNames,
        int matchedMethods,
        long elapsedMillis
) {
    DexDiscoveryHints {
        classNames = List.copyOf(new LinkedHashSet<>(classNames));
        agentTraceClassNames = Set.copyOf(agentTraceClassNames);
        promptClassNames = List.copyOf(new LinkedHashSet<>(promptClassNames));
    }

    DexDiscoveryHints(
            List<String> classNames,
            Set<String> agentTraceClassNames,
            int matchedMethods,
            long elapsedMillis
    ) {
        this(classNames, agentTraceClassNames, List.of(), matchedMethods, elapsedMillis);
    }

    static DexDiscoveryHints empty() {
        return new DexDiscoveryHints(List.of(), Set.of(), List.of(), 0, 0L);
    }

    boolean isEmpty() {
        return classNames.isEmpty() && agentTraceClassNames.isEmpty() && promptClassNames.isEmpty();
    }

    boolean hasAgentTraceHints() {
        return !agentTraceClassNames.isEmpty();
    }

    boolean hasPromptHints() {
        return !promptClassNames.isEmpty();
    }
}
