package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AgentSessionTraceTargetResolver {
    private static final String LEGACY_EXECUTOR = "g6.b";

    private final ClassLoader classLoader;
    private final List<String> classNames;

    private AgentSessionTraceTargetResolver(
            ClassLoader classLoader,
            List<String> classNames
    ) {
        this.classLoader = classLoader;
        this.classNames = new ArrayList<>(new LinkedHashSet<>(classNames));
    }

    static AgentSessionTraceTargets resolveKnown(ClassLoader classLoader) {
        try {
            Class<?> owner = Class.forName(LEGACY_EXECUTOR, false, classLoader);
            return targets("verified-8.0.30", owner);
        } catch (Throwable ignored) {
            return unavailable("known-unavailable");
        }
    }

    static AgentSessionTraceTargets resolve(
            ClassLoader classLoader,
            ClassCatalog catalog,
            DexDiscoveryHints hints
    ) throws Exception {
        AgentSessionTraceTargets known = resolveKnown(classLoader);
        if (known.available()) {
            return withCallSites(known, classLoader, catalog, hints);
        }
        if (hints != null) {
            ArrayList<String> narrowed = new ArrayList<>();
            narrowed.addAll(hints.agentTraceClassNames());
            narrowed.addAll(hints.classNames());
            AgentSessionTraceTargets dexKit = new AgentSessionTraceTargetResolver(
                    classLoader,
                    narrowed
            ).resolveUnique("dexkit-discovery");
            if (dexKit.available()) {
                return withCallSites(dexKit, classLoader, catalog, hints);
            }
        }
        AgentSessionTraceTargets structural = new AgentSessionTraceTargetResolver(
                classLoader,
                catalog.classNames()
        ).resolveUnique("structural-discovery");
        return withCallSites(structural, classLoader, catalog, hints);
    }

    private static AgentSessionTraceTargets withCallSites(
            AgentSessionTraceTargets targets,
            ClassLoader classLoader,
            ClassCatalog catalog,
            DexDiscoveryHints hints
    ) throws Exception {
        if (!targets.available()) {
            return targets;
        }
        List<Method> callSites = hintedCallSites(classLoader, hints);
        if (callSites.isEmpty()) {
            callSites = structuralCallSites(
                    classLoader,
                    catalog.classNames(),
                    targets.execute()
            );
        }
        return new AgentSessionTraceTargets(
                targets.mode(),
                targets.execute(),
                targets.getAgentMeta(),
                targets.agentManagerField(),
                targets.agentManagerGet(),
                callSites
        );
    }

    private static List<Method> hintedCallSites(
            ClassLoader classLoader,
            DexDiscoveryHints hints
    ) {
        if (hints == null || hints.agentSessionCallSiteClassNames().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        for (String name : hints.agentSessionCallSiteClassNames()) {
            try {
                addDeclaredMethods(Class.forName(name, false, classLoader), methods);
            } catch (Throwable ignored) {
                // Other exact DexKit callers remain usable.
            }
        }
        return sorted(methods);
    }

    private static List<Method> structuralCallSites(
            ClassLoader classLoader,
            List<String> classNames,
            Method execute
    ) {
        Class<?> executorInterface = findExecutorInterface(execute);
        if (executorInterface == null) {
            return List.of();
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        ArrayList<Class<?>> loaded = new ArrayList<>();
        for (String name : new LinkedHashSet<>(classNames)) {
            Class<?> candidate;
            try {
                candidate = Class.forName(name, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            loaded.add(candidate);
            if (referencesType(candidate, executorInterface)) {
                roots.add(candidate.getName());
            }
        }
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        for (Class<?> candidate : loaded) {
            for (String root : roots) {
                if (candidate.getName().equals(root)
                        || candidate.getName().startsWith(root + "$")) {
                    addDeclaredMethods(candidate, methods);
                    break;
                }
            }
        }
        return sorted(methods);
    }

    private static Class<?> findExecutorInterface(Method execute) {
        for (Class<?> interfaceType : execute.getDeclaringClass().getInterfaces()) {
            for (Method method : interfaceType.getDeclaredMethods()) {
                if (sameSignature(execute, method)) {
                    return interfaceType;
                }
            }
        }
        return null;
    }

    private static boolean sameSignature(Method first, Method second) {
        return first.getName().equals(second.getName())
                && first.getReturnType() == second.getReturnType()
                && java.util.Arrays.equals(
                        first.getParameterTypes(),
                        second.getParameterTypes()
                );
    }

    private static boolean referencesType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type) {
                return true;
            }
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getReturnType() == type) {
                return true;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter == type) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addDeclaredMethods(Class<?> owner, Set<Method> destination) {
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isAbstract(method.getModifiers())
                    && !Modifier.isNative(method.getModifiers())) {
                destination.add(method);
            }
        }
    }

    private static List<Method> sorted(Set<Method> methods) {
        ArrayList<Method> result = new ArrayList<>(methods);
        result.sort(Comparator.comparing(Method::toGenericString));
        return List.copyOf(result);
    }

    private AgentSessionTraceTargets resolveUnique(String mode) {
        ArrayList<Class<?>> matches = new ArrayList<>();
        ArrayList<Class<?>> withCatalog = new ArrayList<>();
        for (String name : classNames) {
            Class<?> candidate;
            try {
                candidate = Class.forName(name, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            if (candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            if (findExecute(candidate) == null || findAgentMeta(candidate) == null) {
                continue;
            }
            matches.add(candidate);
            if (hasAgentManagerPath(candidate)) {
                withCatalog.add(candidate);
            }
        }
        List<Class<?>> preferred = withCatalog.isEmpty() ? matches : withCatalog;
        if (preferred.size() != 1) {
            return unavailable(preferred.isEmpty()
                    ? mode + "-unavailable" : mode + "-ambiguous");
        }
        return targets(mode, preferred.get(0));
    }

    private static boolean hasAgentManagerPath(Class<?> owner) {
        int matches = 0;
        for (Field field : owner.getDeclaredFields()) {
            if (findAgentManagerGet(field.getType()) != null) {
                matches++;
            }
        }
        return matches == 1;
    }

    private static AgentSessionTraceTargets targets(String mode, Class<?> owner) {
        Method execute = findExecute(owner);
        Method getAgentMeta = findAgentMeta(owner);
        if (execute == null || getAgentMeta == null) {
            return unavailable(mode + "-invalid");
        }
        Field managerField = null;
        Method managerGet = null;
        for (Field field : owner.getDeclaredFields()) {
            Method candidate = findAgentManagerGet(field.getType());
            if (candidate == null) {
                continue;
            }
            if (managerField != null) {
                managerField = null;
                managerGet = null;
                break;
            }
            managerField = field;
            managerGet = candidate;
        }
        return new AgentSessionTraceTargets(
                mode,
                execute,
                getAgentMeta,
                managerField,
                managerGet,
                List.of()
        );
    }

    private static Method findExecute(Class<?> owner) {
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("execute")
                    || parameters.length != 5
                    || parameters[0] != String.class
                    || method.getReturnType().isPrimitive()
                    || !hasMethod(parameters[1], "getText", 0)
                    || !hasMethod(parameters[1], "getAgentId", 0)
                    || !hasMethod(parameters[2], "getPromptOverride", 0)
                    || !hasMethod(parameters[2], "getTaskRole", 0)
                    || !parameters[3].isInterface()
                    || !hasMethod(parameters[3], "invoke", 2)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    private static Method findAgentMeta(Class<?> owner) {
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("getAgentMeta")
                    || parameters.length != 1
                    || parameters[0] != String.class
                    || method.getReturnType().isPrimitive()
                    || !hasMethod(method.getReturnType(), "getResolvedSystemPrompt", 0)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    private static Method findAgentManagerGet(Class<?> owner) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            Method match = null;
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                Class<?> result = method.getReturnType();
                if (!method.getName().equals("get")
                        || parameters.length != 1
                        || parameters[0] != String.class
                        || result.isPrimitive()
                        || !hasMethod(result, "getResolvedSystemPrompt", 0)
                        || !hasMethod(result, "getToolDefinitions", 0)) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = method;
            }
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static boolean hasMethod(Class<?> owner, String name, int parameterCount) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == parameterCount) {
                    return true;
                }
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                if (hasMethod(interfaceType, name, parameterCount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AgentSessionTraceTargets unavailable(String mode) {
        return new AgentSessionTraceTargets(mode, null, null, null, null, List.of());
    }
}
