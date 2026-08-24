package io.github.finall1008.xiaoaimcp.hook;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FilePolicyHookResolver {
    private static final String KNOWN_URI_RESOLVER = "ff.e1";
    private static final String KNOWN_STORAGE = "ff.k";
    private static final String KNOWN_LOCKSCREEN_ALLOWLIST = "g8.m";
    private static final String KNOWN_TOOL_CALL = "q4.v";
    private static final String KNOWN_RISK_MANAGER = "t4.d";

    private final ClassLoader classLoader;
    private final List<String> classNames;
    private final Map<String, Class<?>> loadedClasses = new HashMap<>();

    private FilePolicyHookResolver(ClassLoader classLoader, List<String> classNames) {
        this.classLoader = classLoader;
        this.classNames = classNames;
    }

    static FilePolicyHookTargets resolve(ClassLoader classLoader, ClassCatalog catalog)
            throws ResolutionException {
        try {
            FilePolicyHookTargets known = new FilePolicyHookResolver(
                    classLoader, List.of()).resolveKnown();
            if (known != null) {
                return known;
            }
        } catch (Throwable ignored) {
            // Stable names are only a fast path. Structural discovery remains authoritative.
        }

        try {
            return new FilePolicyHookResolver(
                    classLoader, catalog.classNames()).resolveStructurally();
        } catch (ResolutionException error) {
            throw error;
        } catch (Throwable error) {
            throw new ResolutionException("Unable to inspect file-policy target classes", error);
        }
    }

    private FilePolicyHookTargets resolveKnown() throws Exception {
        Class<?> uriResolver = load(KNOWN_URI_RESOLVER);
        Class<?> storage = load(KNOWN_STORAGE);
        Class<?> allowlist = load(KNOWN_LOCKSCREEN_ALLOWLIST);
        Class<?> toolCall = load(KNOWN_TOOL_CALL);
        RiskCandidate risk = riskCandidate(load(KNOWN_RISK_MANAGER));

        Method resolve = findUriResolve(uriResolver);
        Method external = storage.getDeclaredMethod("isExternalUserAsset", String.class);
        Method allowed = allowlist.getDeclaredMethod("isAllowed", toolCall);
        Method cli = allowlist.getDeclaredMethod("f", String.class, List.class);
        Method cliPublic = allowlist.getDeclaredMethod(
                "isCommandNameAllowed", String.class, List.class);
        Method getName = toolCall.getDeclaredMethod("getName");
        Method getArguments = toolCall.getDeclaredMethod("getArguments");
        if (resolve == null
                || external.getReturnType() != boolean.class
                || allowed.getReturnType() != boolean.class
                || cli.getReturnType() != boolean.class
                || cliPublic.getReturnType() != boolean.class
                || getName.getReturnType() != String.class
                || !Map.class.isAssignableFrom(getArguments.getReturnType())
                || risk == null) {
            return null;
        }
        return targets("verified-profile", uriResolver, resolve, external,
                allowed, List.of(cli, cliPublic), getName, getArguments, risk);
    }

    private FilePolicyHookTargets resolveStructurally() throws ResolutionException {
        List<MutationCandidate> mutationCandidates = new ArrayList<>();
        List<LockscreenCandidate> lockscreenCandidates = new ArrayList<>();
        List<RiskCandidate> riskCandidates = new ArrayList<>();

        for (String className : classNames) {
            if (shouldSkip(className)) {
                continue;
            }
            Class<?> candidate = tryLoad(className);
            if (candidate == null) {
                continue;
            }
            MutationCandidate mutation = mutationCandidate(candidate);
            if (mutation != null) {
                mutationCandidates.add(mutation);
            }
            LockscreenCandidate lockscreen = lockscreenCandidate(candidate);
            if (lockscreen != null) {
                lockscreenCandidates.add(lockscreen);
            }
            RiskCandidate risk = riskCandidate(candidate);
            if (risk != null) {
                riskCandidates.add(risk);
            }
        }

        MutationCandidate mutation = mutationCandidates.size() == 1
                ? mutationCandidates.get(0) : null;
        LockscreenCandidate lockscreen = lockscreenCandidates.size() == 1
                ? lockscreenCandidates.get(0) : null;
        RiskCandidate risk = riskCandidates.size() == 1
                ? riskCandidates.get(0) : null;
        if (mutation == null && lockscreen == null && risk == null) {
            throw new ResolutionException(
                    "File-policy discovery was ambiguous: mutation=" + mutationCandidates.size()
                            + ", lockscreen=" + lockscreenCandidates.size()
                            + ", confirmation=" + riskCandidates.size());
        }
        return new FilePolicyHookTargets(
                "structural-discovery",
                mutation == null ? null : accessible(mutation.resolve()),
                mutation == null ? null : accessible(mutation.externalUserAsset()),
                mutation == null ? List.of() : accessible(mutation.callSites()),
                lockscreen == null ? null : accessible(lockscreen.isAllowed()),
                lockscreen == null ? List.of() : accessible(lockscreen.cliMatchers()),
                lockscreen == null ? null : accessible(lockscreen.getName()),
                lockscreen == null ? null : accessible(lockscreen.getArguments()),
                risk == null ? null : accessible(risk.exemption()),
                risk == null ? null : accessible(risk.getAgentId()),
                risk == null ? null : accessible(risk.getSharedState()),
                risk == null ? null : accessible(risk.getSessionId()),
                risk == null ? null : accessible(risk.getFirst()),
                risk == null ? null : accessible(risk.getSecond())
        );
    }

    private MutationCandidate mutationCandidate(Class<?> uriResolver) {
        Method resolve = findUriResolve(uriResolver);
        Method resolveToFile = resolve == null ? null
                : findMethod(uriResolver, "resolveToFile", resolve.getParameterTypes());
        Method listRoot = findMethod(uriResolver, "listVirtualRoot");
        if (resolve == null
                || resolveToFile == null
                || listRoot == null
                || !List.class.isAssignableFrom(listRoot.getReturnType())) {
            return null;
        }
        Class<?> storage = resolve.getParameterTypes()[3];
        Method external = findMethod(storage, "isExternalUserAsset", String.class);
        Method privateProtection = findMethod(storage,
                "checkPrivateDataWriteProtection", File.class);
        Method crossGroup = findMethod(storage,
                "checkCrossGroupOperation", File.class, File.class);
        if (external == null
                || external.getReturnType() != boolean.class
                || privateProtection == null
                || privateProtection.getReturnType().isPrimitive()
                || crossGroup == null
                || crossGroup.getReturnType().isPrimitive()) {
            return null;
        }
        List<Method> callSites = methodsWithParameter(uriResolver, storage);
        return callSites.isEmpty() ? null
                : new MutationCandidate(resolve, external, callSites);
    }

    private LockscreenCandidate lockscreenCandidate(Class<?> allowlist) {
        Method publicCli = findMethod(
                allowlist, "isCommandNameAllowed", String.class, List.class);
        if (publicCli == null || publicCli.getReturnType() != boolean.class) {
            return null;
        }
        Method isAllowed = null;
        Method getName = null;
        Method getArguments = null;
        for (Method method : allowlist.getDeclaredMethods()) {
            if (!method.getName().equals("isAllowed")
                    || method.getParameterCount() != 1
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            Class<?> toolCall = method.getParameterTypes()[0];
            Method candidateName = findMethod(toolCall, "getName");
            Method candidateArguments = findMethod(toolCall, "getArguments");
            if (candidateName != null
                    && candidateName.getReturnType() == String.class
                    && candidateArguments != null
                    && Map.class.isAssignableFrom(candidateArguments.getReturnType())) {
                if (isAllowed != null) {
                    return null;
                }
                isAllowed = method;
                getName = candidateName;
                getArguments = candidateArguments;
            }
        }
        if (isAllowed == null) {
            return null;
        }
        List<Method> cliMatchers = new ArrayList<>();
        for (Method method : allowlist.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == boolean.class
                    && parameters.length == 2
                    && parameters[0] == String.class
                    && List.class.isAssignableFrom(parameters[1])) {
                cliMatchers.add(method);
            }
        }
        return cliMatchers.isEmpty() ? null
                : new LockscreenCandidate(isAllowed, cliMatchers, getName, getArguments);
    }

    private RiskCandidate riskCandidate(Class<?> manager) {
        if (findMethodNamed(manager, "checkToolRisk", 5) == null
                || findMethodNamed(manager, "checkCliCommand", 4) == null
                || findMethodNamed(manager, "confirmByCategory$runtime", 4) == null
                || findMethodNamed(manager, "requestConsent$runtime", 5) == null) {
            return null;
        }
        RiskCandidate match = null;
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != boolean.class
                    || parameters.length != 4
                    || !List.class.isAssignableFrom(parameters[0])
                    || parameters[3] != String.class) {
                continue;
            }
            Method getAgentId = findPublicOrDeclaredMethod(parameters[1], "getAgentId");
            Method getSharedState = findPublicOrDeclaredMethod(parameters[1], "getSharedState");
            Method getSessionId = findPublicOrDeclaredMethod(parameters[1], "getSessionId");
            Method getFirst = findPublicOrDeclaredMethod(parameters[2], "getFirst");
            Method getSecond = findPublicOrDeclaredMethod(parameters[2], "getSecond");
            if (getAgentId == null
                    || getAgentId.getReturnType() != String.class
                    || getSharedState == null
                    || !Map.class.isAssignableFrom(getSharedState.getReturnType())
                    || getSessionId == null
                    || getSessionId.getReturnType() != String.class
                    || getFirst == null
                    || getSecond == null) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = new RiskCandidate(method, getAgentId, getSharedState, getSessionId,
                    getFirst, getSecond);
        }
        return match;
    }

    private static Method findUriResolve(Class<?> candidate) {
        Method method = findMethodNamed(candidate, "resolve", 5);
        if (method == null) {
            return null;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters[0] == String.class
                && parameters[1] == String.class
                && parameters[2] == File.class
                && !parameters[3].isPrimitive()
                && parameters[4] == boolean.class
                && !method.getReturnType().isPrimitive()
                ? method : null;
    }

    private static List<Method> methodsWithParameter(Class<?> type, Class<?> parameterType) {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter == parameterType) {
                    methods.add(method);
                    break;
                }
            }
        }
        return methods;
    }

    private static Method findMethodNamed(Class<?> type, String name, int parameterCount) {
        Method match = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)
                    || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        return match;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findPublicOrDeclaredMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return findMethod(type, name);
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

    private Class<?> load(String name) throws ClassNotFoundException {
        Class<?> cached = loadedClasses.get(name);
        if (cached != null) {
            return cached;
        }
        Class<?> loaded = Class.forName(name, false, classLoader);
        loadedClasses.put(name, loaded);
        return loaded;
    }

    private Class<?> tryLoad(String name) {
        try {
            return load(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FilePolicyHookTargets targets(
            String mode,
            Class<?> uriResolver,
            Method resolve,
            Method external,
            Method allowed,
            List<Method> cli,
            Method getName,
            Method getArguments,
            RiskCandidate risk
    ) {
        return new FilePolicyHookTargets(
                mode,
                accessible(resolve),
                accessible(external),
                accessible(methodsWithParameter(uriResolver, external.getDeclaringClass())),
                accessible(allowed),
                accessible(cli),
                accessible(getName),
                accessible(getArguments),
                accessible(risk.exemption()),
                accessible(risk.getAgentId()),
                accessible(risk.getSharedState()),
                accessible(risk.getSessionId()),
                accessible(risk.getFirst()),
                accessible(risk.getSecond())
        );
    }

    private static Method accessible(Method method) {
        if (method != null) {
            method.setAccessible(true);
        }
        return method;
    }

    private static List<Method> accessible(List<Method> methods) {
        for (Method method : methods) {
            accessible(method);
        }
        return List.copyOf(methods);
    }

    record MutationCandidate(Method resolve, Method externalUserAsset, List<Method> callSites) {
    }

    record LockscreenCandidate(
            Method isAllowed,
            List<Method> cliMatchers,
            Method getName,
            Method getArguments
    ) {
    }

    record RiskCandidate(
            Method exemption,
            Method getAgentId,
            Method getSharedState,
            Method getSessionId,
            Method getFirst,
            Method getSecond
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
