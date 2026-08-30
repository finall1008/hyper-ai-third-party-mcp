package io.github.finall1008.xiaoaimcp.hook;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DexKitTargetLocator {
    private static final List<String> METHOD_ANCHORS = List.of(
            "resolveToFile",
            "listVirtualRoot",
            "isCommandNameAllowed",
            "checkToolRisk",
            "checkCliCommand",
            "confirmByCategory$runtime",
            "requestConsent$runtime",
            "getFirstVisibleOutputTimeoutMs$runtime"
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
    private static final List<String> PROMPT_STRING_ANCHORS = List.of(
            "tool_selection_rules.md",
            "custom_prompt.md",
            "complex_task_prompt.md",
            "prompts/tools/",
            "prompts/clawmemory/",
            "===== systemPrompt ====="
    );
    private static final String FIRST_OUTPUT_TIMEOUT_ANCHOR =
            "LLM first-visible-output timeout after ";

    private static volatile boolean nativeLoaded;

    private DexKitTargetLocator() {
    }

    static DexDiscoveryHints discover(ClassLoader classLoader) {
        long started = System.nanoTime();
        ensureNativeLoaded();

        Set<String> allClasses = new LinkedHashSet<>();
        Set<String> agentTraceClasses = new LinkedHashSet<>();
        Set<String> promptClasses = new LinkedHashSet<>();
        int matchedMethods = 0;
        try (DexKitBridge bridge = DexKitBridge.create(classLoader, false)) {
            MethodMatcher[] anchors = METHOD_ANCHORS.stream()
                    .map(name -> MethodMatcher.create().name(name))
                    .toArray(MethodMatcher[]::new);
            List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().anyOf(anchors)
            ));
            matchedMethods += anchorMethods.size();
            addDeclaringClasses(anchorMethods, allClasses);

            List<MethodData> timeoutMethods = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(FIRST_OUTPUT_TIMEOUT_ANCHOR)
            ));
            matchedMethods += timeoutMethods.size();
            addDeclaringClasses(timeoutMethods, allClasses);

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

            for (String anchor : PROMPT_STRING_ANCHORS) {
                List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(anchor)
                ));
                matchedMethods += methods.size();
                addDeclaringClasses(methods, promptClasses);
                addRelatedDeclaringClasses(methods, promptClasses);
            }
            List<MethodData> invalidators = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().name("invalidateMemoryCache")
            ));
            matchedMethods += invalidators.size();
            addDeclaringClasses(invalidators, promptClasses);
            addRelatedDeclaringClasses(invalidators, promptClasses);
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return new DexDiscoveryHints(
                List.copyOf(allClasses),
                agentTraceClasses,
                List.copyOf(promptClasses),
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
