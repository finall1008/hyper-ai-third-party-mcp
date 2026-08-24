package io.github.finall1008.xiaoaimcp.hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.finall1008.xiaoaimcp.BridgeContract;
import io.github.finall1008.xiaoaimcp.TargetVersionPolicy;
import io.github.finall1008.xiaoaimcp.config.McpConfigCodec;
import io.github.finall1008.xiaoaimcp.config.McpServer;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyCodec;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyConfig;
import io.github.finall1008.xiaoaimcp.filepolicy.LockscreenFileAccessEvaluator;
import io.github.finall1008.xiaoaimcp.filepolicy.PathPolicyEvaluator;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class XiaoAiMcpModule extends XposedModule {
    private static final String TAG = "XiaoAiMcpBridge";

    private final AtomicReference<WeakReference<Object>> managerReference =
            new AtomicReference<>(new WeakReference<>(null));
    private final AtomicBoolean reloadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final AtomicBoolean initializationStarted = new AtomicBoolean(false);
    private final AtomicBoolean invalidFilePolicyLogged = new AtomicBoolean(false);
    private final ThreadLocal<ArrayDeque<String>> fileAgentContext =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xiaoai-mcp-reload");
        thread.setDaemon(true);
        return thread;
    });

    private volatile SharedPreferences remotePreferences;
    private volatile boolean targetProcess;
    private volatile XposedInterface.HookHandle bootstrapHook;
    private volatile Method reloadMethod;
    private volatile Constructor<?> hostServerConfigConstructor;
    private volatile Constructor<?> hostServersConfigConstructor;
    private volatile Method hostConfigGetAllServers;
    private volatile Method hostConfigGetGatewayMode;
    private volatile Method hostServerGetName;
    private volatile CoroutineAdapter coroutineAdapter;
    private volatile SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        targetProcess = BridgeContract.TARGET_PROCESS.equals(param.getProcessName());
        if (!targetProcess) {
            detach();
            return;
        }
        log(Log.INFO, TAG, "Loaded in target process with framework "
                + getFrameworkName() + " API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!targetProcess
                || !param.isFirstPackage()
                || !BridgeContract.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            bootstrapHook = hook(attach)
                    .setId("xiaoai-mcp-bootstrap")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        boolean initialize = initializationStarted.compareAndSet(false, true);
                        Context attachContext = initialize ? (Context) chain.getArg(0) : null;
                        try {
                            Object result = chain.proceed();
                            if (initialize) {
                                try {
                                    initializeForApplication(attachContext);
                                } catch (Throwable error) {
                                    log(Log.ERROR, TAG,
                                            "Hook initialization failed; host behavior is unchanged",
                                            error);
                                    detach();
                                }
                            }
                            return result;
                        } finally {
                            if (initialize) {
                                XposedInterface.HookHandle handle = bootstrapHook;
                                bootstrapHook = null;
                                if (handle != null) {
                                    try {
                                        handle.unhook();
                                    } catch (Throwable error) {
                                        log(Log.WARN, TAG, "Unable to remove bootstrap hook", error);
                                    }
                                }
                            }
                        }
                    });
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Unable to install target application bootstrap", error);
            detach();
        }
    }

    @SuppressWarnings("deprecation")
    private void initializeForApplication(Context context) throws Exception {
        PackageInfo packageInfo;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(
                    BridgeContract.TARGET_PACKAGE,
                    0
            );
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Target package information is unavailable", error);
        }
        String versionName = packageInfo.versionName;
        if (!TargetVersionPolicy.isSupported(versionName)) {
            log(Log.WARN, TAG, "XiaoAi " + String.valueOf(versionName)
                    + " is below or outside the supported 8.0+ version range; no hooks installed");
            detach();
            return;
        }

        ApplicationInfo applicationInfo = packageInfo.applicationInfo != null
                ? packageInfo.applicationInfo
                : context.getApplicationInfo();
        ClassLoader hostClassLoader = context.getClassLoader();
        ClassCatalog catalog = new CachingClassCatalog(new DexClassCatalog(applicationInfo));
        remotePreferences = getRemotePreferences(BridgeContract.PREF_GROUP);

        ResolvedHookTargets knownMcpTargets = HookTargetResolver.resolveKnown(hostClassLoader);
        FilePolicyHookTargets knownFileTargets = FilePolicyHookResolver.resolveKnown(
                hostClassLoader);
        DexDiscoveryHints dexHints = DexDiscoveryHints.empty();
        if (knownMcpTargets == null || knownFileTargets == null) {
            try {
                dexHints = DexKitTargetLocator.discover(hostClassLoader);
                log(Log.INFO, TAG, "DexKit discovery: classes=" + dexHints.classNames().size()
                        + ", managerHints=" + dexHints.mcpManagerClassNames().size()
                        + ", methods=" + dexHints.matchedMethods()
                        + ", elapsedMs=" + dexHints.elapsedMillis());
            } catch (Throwable error) {
                log(Log.WARN, TAG,
                        "DexKit discovery unavailable; using full structural discovery",
                        error);
            }
        }

        boolean mcpInstalled = false;
        String mcpResolver = "unavailable";
        try {
            ResolvedHookTargets targets = knownMcpTargets != null
                    ? knownMcpTargets
                    : HookTargetResolver.resolve(hostClassLoader, catalog, dexHints);
            installHooks(targets);
            mcpInstalled = true;
            mcpResolver = targets.mode();
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "MCP targets unavailable; independently resolved file-policy hooks remain eligible",
                    error);
        }
        boolean filePolicyInstalled = installFilePolicyHooks(
                hostClassLoader, catalog, knownFileTargets, dexHints);
        if (!mcpInstalled && !filePolicyInstalled) {
            throw new IllegalStateException("No compatible MCP or file-policy capability found");
        }
        log(Log.INFO, TAG, "XiaoAi " + versionName + " accepted (minimum 8.0); mcp="
                + mcpResolver + ", filePolicy=" + filePolicyInstalled);
    }

    private void installHooks(ResolvedHookTargets targets) throws Exception {
        ObjectConfigAdapter adapter = targets.objectAdapter();
        if (adapter != null) {
            hostServerConfigConstructor = adapter.serverConstructor();
            hostServersConfigConstructor = adapter.serversConstructor();
            hostConfigGetAllServers = adapter.getAllServers();
            hostConfigGetGatewayMode = adapter.getGatewayMode();
            hostServerGetName = adapter.getServerName();
        }

        boolean syncDeoptimized = tryDeoptimize(targets.syncMethod());
        boolean reloadDeoptimized = tryDeoptimize(targets.reloadMethod());
        boolean catalogDeoptimized = tryDeoptimize(targets.loadCatalogMethod());
        log(Log.INFO, TAG, "Targeted deoptimization: sync=" + syncDeoptimized
                + ", reload=" + reloadDeoptimized + ", catalog=" + catalogDeoptimized);

        boolean textInstalled = installTextHook(targets.textConfigMethod());
        boolean objectInstalled = installObjectHook(
                targets.hasObjectConfig() ? targets.objectConfigMethod() : null);
        if (!textInstalled && !objectInstalled) {
            throw new IllegalStateException("No MCP config reader hook could be installed");
        }

        boolean reloadInstalled = prepareReload(targets)
                && installManagerCaptureHook(targets.syncMethod());
        if (reloadInstalled) {
            try {
                installPreferenceListener();
            } catch (Throwable error) {
                log(Log.ERROR, TAG,
                        "Preference listener unavailable; configuration applies next startup",
                        error);
                reloadMethod = null;
                reloadInstalled = false;
            }
        }
        log(Log.INFO, TAG, "Hook capabilities: text=" + textInstalled
                + ", object=" + objectInstalled + ", liveReload=" + reloadInstalled);
    }

    private boolean installFilePolicyHooks(
            ClassLoader classLoader,
            ClassCatalog catalog,
            FilePolicyHookTargets knownTargets,
            DexDiscoveryHints dexHints
    ) {
        FilePolicyHookTargets targets;
        try {
            targets = knownTargets != null
                    ? knownTargets
                    : FilePolicyHookResolver.resolve(classLoader, catalog, dexHints);
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "File-policy targets unavailable; MCP hooks remain active and host file policy is unchanged",
                    error);
            return false;
        }

        boolean mutationInstalled = targets.hasMutationPolicy()
                && installExternalMutationHooks(targets);
        boolean lockscreenInstalled = targets.hasLockscreenPolicy()
                && installLockscreenFileHooks(targets);
        boolean confirmationInstalled = targets.hasConfirmationPolicy()
                && installMutationConfirmationHook(targets);
        log(Log.INFO, TAG, "File-policy capabilities: resolver=" + targets.mode()
                + ", mutation=" + mutationInstalled
                + ", lockscreen=" + lockscreenInstalled
                + ", confirmation=" + confirmationInstalled);
        return mutationInstalled || lockscreenInstalled || confirmationInstalled;
    }

    private boolean installExternalMutationHooks(FilePolicyHookTargets targets) {
        try {
            for (Method callSite : targets.uriCallSites()) {
                tryDeoptimize(callSite);
            }
            hook(targets.uriResolve())
                    .setId("xiaoai-file-policy-agent-context")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object value = chain.getArg(1);
                        String agentId = value instanceof String ? (String) value : "";
                        ArrayDeque<String> stack = fileAgentContext.get();
                        stack.push(agentId);
                        try {
                            return chain.proceed();
                        } finally {
                            stack.pop();
                            if (stack.isEmpty()) {
                                fileAgentContext.remove();
                            }
                        }
                    });
            hook(targets.externalUserAssetCheck())
                    .setId("xiaoai-file-policy-existing-mutation")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (!(original instanceof Boolean) || !((Boolean) original)) {
                            return original;
                        }
                        Object rawPath = chain.getArg(0);
                        if (!(rawPath instanceof String path)) {
                            return original;
                        }
                        ArrayDeque<String> stack = fileAgentContext.get();
                        String agentId = stack.peek();
                        if (agentId == null) {
                            return original;
                        }
                        try {
                            if (PathPolicyEvaluator.canMutate(
                                    loadFilePolicy(), new File(path), agentId)) {
                                log(Log.INFO, TAG, "Allowed existing external file mutation: path="
                                        + new File(path).getCanonicalPath()
                                        + ", agent=" + redactAgentId(agentId));
                                return false;
                            }
                        } catch (Throwable error) {
                            log(Log.WARN, TAG,
                                    "Existing external file mutation policy check failed", error);
                        }
                        return original;
                    });
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Existing external file mutation hooks unavailable", error);
            return false;
        }
    }

    private boolean installLockscreenFileHooks(FilePolicyHookTargets targets) {
        try {
            for (Method callSite
                    : targets.lockscreenToolAllowed().getDeclaringClass().getDeclaredMethods()) {
                tryDeoptimize(callSite);
            }
            for (Method matcher : targets.lockscreenCliMatchers()) {
                hook(matcher)
                        .setId("xiaoai-file-policy-lockscreen-cli-" + matcher.getName())
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object original = chain.proceed();
                            if (Boolean.TRUE.equals(original)) {
                                return true;
                            }
                            Object rawCommand = chain.getArg(0);
                            Object rawArgs = chain.getArg(1);
                            if (!(rawCommand instanceof String command)
                                    || !(rawArgs instanceof List<?> arguments)) {
                                return original;
                            }
                            List<String> stringArgs = new ArrayList<>(arguments.size());
                            for (Object argument : arguments) {
                                if (!(argument instanceof String value)) {
                                    return original;
                                }
                                stringArgs.add(value);
                            }
                            if (LockscreenFileAccessEvaluator.isCliCommandAllowed(
                                    loadFilePolicy(), command, stringArgs)) {
                                log(Log.INFO, TAG,
                                        "Allowed lockscreen file CLI command: " + command);
                                return true;
                            }
                            return original;
                        });
            }
            hook(targets.lockscreenToolAllowed())
                    .setId("xiaoai-file-policy-lockscreen-tool")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (Boolean.TRUE.equals(original)) {
                            return true;
                        }
                        Object toolCall = chain.getArg(0);
                        if (toolCall == null) {
                            return original;
                        }
                        Object rawName = targets.toolCallGetName().invoke(toolCall);
                        Object rawArguments = targets.toolCallGetArguments().invoke(toolCall);
                        if (!(rawName instanceof String toolName)
                                || !(rawArguments instanceof Map<?, ?> arguments)) {
                            return original;
                        }
                        Map<String, String> stringArguments = stringifyJsonArguments(arguments);
                        if (LockscreenFileAccessEvaluator.isDirectToolAllowed(
                                loadFilePolicy(), toolName, stringArguments)) {
                            log(Log.INFO, TAG,
                                    "Allowed lockscreen direct file tool: " + toolName);
                            return true;
                        }
                        return original;
                    });
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Lockscreen file hooks unavailable", error);
            return false;
        }
    }

    private boolean installMutationConfirmationHook(FilePolicyHookTargets targets) {
        try {
            for (Method callSite
                    : targets.riskFileExemption().getDeclaringClass().getDeclaredMethods()) {
                tryDeoptimize(callSite);
            }
            hook(targets.riskFileExemption())
                    .setId("xiaoai-file-policy-mutation-confirmation")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (Boolean.TRUE.equals(original)) {
                            return true;
                        }
                        Object rawPaths = chain.getArg(0);
                        Object context = chain.getArg(1);
                        if (!(rawPaths instanceof List<?> values) || context == null) {
                            return original;
                        }
                        List<String> paths = new ArrayList<>();
                        for (Object value : values) {
                            if (!(value instanceof String path)) {
                                return original;
                            }
                            addUniquePath(paths, path);
                        }
                        Object move = chain.getArg(2);
                        if (move != null) {
                            addReflectedPath(paths, targets.riskMoveGetFirst().invoke(move));
                            addReflectedPath(paths, targets.riskMoveGetSecond().invoke(move));
                        }
                        Object rawAgentId = targets.riskContextGetAgentId().invoke(context);
                        if (!(rawAgentId instanceof String agentId)) {
                            return original;
                        }
                        String executionIdentity = resolveExecutionIdentity(
                                targets, context, agentId);
                        if (PathPolicyEvaluator.canSkipMutationConfirmation(
                                loadFilePolicy(), paths, executionIdentity)) {
                            log(Log.INFO, TAG,
                                    "Automatically authorized file mutation confirmation: paths="
                                            + paths.size() + ", agent="
                                            + redactAgentId(executionIdentity));
                            return true;
                        }
                        log(Log.INFO, TAG,
                                "Retained host file mutation confirmation: paths=" + paths.size()
                                        + ", agent=" + redactAgentId(executionIdentity)
                                        + ", background="
                                        + PathPolicyEvaluator.isBackgroundAgent(
                                                executionIdentity));
                        return original;
                    });
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "File mutation confirmation hook unavailable", error);
            return false;
        }
    }

    private static void addReflectedPath(List<String> paths, Object value) {
        if (value instanceof String path) {
            addUniquePath(paths, path);
        }
    }

    private static String resolveExecutionIdentity(
            FilePolicyHookTargets targets,
            Object context,
            String agentId
    ) throws Exception {
        Object rawSharedState = targets.riskContextGetSharedState().invoke(context);
        if (rawSharedState instanceof Map<?, ?> sharedState) {
            Object rawAgentSubId = sharedState.get("agentSubId");
            if (rawAgentSubId instanceof String agentSubId && !agentSubId.isBlank()) {
                if (PathPolicyEvaluator.isBackgroundAgent(agentSubId)) {
                    return agentSubId;
                }
                agentId = agentSubId;
            }
        }
        Object rawSessionId = targets.riskContextGetSessionId().invoke(context);
        if (rawSessionId instanceof String sessionId
                && PathPolicyEvaluator.isBackgroundAgent(sessionId)) {
            return sessionId;
        }
        return agentId;
    }

    private static void addUniquePath(List<String> paths, String path) {
        if (!path.isBlank() && !paths.contains(path)) {
            paths.add(path);
        }
    }

    private FilePolicyConfig loadFilePolicy() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return FilePolicyConfig.disabled();
        }
        try {
            String json = preferences.getString(
                    BridgeContract.PREF_FILE_POLICY_JSON,
                    FilePolicyCodec.emptyConfig()
            );
            FilePolicyConfig config = FilePolicyCodec.parse(json);
            invalidFilePolicyLogged.set(false);
            return config;
        } catch (Throwable error) {
            if (invalidFilePolicyLogged.compareAndSet(false, true)) {
                log(Log.ERROR, TAG,
                        "Invalid file policy ignored; host restrictions remain active", error);
            }
            return FilePolicyConfig.disabled();
        }
    }

    private static Map<String, String> stringifyJsonArguments(Map<?, ?> arguments) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : arguments.entrySet()) {
            if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
                continue;
            }
            String value = jsonContent(entry.getValue());
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static String jsonContent(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            Method getContent = value.getClass().getMethod("getContent");
            Object content = getContent.invoke(value);
            return content instanceof String ? (String) content : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String redactAgentId(String agentId) {
        int isolated = agentId.indexOf("::");
        if (isolated < 0) {
            return agentId;
        }
        return agentId.substring(0, isolated)
                + (PathPolicyEvaluator.isBackgroundAgent(agentId) ? "::bg/..." : "::...");
    }

    private boolean installTextHook(Method method) {
        if (method == null) {
            return false;
        }
        try {
            hook(method)
                    .setId("xiaoai-mcp-config-merge")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (!(original instanceof String hostConfig)) {
                            return original;
                        }
                        return mergeConfiguredServers(hostConfig);
                    });
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Text config hook unavailable", error);
            return false;
        }
    }

    private boolean installObjectHook(Method method) {
        if (method == null) {
            return false;
        }
        try {
            hook(method)
                    .setId("xiaoai-mcp-config-object-merge")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> mergeConfiguredServerObjects(chain.proceed()));
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Object config hook unavailable", error);
            return false;
        }
    }

    private boolean prepareReload(ResolvedHookTargets targets) {
        if (targets.syncMethod() == null || targets.reloadMethod() == null) {
            return false;
        }
        try {
            coroutineAdapter = CoroutineAdapter.create(targets.continuationClass());
            reloadMethod = targets.reloadMethod();
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Live reload coroutine adapter unavailable", error);
            return false;
        }
    }

    private boolean installManagerCaptureHook(Method method) {
        try {
            hook(method)
                    .setId("xiaoai-mcp-manager-capture")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object manager = chain.getThisObject();
                        if (manager != null) {
                            managerReference.set(new WeakReference<>(manager));
                        }
                        Object result = chain.proceed();
                        if (reloadPending.get()) {
                            requestReload();
                        }
                        return result;
                    });
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Live reload manager capture unavailable", error);
            reloadMethod = null;
            return false;
        }
    }

    private boolean tryDeoptimize(Method method) {
        if (method == null) {
            return false;
        }
        try {
            return deoptimize(method);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to deoptimize " + method, error);
            return false;
        }
    }

    private String mergeConfiguredServers(String hostConfig) {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return hostConfig;
        }
        String moduleConfig = preferences.getString(
                BridgeContract.PREF_SERVERS_JSON,
                McpConfigCodec.emptyConfig()
        );
        try {
            List<McpServer> servers = McpConfigCodec.parseModuleConfig(moduleConfig);
            if (servers.isEmpty()) {
                return hostConfig;
            }
            String merged = McpConfigCodec.mergeHostConfig(hostConfig, moduleConfig);
            log(Log.INFO, TAG, "Merged " + servers.size() + " configured server(s): "
                    + McpConfigCodec.redactedForLog(servers));
            return merged;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Ignoring invalid module MCP configuration", error);
            return hostConfig;
        }
    }

    private Object mergeConfiguredServerObjects(Object hostConfig) {
        SharedPreferences preferences = remotePreferences;
        Constructor<?> serverConstructor = hostServerConfigConstructor;
        Constructor<?> serversConstructor = hostServersConfigConstructor;
        Method getAllServers = hostConfigGetAllServers;
        Method getGatewayMode = hostConfigGetGatewayMode;
        Method getServerName = hostServerGetName;
        if (hostConfig == null
                || preferences == null
                || serverConstructor == null
                || serversConstructor == null
                || getAllServers == null
                || getGatewayMode == null
                || getServerName == null) {
            return hostConfig;
        }

        String moduleConfig = preferences.getString(
                BridgeContract.PREF_SERVERS_JSON,
                McpConfigCodec.emptyConfig()
        );
        try {
            List<McpServer> moduleServers = McpConfigCodec.parseModuleConfig(moduleConfig);
            if (moduleServers.isEmpty()) {
                return hostConfig;
            }

            Set<String> moduleNames = new HashSet<>();
            for (McpServer server : moduleServers) {
                moduleNames.add(server.name());
            }

            Object originalListValue = getAllServers.invoke(hostConfig);
            if (!(originalListValue instanceof List<?> originalServers)) {
                throw new IllegalStateException("Host getAllServers did not return List");
            }
            List<Object> mergedServers = new ArrayList<>();
            for (Object originalServer : originalServers) {
                Object name = getServerName.invoke(originalServer);
                if (!(name instanceof String) || !moduleNames.contains(name)) {
                    mergedServers.add(originalServer);
                }
            }

            for (McpServer server : moduleServers) {
                Object hostServer = serverConstructor.newInstance(
                        server.name(),
                        server.url(),
                        null,
                        "",
                        "",
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        server.description(),
                        server.enabled(),
                        server.headers(),
                        null,
                        30,
                        true,
                        false,
                        Collections.emptyList(),
                        server.transport(),
                        ""
                );
                mergedServers.add(hostServer);
            }

            boolean gatewayMode = (boolean) getGatewayMode.invoke(hostConfig);
            Object mergedConfig = serversConstructor.newInstance(
                    mergedServers,
                    Collections.emptyMap(),
                    gatewayMode
            );
            log(Log.INFO, TAG, "Merged " + moduleServers.size()
                    + " configured server object(s): "
                    + McpConfigCodec.redactedForLog(moduleServers));
            return mergedConfig;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Ignoring invalid module MCP object configuration", error);
            return hostConfig;
        }
    }

    private void installPreferenceListener() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            throw new IllegalStateException("Remote Preferences unavailable");
        }
        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
            if (key == null || BridgeContract.PREF_SERVERS_JSON.equals(key)) {
                requestReload();
            }
        };
        preferenceListener = listener;
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private void requestReload() {
        reloadPending.set(true);
        if (reloadInFlight.compareAndSet(false, true)) {
            reloadExecutor.execute(this::startPendingReload);
        }
    }

    private void startPendingReload() {
        if (!reloadPending.getAndSet(false)) {
            finishReload();
            return;
        }
        Object manager = managerReference.get().get();
        Method method = reloadMethod;
        CoroutineAdapter adapter = coroutineAdapter;
        if (manager == null || method == null || adapter == null) {
            log(Log.INFO, TAG, "Configuration changed before MCP manager initialization; "
                    + "it will be applied on next XiaoAi startup");
            finishReload();
            return;
        }

        AtomicBoolean completionDelivered = new AtomicBoolean(false);
        Runnable completion = () -> {
            if (completionDelivered.compareAndSet(false, true)) {
                log(Log.INFO, TAG, "Personal MCP reload completed");
                finishReload();
            }
        };

        try {
            Object continuation = adapter.newContinuation(completion);
            Object result = getInvoker(method)
                    .setType(XposedInterface.Invoker.Type.Chain.FULL)
                    .invoke(manager, continuation);
            if (!CoroutineAdapter.isSuspended(result)) {
                completion.run();
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Personal MCP reload failed", error);
            completion.run();
        }
    }

    private void finishReload() {
        if (reloadPending.get()) {
            reloadExecutor.execute(this::startPendingReload);
            return;
        }
        reloadInFlight.set(false);
        if (reloadPending.get() && reloadInFlight.compareAndSet(false, true)) {
            reloadExecutor.execute(this::startPendingReload);
        }
    }
}
