package io.github.finall1008.xiaoaimcp.hook;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import io.github.finall1008.xiaoaimcp.config.McpConfigCodec;
import io.github.finall1008.xiaoaimcp.config.McpServer;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class XiaoAiMcpModule extends XposedModule {
    private static final String TAG = "XiaoAiMcpBridge";
    private static final String PERSONAL_MCP_MANAGER = "l8.w1";
    private static final String READ_CONFIG_OBJECT_METHOD = "A";
    private static final String READ_CONFIG_METHOD = "J";
    private static final String SYNC_METHOD = "syncConfigAndDiscoverIfNeeded";
    private static final String RELOAD_METHOD = "reloadConfig";

    private final AtomicReference<WeakReference<Object>> managerReference =
            new AtomicReference<>(new WeakReference<>(null));
    private final AtomicBoolean reloadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xiaoai-mcp-reload");
        thread.setDaemon(true);
        return thread;
    });

    private volatile SharedPreferences remotePreferences;
    private volatile boolean targetProcess;
    private volatile Method reloadMethod;
    private volatile Constructor<?> hostServerConfigConstructor;
    private volatile Constructor<?> hostServersConfigConstructor;
    private volatile Method hostConfigGetAllServers;
    private volatile Method hostConfigGetGatewayMode;
    private volatile Method hostServerGetName;
    private volatile Class<?> hostContinuationClass;
    private volatile Object hostEmptyCoroutineContext;
    private volatile Object hostCoroutineSuspended;
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
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!targetProcess
                || !param.isFirstPackage()
                || !BridgeContract.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            remotePreferences = getRemotePreferences(BridgeContract.PREF_GROUP);
            installPreferenceListener();
            installHooks(param.getClassLoader());
            log(Log.INFO, TAG, "Hooks installed after exact target signature verification for XiaoAi "
                    + BridgeContract.TARGET_VERSION_NAME + " (" + BridgeContract.TARGET_VERSION_CODE + ")");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Hook installation failed; host behavior is unchanged", error);
            detach();
        }
    }

    private void installHooks(ClassLoader hostClassLoader) throws Exception {
        Class<?> managerClass = Class.forName(PERSONAL_MCP_MANAGER, false, hostClassLoader);
        Method readConfigMethod = managerClass.getDeclaredMethod(READ_CONFIG_METHOD);
        Class<?> serverConfigClass = Class.forName("r8.h", false, hostClassLoader);
        Class<?> connectionConfigClass = Class.forName("r8.i", false, hostClassLoader);
        Class<?> serversConfigClass = Class.forName("r8.k", false, hostClassLoader);
        Method readConfigObjectMethod = managerClass.getDeclaredMethod(READ_CONFIG_OBJECT_METHOD);
        Method syncMethod = managerClass.getDeclaredMethod(SYNC_METHOD);
        Class<?> continuation = Class.forName(
                "kotlin.coroutines.Continuation",
                false,
                hostClassLoader
        );
        Method hostReloadMethod = managerClass.getDeclaredMethod(RELOAD_METHOD, continuation);
        Method loadCatalogMethod = managerClass.getDeclaredMethod(
                "loadCatalogAndRegister",
                continuation
        );

        Constructor<?> serverConstructor = serverConfigClass.getDeclaredConstructor(
                String.class,
                String.class,
                connectionConfigClass,
                String.class,
                String.class,
                List.class,
                Map.class,
                String.class,
                boolean.class,
                Map.class,
                Long.class,
                int.class,
                boolean.class,
                boolean.class,
                List.class,
                String.class,
                String.class
        );
        Constructor<?> serversConstructor = serversConfigClass.getDeclaredConstructor(
                List.class,
                Map.class,
                boolean.class
        );
        Method getAllServers = serversConfigClass.getDeclaredMethod("getAllServers");
        Method getGatewayMode = serversConfigClass.getDeclaredMethod("getGatewayMode");
        Method getServerName = serverConfigClass.getDeclaredMethod("getName");

        if (readConfigMethod.getParameterCount() != 0
                || readConfigMethod.getReturnType() != String.class
                || readConfigObjectMethod.getParameterCount() != 0
                || readConfigObjectMethod.getReturnType() != serversConfigClass
                || syncMethod.getParameterCount() != 0
                || syncMethod.getReturnType() != void.class
                || hostReloadMethod.getReturnType() != Object.class
                || loadCatalogMethod.getReturnType() != Object.class) {
            throw new NoSuchMethodException("Unexpected PersonalMcpManager method signature");
        }

        Class<?> emptyContextClass = Class.forName(
                "kotlin.coroutines.EmptyCoroutineContext",
                false,
                hostClassLoader
        );
        Field instanceField = emptyContextClass.getField("INSTANCE");
        Object emptyContext = instanceField.get(null);
        Class<?> intrinsics = Class.forName(
                "kotlin.coroutines.intrinsics.IntrinsicsKt",
                false,
                hostClassLoader
        );
        Object suspended = intrinsics.getMethod("getCOROUTINE_SUSPENDED").invoke(null);

        hostContinuationClass = continuation;
        hostEmptyCoroutineContext = emptyContext;
        hostCoroutineSuspended = suspended;
        reloadMethod = hostReloadMethod;
        hostServerConfigConstructor = serverConstructor;
        hostServersConfigConstructor = serversConstructor;
        hostConfigGetAllServers = getAllServers;
        hostConfigGetGatewayMode = getGatewayMode;
        hostServerGetName = getServerName;

        boolean syncDeoptimized = deoptimize(syncMethod);
        boolean reloadDeoptimized = deoptimize(hostReloadMethod);
        boolean catalogDeoptimized = deoptimize(loadCatalogMethod);
        log(Log.INFO, TAG, "Targeted deoptimization: sync=" + syncDeoptimized
                + ", reload=" + reloadDeoptimized + ", catalog=" + catalogDeoptimized);
        if (!syncDeoptimized || !reloadDeoptimized || !catalogDeoptimized) {
            throw new IllegalStateException("Unable to deoptimize MCP config callers");
        }

        hook(readConfigMethod)
                .setId("xiaoai-mcp-config-merge")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object original = chain.proceed();
                    if (!(original instanceof String hostConfig)) {
                        return original;
                    }
                    return mergeConfiguredServers(hostConfig);
                });

        hook(readConfigObjectMethod)
                .setId("xiaoai-mcp-config-object-merge")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> mergeConfiguredServerObjects(chain.proceed()));

        hook(syncMethod)
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
        Class<?> continuationClass = hostContinuationClass;
        if (manager == null || method == null || continuationClass == null) {
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
            Object continuation = Proxy.newProxyInstance(
                    continuationClass.getClassLoader(),
                    new Class<?>[]{continuationClass},
                    (proxy, calledMethod, args) -> switch (calledMethod.getName()) {
                        case "getContext" -> hostEmptyCoroutineContext;
                        case "resumeWith" -> {
                            completion.run();
                            yield null;
                        }
                        case "toString" -> "XiaoAiMcpReloadContinuation";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null ? null : args[0]);
                        default -> throw new UnsupportedOperationException(calledMethod.toString());
                    }
            );
            Object result = getInvoker(method)
                    .setType(XposedInterface.Invoker.Type.Chain.FULL)
                    .invoke(manager, continuation);
            if (result != hostCoroutineSuspended) {
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
