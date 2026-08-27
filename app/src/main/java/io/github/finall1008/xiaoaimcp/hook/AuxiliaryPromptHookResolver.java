package io.github.finall1008.xiaoaimcp.hook;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class AuxiliaryPromptHookResolver {
    private static final String KNOWN_TOOL_LOADER = "xd.o";
    private static final String KNOWN_MEMORY_LOADER = "bb.a";

    private final ClassLoader classLoader;
    private final List<String> classNames;

    private AuxiliaryPromptHookResolver(ClassLoader classLoader, List<String> classNames) {
        this.classLoader = classLoader;
        this.classNames = new ArrayList<>(new LinkedHashSet<>(classNames));
    }

    static AuxiliaryPromptHookTargets resolveKnown(ClassLoader classLoader) {
        Method tool = null;
        Method memory = null;
        try {
            tool = findNamed(Class.forName(KNOWN_TOOL_LOADER, false, classLoader),
                    "loadFromAssets", 3);
        } catch (Throwable ignored) {
            // Each Prompt source degrades independently.
        }
        try {
            memory = findNamed(Class.forName(KNOWN_MEMORY_LOADER, false, classLoader),
                    "loadSplit", 2);
        } catch (Throwable ignored) {
            // Each Prompt source degrades independently.
        }
        return new AuxiliaryPromptHookTargets("verified-profile", tool, memory);
    }

    static AuxiliaryPromptHookTargets resolve(
            ClassLoader classLoader,
            ClassCatalog catalog,
            DexDiscoveryHints hints
    ) throws Exception {
        AuxiliaryPromptHookTargets known = resolveKnown(classLoader);
        if (known.hasAllCapabilities()) {
            return known;
        }
        AuxiliaryPromptHookTargets dexKit = null;
        if (hints != null && hints.hasPromptHints()) {
            dexKit = new AuxiliaryPromptHookResolver(
                    classLoader, hints.promptClassNames()).resolveStructurally("dexkit-discovery");
            if (known.withFallback(dexKit).hasAllCapabilities()) {
                return known.withFallback(dexKit);
            }
        }
        AuxiliaryPromptHookTargets structural = new AuxiliaryPromptHookResolver(
                classLoader, catalog.classNames()).resolveStructurally("structural-discovery");
        AuxiliaryPromptHookTargets result = known.withFallback(dexKit).withFallback(structural);
        if (result.isEmpty()) {
            throw new IllegalStateException("Unable to resolve auxiliary Prompt loaders");
        }
        return result;
    }

    private AuxiliaryPromptHookTargets resolveStructurally(String mode) {
        Method tool = uniqueMethod(true);
        Method memory = uniqueMethod(false);
        return new AuxiliaryPromptHookTargets(mode, tool, memory);
    }

    private Method uniqueMethod(boolean tool) {
        Method match = null;
        for (String name : classNames) {
            Class<?> candidate;
            try {
                candidate = Class.forName(name, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (!(tool ? isToolLoader(method) : isMemoryLoader(method))) {
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

    private static boolean isToolLoader(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return Map.class.isAssignableFrom(method.getReturnType())
                && parameters.length == 3
                && parameters[1] == String.class
                && Map.class.isAssignableFrom(parameters[2])
                && hasSingleton(method.getDeclaringClass())
                && hasMapParser(method.getDeclaringClass())
                && !parameters[0].isPrimitive();
    }

    private static boolean isMemoryLoader(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return Map.class.isAssignableFrom(method.getReturnType())
                && parameters.length == 2
                && parameters[0] == String.class
                && Map.class.isAssignableFrom(parameters[1])
                && hasSingleton(method.getDeclaringClass())
                && hasContextInitializer(method.getDeclaringClass());
    }

    private static boolean hasSingleton(Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == owner) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMapParser(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Map.class.isAssignableFrom(method.getReturnType())
                    && parameters.length == 1 && parameters[0] == String.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasContextInitializer(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() == void.class && parameters.length == 1
                    && Context.class.isAssignableFrom(parameters[0])) {
                return true;
            }
        }
        return false;
    }

    private static Method findNamed(Class<?> owner, String name, int parameterCount) {
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount
                    || !Map.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    private static boolean sameSignature(Method first, Method second) {
        if (first.getDeclaringClass() != second.getDeclaringClass()
                || first.getReturnType() != second.getReturnType()) {
            return false;
        }
        return java.util.Arrays.equals(first.getParameterTypes(), second.getParameterTypes());
    }
}
