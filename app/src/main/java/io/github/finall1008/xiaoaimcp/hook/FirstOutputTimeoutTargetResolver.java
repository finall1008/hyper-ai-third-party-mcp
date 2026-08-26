package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class FirstOutputTimeoutTargetResolver {
    private static final String KNOWN_LLM_AGENT = "i4.b";
    private static final String FIRST_OUTPUT = "FirstVisibleOutputTimeoutMs";
    private static final String STREAM_IDLE = "StreamIdleTimeoutMs";
    private static final String CALL_ABSOLUTE = "CallAbsoluteTimeoutMs";

    private FirstOutputTimeoutTargetResolver() {
    }

    static FirstOutputTimeoutTargets resolve(
            ClassLoader classLoader,
            ClassCatalog catalog,
            DexDiscoveryHints hints
    ) throws Exception {
        FirstOutputTimeoutTargets known = resolveKnown(classLoader);
        if (known != null) {
            return known;
        }

        LinkedHashSet<String> narrowed = new LinkedHashSet<>();
        if (hints != null) {
            narrowed.addAll(hints.classNames());
            narrowed.addAll(hints.agentTraceClassNames());
        }
        Method dexKitMatch = uniqueMatch(classLoader, List.copyOf(narrowed));
        if (dexKitMatch != null) {
            return new FirstOutputTimeoutTargets("dexkit-discovery", dexKitMatch);
        }

        Method structuralMatch = uniqueMatch(classLoader, catalog.classNames());
        if (structuralMatch != null) {
            return new FirstOutputTimeoutTargets("structural-discovery", structuralMatch);
        }
        throw new ResolutionException("No unique LlmAgent first-output timeout getter found");
    }

    static FirstOutputTimeoutTargets resolveKnown(ClassLoader classLoader) {
        try {
            Class<?> owner = Class.forName(KNOWN_LLM_AGENT, false, classLoader);
            Method getter = timeoutGetter(owner);
            return getter == null ? null : new FirstOutputTimeoutTargets(
                    "verified-profile",
                    getter
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method uniqueMatch(ClassLoader classLoader, List<String> names) {
        List<Method> matches = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            try {
                Method getter = timeoutGetter(Class.forName(name, false, classLoader));
                if (getter != null) {
                    matches.add(getter);
                    if (matches.size() > 1) {
                        return null;
                    }
                }
            } catch (Throwable ignored) {
                // A candidate that cannot be loaded is not authoritative.
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Method timeoutGetter(Class<?> owner) {
        Method firstOutputGetter = uniqueMethod(owner, "get" + FIRST_OUTPUT, 0, long.class);
        Method firstOutputSetter = uniqueMethod(owner, "set" + FIRST_OUTPUT, 1, void.class);
        Method idleGetter = uniqueMethod(owner, "get" + STREAM_IDLE, 0, long.class);
        Method idleSetter = uniqueMethod(owner, "set" + STREAM_IDLE, 1, void.class);
        Method absoluteGetter = uniqueMethod(owner, "get" + CALL_ABSOLUTE, 0, long.class);
        Method absoluteSetter = uniqueMethod(owner, "set" + CALL_ABSOLUTE, 1, void.class);
        if (firstOutputGetter == null || firstOutputSetter == null
                || idleGetter == null || idleSetter == null
                || absoluteGetter == null || absoluteSetter == null
                || firstOutputSetter.getParameterTypes()[0] != long.class
                || idleSetter.getParameterTypes()[0] != long.class
                || absoluteSetter.getParameterTypes()[0] != long.class) {
            return null;
        }
        return firstOutputGetter;
    }

    private static Method uniqueMethod(
            Class<?> owner,
            String baseName,
            int parameterCount,
            Class<?> returnType
    ) {
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            String name = method.getName();
            if ((!name.equals(baseName) && !name.startsWith(baseName + "$"))
                    || method.getParameterCount() != parameterCount
                    || method.getReturnType() != returnType) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    static final class ResolutionException extends Exception {
        ResolutionException(String message) {
            super(message);
        }
    }
}
