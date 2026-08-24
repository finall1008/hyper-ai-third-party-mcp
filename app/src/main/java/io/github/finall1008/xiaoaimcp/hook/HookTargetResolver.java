package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class HookTargetResolver {
    private static final String KNOWN_MANAGER = "l8.w1";
    private static final String KNOWN_SERVER = "r8.h";
    private static final String KNOWN_SERVERS = "r8.k";
    private static final String SYNC_METHOD = "syncConfigAndDiscoverIfNeeded";
    private static final String RELOAD_METHOD = "reloadConfig";
    private static final String CATALOG_METHOD = "loadCatalogAndRegister";

    private final ClassLoader classLoader;
    private final List<String> classNames;
    private final Map<String, Class<?>> loadedClasses = new HashMap<>();

    private HookTargetResolver(ClassLoader classLoader, List<String> classNames) {
        this.classLoader = classLoader;
        this.classNames = classNames;
    }

    static ResolvedHookTargets resolve(ClassLoader classLoader, ClassCatalog catalog)
            throws ResolutionException {
        try {
            HookTargetResolver resolver = new HookTargetResolver(classLoader, List.of());
            ResolvedHookTargets known = resolver.resolveKnown();
            if (known != null) {
                return known;
            }
        } catch (Throwable ignored) {
            // The verified profile is only a fast path. Structural discovery handles relocated names.
        }

        try {
            HookTargetResolver resolver = new HookTargetResolver(classLoader, catalog.classNames());
            return resolver.resolveStructurally();
        } catch (ResolutionException error) {
            throw error;
        } catch (Throwable error) {
            throw new ResolutionException("Unable to inspect target classes", error);
        }
    }

    private ResolvedHookTargets resolveKnown() {
        try {
            Class<?> manager = load(KNOWN_MANAGER);
            Class<?> server = load(KNOWN_SERVER);
            Class<?> servers = load(KNOWN_SERVERS);
            Method text = manager.getDeclaredMethod("J");
            Method object = manager.getDeclaredMethod("A");
            Method sync = manager.getDeclaredMethod(SYNC_METHOD);
            Method reload = uniqueSuspendMethod(manager, RELOAD_METHOD);
            Method catalog = uniqueSuspendMethod(manager, CATALOG_METHOD);
            ObjectConfigAdapter adapter = resolveObjectAdapter(servers, server);
            if (!isTextReader(text)
                    || object.getParameterCount() != 0
                    || object.getReturnType() != servers
                    || !isSyncMethod(sync)
                    || !isSuspendMethod(reload)
                    || !isSuspendMethod(catalog)
                    || !haveCompatibleSuspendParameters(reload, catalog)
                    || adapter == null) {
                return null;
            }
            return new ResolvedHookTargets(
                    "verified-profile",
                    manager,
                    text,
                    object,
                    sync,
                    reload,
                    catalog,
                    suspendParameter(reload),
                    adapter
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ResolvedHookTargets resolveStructurally() throws ResolutionException {
        List<Class<?>> managers = new ArrayList<>();
        for (String className : classNames) {
            if (shouldSkip(className)) {
                continue;
            }
            Class<?> candidate = tryLoad(className);
            if (candidate != null && hasManagerAnchors(candidate)) {
                managers.add(candidate);
            }
        }
        if (managers.size() != 1) {
            throw new ResolutionException("Expected one MCP manager candidate, found "
                    + managers.size());
        }

        Class<?> manager = managers.get(0);
        Method syncCandidate = findDeclaredMethod(manager, SYNC_METHOD);
        Method reloadCandidate = uniqueSuspendMethod(manager, RELOAD_METHOD);
        Method catalogCandidate = uniqueSuspendMethod(manager, CATALOG_METHOD);
        Method sync = isSyncMethod(syncCandidate) ? syncCandidate : null;
        Method reload = isSuspendMethod(reloadCandidate) ? reloadCandidate : null;
        Method catalog = isSuspendMethod(catalogCandidate) ? catalogCandidate : null;
        if (!haveCompatibleSuspendParameters(reload, catalog)) {
            reload = null;
            catalog = null;
        }
        Method text = uniqueTextReader(manager);

        Method object = null;
        ObjectConfigAdapter adapter = null;
        List<Method> objectReaders = new ArrayList<>();
        for (Method method : manager.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && findContainerShape(method.getReturnType()) != null) {
                objectReaders.add(method);
            }
        }
        if (objectReaders.size() == 1) {
            Method candidate = objectReaders.get(0);
            Class<?> serverClass = findServerClass(
                    findContainerShape(candidate.getReturnType()).getAllServers());
            if (serverClass != null) {
                ObjectConfigAdapter candidateAdapter = resolveObjectAdapter(
                        candidate.getReturnType(), serverClass);
                if (candidateAdapter != null) {
                    object = candidate;
                    adapter = candidateAdapter;
                }
            }
        }

        if (text == null && object == null) {
            throw new ResolutionException("MCP manager has no uniquely resolvable config reader");
        }
        return new ResolvedHookTargets(
                "structural-discovery",
                manager,
                text,
                object,
                sync,
                reload,
                catalog,
                suspendParameter(reload != null ? reload : catalog),
                adapter
        );
    }

    private Class<?> findServerClass(Method getAllServers) {
        Class<?> genericType = genericServerType(getAllServers);
        if (genericType != null && hasServerShape(genericType)) {
            return genericType;
        }
        List<Class<?>> candidates = new ArrayList<>();
        for (String className : classNames) {
            if (shouldSkip(className)) {
                continue;
            }
            Class<?> candidate = tryLoad(className);
            if (candidate != null && hasServerShape(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private boolean hasManagerAnchors(Class<?> candidate) {
        Method sync = findDeclaredMethod(candidate, SYNC_METHOD);
        Method reload = uniqueSuspendMethod(candidate, RELOAD_METHOD);
        Method catalog = uniqueSuspendMethod(candidate, CATALOG_METHOD);
        if (!haveCompatibleSuspendParameters(reload, catalog)) {
            return false;
        }
        int anchors = 0;
        if (isSyncMethod(sync)) {
            anchors++;
        }
        if (isSuspendMethod(reload)) {
            anchors++;
        }
        if (isSuspendMethod(catalog)) {
            anchors++;
        }
        return anchors >= 2;
    }

    private static Method uniqueTextReader(Class<?> manager) {
        Method match = null;
        for (Method method : manager.getDeclaredMethods()) {
            if (!isTextReader(method)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    private static ObjectConfigAdapter resolveObjectAdapter(
            Class<?> serversClass,
            Class<?> serverClass
    ) {
        ContainerShape container = findContainerShape(serversClass);
        if (container == null) {
            return null;
        }
        Method getName = findDeclaredMethod(serverClass, "getName");
        if (getName == null
                || getName.getParameterCount() != 0
                || getName.getReturnType() != String.class) {
            return null;
        }
        List<Constructor<?>> serverConstructors = new ArrayList<>();
        for (Constructor<?> constructor : serverClass.getDeclaredConstructors()) {
            if (isSupportedServerConstructor(constructor)) {
                serverConstructors.add(constructor);
            }
        }
        if (serverConstructors.size() != 1) {
            return null;
        }
        return new ObjectConfigAdapter(
                serverConstructors.get(0),
                container.constructor(),
                container.getAllServers(),
                container.getGatewayMode(),
                getName
        );
    }

    private static ContainerShape findContainerShape(Class<?> candidate) {
        if (candidate == void.class || candidate.isPrimitive() || candidate == String.class) {
            return null;
        }
        Method allServers = findDeclaredMethod(candidate, "getAllServers");
        Method gatewayMode = findDeclaredMethod(candidate, "getGatewayMode");
        if (allServers == null
                || allServers.getParameterCount() != 0
                || !List.class.isAssignableFrom(allServers.getReturnType())
                || gatewayMode == null
                || gatewayMode.getParameterCount() != 0
                || gatewayMode.getReturnType() != boolean.class) {
            return null;
        }
        try {
            Constructor<?> constructor = candidate.getDeclaredConstructor(
                    List.class, Map.class, boolean.class);
            return new ContainerShape(constructor, allServers, gatewayMode);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Class<?> genericServerType(Method getAllServers) {
        Type returnType = getAllServers.getGenericReturnType();
        if (!(returnType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] arguments = parameterizedType.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        if (arguments[0] instanceof Class<?> serverClass) {
            return serverClass;
        }
        return null;
    }

    private static boolean isSupportedServerConstructor(Constructor<?> constructor) {
        Class<?>[] parameters = constructor.getParameterTypes();
        return parameters.length == 17
                && parameters[0] == String.class
                && parameters[1] == String.class
                && !parameters[2].isPrimitive()
                && parameters[3] == String.class
                && parameters[4] == String.class
                && parameters[5] == List.class
                && parameters[6] == Map.class
                && parameters[7] == String.class
                && parameters[8] == boolean.class
                && parameters[9] == Map.class
                && parameters[10] == Long.class
                && parameters[11] == int.class
                && parameters[12] == boolean.class
                && parameters[13] == boolean.class
                && parameters[14] == List.class
                && parameters[15] == String.class
                && parameters[16] == String.class;
    }

    private static boolean hasServerShape(Class<?> candidate) {
        Method getName = findDeclaredMethod(candidate, "getName");
        if (getName == null
                || getName.getParameterCount() != 0
                || getName.getReturnType() != String.class) {
            return false;
        }
        int supportedConstructors = 0;
        for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
            if (isSupportedServerConstructor(constructor)) {
                supportedConstructors++;
            }
        }
        return supportedConstructors == 1;
    }

    private static boolean isTextReader(Method method) {
        return method != null
                && method.getParameterCount() == 0
                && method.getReturnType() == String.class;
    }

    private static boolean isSyncMethod(Method method) {
        return method != null
                && method.getParameterCount() == 0
                && method.getReturnType() == void.class;
    }

    private static boolean isSuspendMethod(Method method) {
        return method != null
                && method.getParameterCount() == 1
                && !method.getParameterTypes()[0].isPrimitive()
                && method.getReturnType() == Object.class;
    }

    private static Method uniqueSuspendMethod(Class<?> type, String name) {
        Method match = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || !isSuspendMethod(method)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    private static boolean haveCompatibleSuspendParameters(Method first, Method second) {
        return first == null
                || second == null
                || suspendParameter(first) == suspendParameter(second);
    }

    private static Class<?> suspendParameter(Method method) {
        return method == null ? null : method.getParameterTypes()[0];
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Class<?> load(String className) throws ClassNotFoundException {
        Class<?> existing = loadedClasses.get(className);
        if (existing != null) {
            return existing;
        }
        Class<?> loaded = Class.forName(className, false, classLoader);
        loadedClasses.put(className, loaded);
        return loaded;
    }

    private Class<?> tryLoad(String className) {
        try {
            return load(className);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldSkip(String className) {
        return className.startsWith("android.")
                || className.startsWith("androidx.")
                || className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("kotlin.")
                || className.startsWith("kotlinx.");
    }

    private record ContainerShape(
            Constructor<?> constructor,
            Method getAllServers,
            Method getGatewayMode
    ) {
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
