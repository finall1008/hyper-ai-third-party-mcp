package io.github.finall1008.xiaoaimcp.hook;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DexKitTargetLocator {
    private static final String MCP_CONFIG_MARKER = "personal_mcp_servers.json";
    private static final List<String> METHOD_ANCHORS = List.of(
            "syncConfigAndDiscoverIfNeeded",
            "reloadConfig",
            "loadCatalogAndRegister",
            "getAllServers",
            "getGatewayMode",
            "resolveToFile",
            "listVirtualRoot",
            "isCommandNameAllowed",
            "checkToolRisk",
            "checkCliCommand",
            "confirmByCategory$runtime",
            "requestConsent$runtime"
    );
    private static final List<String> AGENT_TRACE_STRING_ANCHORS = List.of(
            "reasoning_delta",
            "reasoningContent",
            "shouldSuppressReasoning",
            "tool_start",
            "tool_progress",
            "tool_done",
            "tool_failed",
            "tool_call_id",
            "ToolCallItem",
            "MICLAW_THINKING_CHAIN",
            "__reactNativeBundleEndSuccess__"
    );
    private static final List<String> AGENT_TRACE_METHOD_ANCHORS = List.of(
            "buildToolCallItem",
            "buildToastStream",
            "loadScript"
    );

    private static volatile boolean nativeLoaded;

    private DexKitTargetLocator() {
    }

    static DexDiscoveryHints discover(ClassLoader classLoader) {
        long started = System.nanoTime();
        ensureNativeLoaded();

        Set<String> allClasses = new LinkedHashSet<>();
        Set<String> managerClasses = new LinkedHashSet<>();
        Set<String> agentTraceClasses = new LinkedHashSet<>();
        int matchedMethods = 0;
        try (DexKitBridge bridge = DexKitBridge.create(classLoader, false)) {
            List<MethodData> markerMethods = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(MCP_CONFIG_MARKER)
            ));
            matchedMethods += markerMethods.size();
            addDeclaringClasses(markerMethods, allClasses);
            addTextReaderDeclaringClasses(markerMethods, managerClasses);
            addRelatedDeclaringClasses(markerMethods, allClasses);

            MethodMatcher[] anchors = METHOD_ANCHORS.stream()
                    .map(name -> MethodMatcher.create().name(name))
                    .toArray(MethodMatcher[]::new);
            List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().anyOf(anchors)
            ));
            matchedMethods += anchorMethods.size();
            addDeclaringClasses(anchorMethods, allClasses);

            for (String anchor : AGENT_TRACE_STRING_ANCHORS) {
                List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(anchor)
                ));
                matchedMethods += methods.size();
                addDeclaringClasses(methods, agentTraceClasses);
                addRelatedDeclaringClasses(methods, agentTraceClasses);
            }

            MethodMatcher[] traceMethods = AGENT_TRACE_METHOD_ANCHORS.stream()
                    .map(name -> MethodMatcher.create().name(name))
                    .toArray(MethodMatcher[]::new);
            List<MethodData> traceMethodMatches = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().anyOf(traceMethods)
            ));
            matchedMethods += traceMethodMatches.size();
            addDeclaringClasses(traceMethodMatches, agentTraceClasses);
            addRelatedDeclaringClasses(traceMethodMatches, agentTraceClasses);
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return new DexDiscoveryHints(
                List.copyOf(allClasses),
                managerClasses,
                agentTraceClasses,
                matchedMethods,
                elapsedMillis
        );
    }

    private static void addDeclaringClasses(
            List<MethodData> methods,
            Set<String> destination
    ) {
        for (MethodData method : methods) {
            String className = method.getDeclaredClassName();
            if (className != null && !className.isBlank() && !shouldSkip(className)) {
                destination.add(className);
            }
        }
    }

    private static void addRelatedDeclaringClasses(
            List<MethodData> methods,
            Set<String> destination
    ) {
        for (MethodData method : methods) {
            try {
                addDeclaringClasses(method.getCallers(), destination);
                addDeclaringClasses(method.getInvokes(), destination);
            } catch (Throwable ignored) {
                // Relationship metadata is an optional hint; direct query results remain usable.
            }
        }
    }

    private static void addTextReaderDeclaringClasses(
            List<MethodData> methods,
            Set<String> destination
    ) {
        for (MethodData method : methods) {
            if (method.getParamCount() == 0
                    && "java.lang.String".equals(method.getReturnTypeName())) {
                addDeclaringClasses(List.of(method), destination);
            }
        }
    }

    private static boolean shouldSkip(String className) {
        return className.startsWith("android.")
                || className.startsWith("androidx.")
                || className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("kotlin.")
                || className.startsWith("kotlinx.")
                || className.startsWith("io.github.finall1008.xiaoaimcp.")
                || className.startsWith("org.luckypray.dexkit.");
    }

    private static void ensureNativeLoaded() {
        if (nativeLoaded) {
            return;
        }
        synchronized (DexKitTargetLocator.class) {
            if (!nativeLoaded) {
                System.loadLibrary("dexkit");
                nativeLoaded = true;
            }
        }
    }
}
