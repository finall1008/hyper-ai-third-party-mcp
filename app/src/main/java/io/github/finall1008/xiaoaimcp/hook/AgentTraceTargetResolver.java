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
    private static final KnownProfile CURRENT_PROFILE = new KnownProfile(
            "verified-8.2.3",
            "o61.e$e",
            "o61.e",
            "d51.c$b",
            "f51.g",
            "e51.a",
            "zq1.z"
    );
    private static final KnownProfile LEGACY_PROFILE = new KnownProfile(
            "verified-8.0.30",
            "cu0.e$d",
            "cu0.e",
            "ts0.c$b",
            "vs0.f",
            "us0.a",
            "rd1.y"
    );

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
        AgentTraceTargets current = resolveKnownProfile(CURRENT_PROFILE);
        return current.hasAllCapabilities()
                ? current
                : current.withFallback(resolveKnownProfile(LEGACY_PROFILE));
    }

    private AgentTraceTargets resolveKnownProfile(KnownProfile profile) {
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
                    List.of(load(profile.reasoningSuppressor()).getName()),
                    this::reasoningSuppressor);
            reasoningResponseMapper = findReasoningResponseMapper(
                    load(profile.reasoningResponseMapper()), reasoningSuppressor);
            envelopeFrom = findEnvelopeFrom(load(profile.envelopeMapper()), reasoningState);
            reasoningMapper = findReasoningMapper(load(profile.reasoningMapper()));
        } catch (Throwable ignored) {
            // Each capability is allowed to degrade independently.
        }
        try {
            Class<?> builder = load(profile.toolBuilder());
            toolCallBuilder = findToolBuilder(builder);
            toastStreamBuilder = findToastStreamBuilder(builder);
        } catch (Throwable ignored) {
            // Tool detail expansion can fall back to the host summary.
        }
        try {
            bundleLoader = findBundleLoader(load(profile.bundleLoader()));
        } catch (Throwable ignored) {
            // The host bundle remains loadable without the optional patch.
        }
        return accessible(new AgentTraceTargets(
                profile.mode(),
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
        Method bundleLoader = findBundleLoader(candidateNames);
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
        return hasReasoningTransformFamily(method.getDeclaringClass());
    }

    private static boolean hasReasoningTransformFamily(Class<?> owner) {
        Method delta = null;
        Method completed = null;
        Method flush = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if ((candidate.getName().equals("stripDelta")
                    || candidate.getName().equals("stripCompleted"))
                    && (parameters.length == 1 || parameters.length == 2)
                    && parameters[0] == String.class
                    && !candidate.getReturnType().isPrimitive()) {
                if (candidate.getName().equals("stripDelta")) {
                    delta = candidate;
                } else {
                    completed = candidate;
                }
            } else if (candidate.getName().equals("flushPending")
                    && parameters.length == 0
                    && !candidate.getReturnType().isPrimitive()) {
                flush = candidate;
            }
        }
        return delta != null && completed != null && flush != null
                && sameParameters(delta, completed)
                && delta.getReturnType() == completed.getReturnType()
                && delta.getReturnType() == flush.getReturnType();
    }

    private static boolean sameParameters(Method first, Method second) {
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
                && (parameters.length == 2 || parameters.length == 3)
                && parameters[0] == String.class
                && parameters[1] == boolean.class
                && (parameters.length != 3
                        || (!parameters[2].isPrimitive()
                        && parameters[2].getName().contains("ReactContext")))
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
        return owner == null ? null : findBundleLoader(List.of(owner.getName()));
    }

    private Method findBundleLoader(List<String> names) {
        Method twoParameter = null;
        Method threeParameter = null;
        for (String name : names) {
            Class<?> candidate = tryLoad(name);
            if (candidate == null) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (!bundleLoader(method)) {
                    continue;
                }
                if (method.getParameterCount() == 3) {
                    if (threeParameter != null && !sameSignature(threeParameter, method)) {
                        return null;
                    }
                    threeParameter = method;
                } else {
                    if (twoParameter != null && !sameSignature(twoParameter, method)) {
                        return null;
                    }
                    twoParameter = method;
                }
            }
        }
        // New XiaoAi delegates call the context-aware overload directly. Hooking it also
        // covers the two-parameter overload because that overload forwards into this one.
        return threeParameter != null ? threeParameter : twoParameter;
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

    private record KnownProfile(
            String mode,
            String reasoningSuppressor,
            String reasoningResponseMapper,
            String envelopeMapper,
            String reasoningMapper,
            String toolBuilder,
            String bundleLoader
    ) {
    }
}
