package io.github.finall1008.xiaoaimcp.hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
                        if (initialize) {
                            try {
                                initializeForApplication((Context) chain.getArg(0));
                            } catch (Throwable error) {
                                log(Log.ERROR, TAG,
                                        "Hook initialization failed; host behavior is unchanged",
                                        error);
                                detach();
                            }
                        }
                        try {
                            return chain.proceed();
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
        ResolvedHookTargets targets = HookTargetResolver.resolve(
                hostClassLoader,
                new DexClassCatalog(applicationInfo)
        );
        remotePreferences = getRemotePreferences(BridgeContract.PREF_GROUP);
        installHooks(targets);
        log(Log.INFO, TAG, "XiaoAi " + versionName + " accepted (minimum 8.0); resolver="
                + targets.mode());
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
