package io.github.finall1008.xiaoaimcp.hook;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PromptHookResolver {
    private static final String KNOWN_DESCRIPTOR = "cd.d";
    private static final String KNOWN_PROMPT_MANAGER = "s4.p1";

    private final ClassLoader classLoader;
    private final List<String> classNames;
    private final Map<String, Class<?>> loadedClasses = new HashMap<>();

    private PromptHookResolver(ClassLoader classLoader, List<String> classNames) {
        this.classLoader = classLoader;
        this.classNames = classNames;
    }

    static PromptHookTargets resolveKnown(ClassLoader classLoader) {
        try {
            PromptHookResolver resolver = new PromptHookResolver(classLoader, List.of());
            DescriptorCandidate descriptor = resolver.descriptorCandidate(
                    resolver.load(KNOWN_DESCRIPTOR));
            if (descriptor == null) {
                return null;
            }
            InvalidatorCandidate invalidator = resolver.knownInvalidator();
            Class<?> manager = resolver.load(KNOWN_PROMPT_MANAGER);
            return targets("verified-profile", descriptor,
                    findCallSites(List.of(manager), descriptor.resolve().getDeclaringClass()),
                    invalidator);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static PromptHookTargets resolve(
            ClassLoader classLoader,
            ClassCatalog catalog,
            DexDiscoveryHints hints
    ) throws ResolutionException {
        PromptHookTargets known = resolveKnown(classLoader);
        if (known != null) {
            return known;
        }
        if (hints != null && hints.hasPromptHints()) {
            try {
                return new PromptHookResolver(classLoader, hints.promptClassNames())
                        .resolveStructurally("dexkit-discovery");
            } catch (ResolutionException ignored) {
                // The complete class catalog remains authoritative.
            }
        }
        try {
            return new PromptHookResolver(classLoader, catalog.classNames())
                    .resolveStructurally("structural-discovery");
        } catch (ResolutionException error) {
            throw error;
        } catch (Throwable error) {
            throw new ResolutionException("Unable to inspect prompt target classes", error);
        }
    }

    private PromptHookTargets resolveStructurally(String mode) throws ResolutionException {
        List<DescriptorCandidate> descriptors = new ArrayList<>();
        List<InvalidatorCandidate> invalidators = new ArrayList<>();
        List<Class<?>> inspectedClasses = new ArrayList<>();
        for (String className : classNames) {
            if (shouldSkip(className)) {
                continue;
            }
            Class<?> candidate = tryLoad(className);
            if (candidate == null) {
                continue;
            }
            inspectedClasses.add(candidate);
            DescriptorCandidate descriptor = descriptorCandidate(candidate);
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
            InvalidatorCandidate invalidator = invalidatorCandidate(candidate);
            if (invalidator != null) {
                invalidators.add(invalidator);
            }
        }
        if (descriptors.size() != 1) {
            throw new ResolutionException(
                    "Prompt resolver discovery was ambiguous: descriptors=" + descriptors.size());
        }
        if (invalidators.isEmpty()) {
            InvalidatorCandidate external = externalInvalidator(inspectedClasses);
            if (external != null) {
                invalidators.add(external);
            }
        }
        InvalidatorCandidate invalidator = invalidators.size() == 1
                ? invalidators.get(0) : null;
        DescriptorCandidate descriptor = descriptors.get(0);
        return targets(mode, descriptor,
                findCallSites(inspectedClasses, descriptor.resolve().getDeclaringClass()),
                invalidator);
    }

    private InvalidatorCandidate knownInvalidator() throws Exception {
        Class<?> manager = load(KNOWN_PROMPT_MANAGER);
        for (Field field : manager.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) {
                continue;
            }
            Method method = findInvalidator(value.getClass());
            if (method != null) {
                return new InvalidatorCandidate(value, method);
            }
        }
        return null;
    }

    private static DescriptorCandidate descriptorCandidate(Class<?> candidate) {
        Field fileName = null;
        int stringFields = 0;
        for (Field field : candidate.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                stringFields++;
                fileName = field;
            }
        }
        if (stringFields != 1 || !hasDescriptorConstructor(candidate)) {
            return null;
        }
        Method resolve = null;
        for (Method method : candidate.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != String.class
                    || parameters.length != 4
                    || parameters[0].isPrimitive()
                    || parameters[1] != String.class
                    || parameters[2] != File.class
                    || parameters[3] != boolean.class) {
                continue;
            }
            if (resolve != null) {
                return null;
            }
            resolve = method;
        }
        return resolve == null ? null : new DescriptorCandidate(resolve, fileName);
    }

    private static boolean hasDescriptorConstructor(Class<?> candidate) {
        for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == String.class
                    && parameters[1].isEnum()) {
                return true;
            }
        }
        return false;
    }

    private static InvalidatorCandidate invalidatorCandidate(Class<?> candidate) {
        Method method = findInvalidator(candidate);
        if (method == null) {
            return null;
        }
        if (Modifier.isStatic(method.getModifiers())) {
            return new InvalidatorCandidate(null, method);
        }
        for (Field field : candidate.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == candidate) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return new InvalidatorCandidate(value, method);
                    }
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static InvalidatorCandidate externalInvalidator(List<Class<?>> classes) {
        InvalidatorCandidate match = null;
        for (Class<?> invalidatorType : classes) {
            Method method = findInvalidator(invalidatorType);
            if (method == null || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            for (Class<?> owner : classes) {
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            || !invalidatorType.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (value == null) {
                            continue;
                        }
                        InvalidatorCandidate found = new InvalidatorCandidate(value, method);
                        if (match != null && match.instance() != found.instance()) {
                            return null;
                        }
                        match = found;
                    } catch (Throwable ignored) {
                        // An inaccessible singleton is not safe to invoke.
                    }
                }
            }
        }
        return match;
    }

    private static Method findInvalidator(Class<?> candidate) {
        try {
            Method method = candidate.getDeclaredMethod("invalidateMemoryCache");
            return method.getReturnType() == void.class ? method : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static PromptHookTargets targets(
            String mode,
            DescriptorCandidate descriptor,
            List<Method> callSites,
            InvalidatorCandidate invalidator
    ) {
        return new PromptHookTargets(
                mode,
                descriptor.resolve(),
                descriptor.fileName(),
                callSites,
                invalidator == null ? null : invalidator.instance(),
                invalidator == null ? null : invalidator.method()
        );
    }

    private static List<Method> findCallSites(
            List<Class<?>> classes,
            Class<?> descriptorType
    ) {
        List<Method> callSites = new ArrayList<>();
        for (Class<?> candidate : classes) {
            for (Method method : candidate.getDeclaredMethods()) {
                if (method.getReturnType() != String.class) {
                    continue;
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (parameter == descriptorType) {
                        callSites.add(method);
                        break;
                    }
                }
            }
        }
        return callSites;
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    private Class<?> tryLoad(String name) {
        if (loadedClasses.containsKey(name)) {
            return loadedClasses.get(name);
        }
        try {
            Class<?> loaded = load(name);
            loadedClasses.put(name, loaded);
            return loaded;
        } catch (Throwable ignored) {
            loadedClasses.put(name, null);
            return null;
        }
    }

    private static boolean shouldSkip(String name) {
        return name.startsWith("android.")
                || name.startsWith("androidx.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("kotlin.")
                || name.startsWith("kotlinx.");
    }

    private record DescriptorCandidate(Method resolve, Field fileName) {
    }

    private record InvalidatorCandidate(Object instance, Method method) {
    }

    static final class ResolutionException extends Exception {
        ResolutionException(String message) {
            super(message);
        }

        ResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
