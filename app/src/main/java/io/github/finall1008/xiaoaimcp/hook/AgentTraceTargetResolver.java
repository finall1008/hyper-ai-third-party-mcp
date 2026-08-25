package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class AgentTraceTargetResolver {
    private static final String KNOWN_REASONING_STATE =
            "com.xiaomi.voiceassist.miclaw.model.MiClawStreamingState$Reasoning";
    private static final String KNOWN_ENVELOPE_MAPPER = "ts0.c$b";
    private static final String KNOWN_REASONING_MAPPER = "vs0.f";
    private static final String KNOWN_TOOL_BUILDER = "us0.a";
    private static final String KNOWN_BUNDLE_LOADER = "rd1.y";

    private final ClassLoader classLoader;
    private final List<String> candidateNames;
    private final Map<String, Class<?>> loadedClasses = new HashMap<>();

    private AgentTraceTargetResolver(ClassLoader classLoader, List<String> candidateNames) {
        this.classLoader = classLoader;
        this.candidateNames = new ArrayList<>(new LinkedHashSet<>(candidateNames));
    }

    static AgentTraceTargets resolve(
            ClassLoader classLoader,
            ClassCatalog catalog,
            DexDiscoveryHints hints
    ) throws Exception {
        AgentTraceTargets known = resolveKnown(classLoader);
        if (known.hasAllCapabilities()) {
            return known;
        }

        List<String> narrowed = new ArrayList<>();
        if (hints != null) {
            narrowed.addAll(hints.agentTraceClassNames());
            narrowed.addAll(hints.classNames());
        }
        AgentTraceTargets dexKitTargets = narrowed.isEmpty()
                ? null
                : new AgentTraceTargetResolver(classLoader, narrowed)
                        .resolveStructurally("dexkit-discovery");
        if (dexKitTargets != null && dexKitTargets.hasAllCapabilities()) {
            return known.withFallback(dexKitTargets);
        }

        try {
            List<String> allClasses = catalog.classNames();
            AgentTraceTargets structural = new AgentTraceTargetResolver(classLoader, allClasses)
                    .resolveStructurally("structural-discovery");
            return known.withFallback(dexKitTargets).withFallback(structural);
        } catch (Throwable ignored) {
            return known.withFallback(dexKitTargets);
        }
    }

    static AgentTraceTargets resolveKnown(ClassLoader classLoader) {
        return new AgentTraceTargetResolver(classLoader, List.of()).resolveKnownProfile();
    }

    private AgentTraceTargets resolveKnownProfile() {
        Method reasoningSuppressor = null;
        Constructor<?> reasoningConstructor = null;
        Method reasoningResponseMapper = null;
        Method envelopeFrom = null;
        Method reasoningMapper = null;
        Method toolCallBuilder = null;
        Method toastStreamBuilder = null;
        Method bundleLoader = null;
        try {
            Class<?> reasoningState = load(KNOWN_REASONING_STATE);
            reasoningConstructor = reasoningState.getDeclaredConstructor(String.class);
            reasoningSuppressor = uniqueMethod(
                    List.of(load("cu0.e$d").getName()), this::reasoningSuppressor);
            reasoningResponseMapper = findReasoningResponseMapper(
                    load("cu0.e"), reasoningSuppressor);
            envelopeFrom = findEnvelopeFrom(load(KNOWN_ENVELOPE_MAPPER), reasoningState);
            reasoningMapper = findReasoningMapper(load(KNOWN_REASONING_MAPPER));
        } catch (Throwable ignored) {
            // Each capability is allowed to degrade independently.
        }
        try {
            Class<?> builder = load(KNOWN_TOOL_BUILDER);
            toolCallBuilder = findToolBuilder(builder);
            toastStreamBuilder = findToastStreamBuilder(builder);
        } catch (Throwable ignored) {
            // Tool detail expansion can fall back to the host summary.
        }
        try {
            bundleLoader = findBundleLoader(load(KNOWN_BUNDLE_LOADER));
        } catch (Throwable ignored) {
            // The host bundle remains loadable without the optional patch.
        }
        return accessible(new AgentTraceTargets(
                "verified-profile",
                reasoningSuppressor,
                reasoningConstructor,
                reasoningResponseMapper,
                envelopeFrom,
                reasoningMapper,
                toolCallBuilder,
                toastStreamBuilder,
                bundleLoader
        ));
    }

    private AgentTraceTargets resolveStructurally(String mode) {
        Method reasoningSuppressor = uniqueMethod(candidateNames, this::reasoningSuppressor);
        Class<?> reasoningOwner = reasoningSuppressor == null
                ? null : reasoningSuppressor.getDeclaringClass();
        Constructor<?> reasoningConstructor = findReasoningConstructor(candidateNames);
        Method reasoningResponseMapper = findReasoningResponseMapper(
                candidateNames, reasoningSuppressor);
        if (reasoningConstructor == null && reasoningOwner != null) {
            reasoningConstructor = findReasoningConstructor(
                    List.of(reasoningOwner.getName().replace("$d", "$Reasoning")));
        }
        Method envelopeFrom = findEnvelopeFrom(candidateNames, reasoningConstructor);
        Method reasoningMapper = findReasoningMapper(candidateNames);
        Method toolCallBuilder = uniqueMethod(candidateNames, this::toolBuilder);
        Method toastStreamBuilder = uniqueMethod(candidateNames, this::toastStreamBuilder);
        Method bundleLoader = uniqueMethod(candidateNames, this::bundleLoader);
        return accessible(new AgentTraceTargets(
                mode,
                reasoningSuppressor,
                reasoningConstructor,
                reasoningResponseMapper,
                envelopeFrom,
                reasoningMapper,
                toolCallBuilder,
                toastStreamBuilder,
                bundleLoader
        ));
    }

    private interface MethodPredicate {
        boolean matches(Method method);
    }

    private Method uniqueMethod(List<String> names, MethodPredicate predicate) {
        Method match = null;
        for (String name : names) {
            Class<?> candidate = tryLoad(name);
            if (candidate == null) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (!predicate.matches(method)) {
                    continue;
                }
                if (match != null && !sameSignature(match, method)) {
                    return null;
                }
                match = method;
            }
        }
        return match;
    }

    private boolean reasoningSuppressor(Method method) {
        if (!method.getName().equals("shouldSuppressReasoning")
                || method.getParameterCount() != 0
                || method.getReturnType() != boolean.class) {
            return false;
        }
        Class<?> owner = method.getDeclaringClass();
        return hasMethod(owner, "stripDelta", 1)
                && hasMethod(owner, "stripCompleted", 1)
                && hasMethod(owner, "flushPending", 0);
    }

    private boolean toolBuilder(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return method.getName().equals("buildToolCallItem")
                && parameters.length == 3
                && parameters[0] == String.class
                && parameters[1] == String.class
                && parameters[2] == String.class
                && !method.getReturnType().isPrimitive();
    }

    private boolean bundleLoader(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return method.getName().equals("loadScript")
                && parameters.length == 2
                && parameters[0] == String.class
                && parameters[1] == boolean.class
                && method.getReturnType() == boolean.class
                && hasMethodIncludingAncestors(
                        method.getDeclaringClass(), "getReactInstanceManager", 0)
                && hasMethodIncludingAncestors(
                        method.getDeclaringClass(), "getJavaScriptExecutorFactory", 0);
    }

    private boolean toastStreamBuilder(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return method.getName().equals("buildToastStream")
                && parameters.length == 2
                && parameters[0] == String.class
                && parameters[1] == String.class
                && !method.getReturnType().isPrimitive()
                && hasToolBuilder(method.getDeclaringClass());
    }

    private static boolean hasToolBuilder(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("buildToolCallItem")
                    && parameters.length == 3
                    && parameters[0] == String.class
                    && parameters[1] == String.class
                    && parameters[2] == String.class) {
                return true;
            }
        }
        return false;
    }

    private Constructor<?> findReasoningConstructor(List<String> names) {
        Constructor<?> match = null;
        for (String name : names) {
            Class<?> candidate = tryLoad(name);
            if (candidate == null || !candidate.getSimpleName().contains("Reasoning")) {
                continue;
            }
            for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 1 || parameters[0] != String.class
                        || !hasMethod(candidate, "getText", 0)) {
                    continue;
                }
                if (match != null && !sameSignature(match, constructor)) {
                    return null;
                }
                match = constructor;
            }
        }
        return match;
    }

    private Method findEnvelopeFrom(List<String> names, Constructor<?> reasoningConstructor) {
        Class<?> reasoningClass = reasoningConstructor == null
                ? null : reasoningConstructor.getDeclaringClass();
        if (reasoningClass == null) {
            return null;
        }
        Method match = null;
        for (String name : names) {
            Class<?> candidate = tryLoad(name);
            if (candidate == null) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals("from") || parameters.length != 1
                        || !parameters[0].isAssignableFrom(reasoningClass)
                        || !parameters[0].getName().contains("StreamingState")
                        || method.getReturnType().isPrimitive()) {
                    continue;
                }
                if (match != null && !sameSignature(match, method)) {
                    return null;
                }
                match = method;
            }
        }
        return match;
    }

    private Method findReasoningMapper(List<String> names) {
        Method match = null;
        for (String name : names) {
            Class<?> candidate = tryLoad(name);
            if (candidate == null) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals("map") || parameters.length != 2
                        || parameters[1] != String.class || method.getReturnType().isPrimitive()
                        || !hasMethod(parameters[0], "getText", 0)
                        || !hasMethod(method.getReturnType(), "getInstructions", 0)) {
                    continue;
                }
                if (match != null && !sameSignature(match, method)) {
                    return null;
                }
                match = method;
            }
        }
        return match;
    }

    private Method findReasoningResponseMapper(
            Class<?> owner,
            Method reasoningSuppressor
    ) {
        return owner == null ? null : findReasoningResponseMapper(
                List.of(owner.getName()), reasoningSuppressor);
    }

    private Method findReasoningResponseMapper(
            List<String> names,
            Method reasoningSuppressor
    ) {
        if (reasoningSuppressor == null) {
            return null;
        }
        Class<?> suppressorOwner = reasoningSuppressor.getDeclaringClass();
        Method match = null;
        for (String name : names) {
            Class<?> candidate = tryLoad(name);
            if (candidate == null) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 2
                        || parameters[1] != suppressorOwner
                        || !java.util.List.class.isAssignableFrom(method.getReturnType())
                        || !hasMethod(parameters[0], "getEventType", 0)
                        || !hasMethod(parameters[0], "getPayload", 0)) {
                    continue;
                }
                if (match != null && !sameSignature(match, method)) {
                    return null;
                }
                match = method;
            }
        }
        return match;
    }

    private Method findToolBuilder(Class<?> owner) {
        return owner == null ? null : uniqueMethod(List.of(owner.getName()), this::toolBuilder);
    }

    private Method findBundleLoader(Class<?> owner) {
        return owner == null ? null : uniqueMethod(List.of(owner.getName()), this::bundleLoader);
    }

    private Method findToastStreamBuilder(Class<?> owner) {
        return owner == null
                ? null : uniqueMethod(List.of(owner.getName()), this::toastStreamBuilder);
    }

    private Method findEnvelopeFrom(Class<?> owner, Class<?> reasoningClass) {
        if (owner == null || reasoningClass == null) {
            return null;
        }
        try {
            return findEnvelopeFrom(List.of(owner.getName()),
                    reasoningClass.getDeclaredConstructor(String.class));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Method findReasoningMapper(Class<?> owner) {
        return owner == null ? null : findReasoningMapper(List.of(owner.getName()));
    }

    private static boolean hasMethod(Class<?> owner, String name, int parameterCount) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethodIncludingAncestors(
            Class<?> owner,
            String name,
            int parameterCount
    ) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            if (hasMethod(current, name, parameterCount)) {
                return true;
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                if (hasMethodIncludingAncestors(interfaceType, name, parameterCount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameSignature(Method first, Method second) {
        if (!first.getName().equals(second.getName())) {
            return false;
        }
        Class<?>[] left = first.getParameterTypes();
        Class<?>[] right = second.getParameterTypes();
        if (left.length != right.length) {
            return false;
        }
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSignature(Constructor<?> first, Constructor<?> second) {
        Class<?>[] left = first.getParameterTypes();
        Class<?>[] right = second.getParameterTypes();
        if (left.length != right.length) {
            return false;
        }
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                return false;
            }
        }
        return first.getDeclaringClass() == second.getDeclaringClass();
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        Class<?> loaded = loadedClasses.get(name);
        if (loaded != null) {
            return loaded;
        }
        Class<?> resolved = Class.forName(name, false, classLoader);
        loadedClasses.put(name, resolved);
        return resolved;
    }

    private Class<?> tryLoad(String name) {
        try {
            return load(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AgentTraceTargets accessible(AgentTraceTargets targets) {
        makeAccessible(targets.reasoningSuppressor());
        makeAccessible(targets.reasoningConstructor());
        makeAccessible(targets.reasoningResponseMapper());
        makeAccessible(targets.envelopeFrom());
        makeAccessible(targets.reasoningMapper());
        makeAccessible(targets.toolCallBuilder());
        makeAccessible(targets.toastStreamBuilder());
        makeAccessible(targets.bundleLoader());
        return targets;
    }

    private static void makeAccessible(Object executable) {
        if (executable instanceof Method method) {
            method.setAccessible(true);
        } else if (executable instanceof Constructor<?> constructor) {
            constructor.setAccessible(true);
        }
    }
}
