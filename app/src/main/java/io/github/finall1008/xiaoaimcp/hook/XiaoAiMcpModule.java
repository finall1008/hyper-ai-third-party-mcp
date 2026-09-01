package io.github.finall1008.xiaoaimcp.hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.finall1008.xiaoaimcp.BridgeContract;
import io.github.finall1008.xiaoaimcp.TargetVersionPolicy;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyCodec;
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyConfig;
import io.github.finall1008.xiaoaimcp.filepolicy.LockscreenFileAccessEvaluator;
import io.github.finall1008.xiaoaimcp.filepolicy.PathPolicyEvaluator;
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchCodec;
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchConfig;
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchEngine;
import io.github.finall1008.xiaoaimcp.prompt.PromptPatchResult;
import io.github.finall1008.xiaoaimcp.prompt.PromptTargetType;
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutConfig;
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutRepository;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class XiaoAiMcpModule extends XposedModule {
    private static final String TAG = "XiaoAiMcpBridge";
    private static final int MAX_INITIALIZATION_IDENTITIES = 256;

    private final AtomicBoolean initializationStarted = new AtomicBoolean(false);
    private final AtomicBoolean invalidFilePolicyLogged = new AtomicBoolean(false);
    private final AtomicBoolean invalidPromptPatchLogged = new AtomicBoolean(false);
    private final AtomicBoolean agentTraceBundleFallbackLogged = new AtomicBoolean(false);
    private final AtomicBoolean agentSessionTraceRuntimeLogged = new AtomicBoolean(false);
    private final ThreadLocal<ArrayDeque<String>> fileAgentContext =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final AgentToolTraceStore agentToolTraceStore = new AgentToolTraceStore();
    private final ThreadLocal<Boolean> initializationReasoningPending = new ThreadLocal<>();
    private final Set<Object> initializationReasoningObjects =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> initializationReasoningEnvelopes =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private volatile SharedPreferences remotePreferences;
    private volatile boolean targetProcess;
    private volatile XposedInterface.HookHandle bootstrapHook;
    private volatile SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private volatile Object promptCacheInvalidator;
    private volatile Method promptCacheInvalidatorMethod;
    private volatile Method hostToastStreamBuilder;
    private volatile Object hostInstructionBuilder;
    private volatile Class<?> hostInstructionBuilderClass;
    private volatile AgentTraceCaptureWriter agentTraceCaptureWriter;

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
        boolean agentTraceEnabled = isAgentTraceEnabled();
        FirstOutputTimeoutConfig firstOutputTimeoutConfig = loadFirstOutputTimeoutConfig();

        FilePolicyHookTargets knownFileTargets = FilePolicyHookResolver.resolveKnown(
                hostClassLoader);
        PromptHookTargets knownPromptTargets = PromptHookResolver.resolveKnown(hostClassLoader);
        AuxiliaryPromptHookTargets knownAuxiliaryPromptTargets =
                AuxiliaryPromptHookResolver.resolveKnown(hostClassLoader);
        DexDiscoveryHints dexHints = DexDiscoveryHints.empty();
        AgentTraceTargets knownAgentTraceTargets = agentTraceEnabled
                ? AgentTraceTargetResolver.resolveKnown(hostClassLoader)
                : null;
        AgentSessionTraceTargets knownSessionTraceTargets = agentTraceEnabled
                ? AgentSessionTraceTargetResolver.resolveKnown(hostClassLoader)
                : null;
        FirstOutputTimeoutTargets knownFirstOutputTimeoutTargets =
                firstOutputTimeoutConfig.overridesHost()
                        ? FirstOutputTimeoutTargetResolver.resolveKnown(hostClassLoader)
                        : null;
        if (knownFileTargets == null || knownPromptTargets == null
                || !knownAuxiliaryPromptTargets.hasAllCapabilities()
                || (agentTraceEnabled && !knownAgentTraceTargets.hasAllCapabilities())
                || (agentTraceEnabled && !knownSessionTraceTargets.installable())
                || (firstOutputTimeoutConfig.overridesHost()
                        && knownFirstOutputTimeoutTargets == null)) {
            try {
                dexHints = DexKitTargetLocator.discover(hostClassLoader);
                log(Log.INFO, TAG, "DexKit discovery: classes=" + dexHints.classNames().size()
                        + ", agentTraceHints=" + dexHints.agentTraceClassNames().size()
                        + ", sessionCallSites="
                        + dexHints.agentSessionCallSiteClassNames().size()
                        + ", promptHints=" + dexHints.promptClassNames().size()
                        + ", methods=" + dexHints.matchedMethods()
                        + ", elapsedMs=" + dexHints.elapsedMillis());
            } catch (Throwable error) {
                log(Log.WARN, TAG,
                        "DexKit discovery unavailable; using full structural discovery",
                        error);
            }
        }

        boolean filePolicyInstalled = installFilePolicyHooks(
                hostClassLoader, catalog, knownFileTargets, dexHints);
        boolean promptInstalled = false;
        String promptResolver = "unavailable";
        try {
            PromptHookTargets promptTargets = knownPromptTargets != null
                    ? knownPromptTargets
                    : PromptHookResolver.resolve(hostClassLoader, catalog, dexHints);
            promptInstalled = installPromptHook(promptTargets);
            promptResolver = promptInstalled ? promptTargets.mode() : "unavailable";
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "System Prompt patch targets unavailable; other capabilities remain active",
                    error);
        }
        boolean auxiliaryPromptInstalled = false;
        String auxiliaryPromptResolver = "unavailable";
        try {
            AuxiliaryPromptHookTargets auxiliaryTargets =
                    knownAuxiliaryPromptTargets.hasAllCapabilities()
                            ? knownAuxiliaryPromptTargets
                            : AuxiliaryPromptHookResolver.resolve(
                                    hostClassLoader, catalog, dexHints);
            auxiliaryPromptInstalled = installAuxiliaryPromptHooks(auxiliaryTargets);
            auxiliaryPromptResolver = auxiliaryPromptInstalled
                    ? auxiliaryTargets.mode() : "unavailable";
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "Tool/Memory Prompt patch targets unavailable; Agent Prompt remains independent",
                    error);
        }
        boolean agentTraceInstalled = false;
        String agentTraceResolver = "disabled";
        String agentSessionTraceResolver = "disabled";
        if (agentTraceEnabled) {
            try {
                AgentTraceTargets agentTraceTargets = !knownAgentTraceTargets.hasAllCapabilities()
                        ? AgentTraceTargetResolver.resolve(hostClassLoader, catalog, dexHints)
                        : knownAgentTraceTargets;
                AgentTraceCapabilities capabilities = installAgentTraceHooks(
                        context, agentTraceTargets);
                agentTraceInstalled = capabilities.any();
                agentTraceResolver = agentTraceTargets.mode();
                log(Log.INFO, TAG, "Agent Trace capabilities: resolver=" + agentTraceResolver
                        + ", reasoning=" + capabilities.reasoning()
                        + ", toolDetails=" + capabilities.toolDetails()
                        + ", bundlePatch=" + capabilities.bundlePatch()
                        + ", initMarker=" + capabilities.initializationMarker());
            } catch (Throwable error) {
                agentTraceResolver = "unavailable";
                log(Log.WARN, TAG, "Agent Trace unavailable; host behavior is unchanged", error);
            }
            try {
                AgentSessionTraceTargets sessionTargets =
                        AgentSessionTraceTargetResolver.resolve(
                                hostClassLoader,
                                catalog,
                                dexHints
                        );
                boolean sessionCaptureInstalled = installAgentSessionTraceHook(
                        context,
                        sessionTargets
                );
                agentTraceInstalled = agentTraceInstalled || sessionCaptureInstalled;
                agentSessionTraceResolver = sessionTargets.mode();
                log(Log.INFO, TAG, "Agent session trace capture: resolver="
                        + agentSessionTraceResolver + ", callSites="
                        + sessionTargets.callSites().size() + ", installed="
                        + sessionCaptureInstalled);
            } catch (Throwable error) {
                agentSessionTraceResolver = "unavailable";
                log(Log.WARN, TAG,
                        "Agent session trace capture unavailable; host trace UI remains active",
                        error);
            }
        } else {
            log(Log.INFO, TAG, "Agent Trace disabled by module preference; no trace hooks installed");
        }
        boolean firstOutputTimeoutInstalled = false;
        String firstOutputTimeoutResolver = "host-default";
        if (firstOutputTimeoutConfig.overridesHost()) {
            try {
                FirstOutputTimeoutTargets timeoutTargets =
                        knownFirstOutputTimeoutTargets != null
                                ? knownFirstOutputTimeoutTargets
                                : FirstOutputTimeoutTargetResolver.resolve(
                                        hostClassLoader,
                                        catalog,
                                        dexHints
                                );
                firstOutputTimeoutInstalled = installFirstOutputTimeoutHook(
                        timeoutTargets,
                        firstOutputTimeoutConfig
                );
                firstOutputTimeoutResolver = firstOutputTimeoutInstalled
                        ? timeoutTargets.mode() : "unavailable";
            } catch (Throwable error) {
                firstOutputTimeoutResolver = "unavailable";
                log(Log.WARN, TAG,
                        "First-output timeout override unavailable; host timeout is unchanged",
                        error);
            }
        } else {
            log(Log.INFO, TAG,
                    "First-output timeout follows host default; no timeout Hook installed");
        }
        if (promptInstalled || auxiliaryPromptInstalled) {
            try {
                installPromptPreferenceListener();
            } catch (Throwable error) {
                log(Log.ERROR, TAG,
                        "Preference listener unavailable; configuration applies next startup",
                        error);
            }
        }
        if (!filePolicyInstalled && !agentTraceInstalled && !promptInstalled
                && !auxiliaryPromptInstalled
                && !firstOutputTimeoutInstalled) {
            throw new IllegalStateException("No compatible module capability found");
        }
        log(Log.INFO, TAG, "XiaoAi " + versionName + " accepted (minimum 8.0); filePolicy="
                + filePolicyInstalled
                + ", agentTrace=" + agentTraceResolver
                + ", agentSessionTrace=" + agentSessionTraceResolver
                + ", promptPatch=" + promptResolver
                + ", auxiliaryPromptPatch=" + auxiliaryPromptResolver
                + ", firstOutputTimeout=" + firstOutputTimeoutResolver);
    }

    private FirstOutputTimeoutConfig loadFirstOutputTimeoutConfig() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return FirstOutputTimeoutConfig.hostDefault();
        }
        try {
            return new FirstOutputTimeoutRepository(preferences).loadStrict();
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "Invalid first-output timeout configuration ignored; host timeout is unchanged",
                    error);
            return FirstOutputTimeoutConfig.hostDefault();
        }
    }

    private boolean installFirstOutputTimeoutHook(
            FirstOutputTimeoutTargets targets,
            FirstOutputTimeoutConfig config
    ) {
        try {
            long overrideMillis = config.resolveTimeoutMillis(0L);
            tryDeoptimize(targets.timeoutGetter());
            hook(targets.timeoutGetter())
                    .setId("xiaoai-first-output-timeout")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> overrideMillis);
            String configuredValue = overrideMillis == Long.MAX_VALUE
                    ? "unlimited" : overrideMillis + "ms";
            log(Log.INFO, TAG, "First-output timeout override installed: resolver="
                    + targets.mode() + ", value=" + configuredValue
                    + "; restart XiaoAi to apply future changes");
            return true;
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "First-output timeout Hook unavailable; host timeout is unchanged",
                    error);
            return false;
        }
    }

    private boolean installPromptHook(PromptHookTargets targets) {
        try {
            tryDeoptimize(targets.resolvePrompt());
            for (Method callSite : targets.callSites()) {
                tryDeoptimize(callSite);
            }
            promptCacheInvalidator = targets.cacheInvalidator();
            promptCacheInvalidatorMethod = targets.invalidateMemoryCache();
            hook(targets.resolvePrompt())
                    .setId("xiaoai-system-prompt-patch")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (!(original instanceof String prompt)) {
                            return original;
                        }
                        Object descriptor = chain.getThisObject();
                        Object rawAgentId = chain.getArg(1);
                        if (descriptor == null || !(rawAgentId instanceof String agentId)) {
                            return original;
                        }
                        Object rawFileName = targets.fileNameField().get(descriptor);
                        if (!(rawFileName instanceof String fileName)) {
                            return original;
                        }
                        PromptPatchConfig config = loadPromptPatchConfig();
                        if (config == null) {
                            return original;
                        }
                        PromptPatchResult result = PromptPatchEngine.apply(
                                prompt, agentId, fileName, config);
                        if (!result.appliedPatchIds().isEmpty()) {
                            log(Log.INFO, TAG, "Applied System Prompt patches: agent="
                                    + redactAgentId(agentId) + ", file=" + fileName
                                    + ", ids=" + result.appliedPatchIds());
                        }
                        for (PromptPatchResult.SkippedPatch skipped : result.skippedPatches()) {
                            log(Log.WARN, TAG, "Skipped System Prompt patch: agent="
                                    + redactAgentId(agentId) + ", file=" + fileName
                                    + ", id=" + skipped.id()
                                    + ", occurrences=" + skipped.occurrences());
                        }
                        return result.text();
                    });
            log(Log.INFO, TAG, "System Prompt patch capability: resolver=" + targets.mode()
                    + ", callSites=" + targets.callSites().size()
                    + ", cacheInvalidation=" + targets.canInvalidateMemoryCache());
            return true;
        } catch (Throwable error) {
            promptCacheInvalidator = null;
            promptCacheInvalidatorMethod = null;
            log(Log.ERROR, TAG, "System Prompt patch hook unavailable", error);
            return false;
        }
    }

    private boolean installAuxiliaryPromptHooks(AuxiliaryPromptHookTargets targets) {
        boolean toolInstalled = false;
        boolean memoryInstalled = false;
        if (targets.toolPromptLoader() != null) {
            try {
                tryDeoptimize(targets.toolPromptLoader());
                hook(targets.toolPromptLoader())
                        .setId("xiaoai-tool-prompt-patch")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object original = chain.proceed();
                            Object rawToolName = chain.getArg(1);
                            if (!(rawToolName instanceof String toolName)) {
                                return original;
                            }
                            return patchPromptSections(
                                    original, PromptTargetType.TOOL_PROMPT, toolName);
                        });
                toolInstalled = true;
            } catch (Throwable error) {
                log(Log.WARN, TAG,
                        "Tool Prompt patch Hook unavailable; tool descriptions remain unchanged",
                        error);
            }
        }
        if (targets.memoryPromptLoader() != null) {
            try {
                tryDeoptimize(targets.memoryPromptLoader());
                hook(targets.memoryPromptLoader())
                        .setId("xiaoai-memory-prompt-patch")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object original = chain.proceed();
                            Object rawPromptKey = chain.getArg(0);
                            if (!(rawPromptKey instanceof String promptKey)) {
                                return original;
                            }
                            return patchPromptSections(
                                    original, PromptTargetType.MEMORY_PROMPT, promptKey);
                        });
                memoryInstalled = true;
            } catch (Throwable error) {
                log(Log.WARN, TAG,
                        "Memory Prompt patch Hook unavailable; MemoryGate remains unchanged",
                        error);
            }
        }
        log(Log.INFO, TAG, "Auxiliary Prompt patch capability: resolver=" + targets.mode()
                + ", tool=" + toolInstalled + ", memory=" + memoryInstalled);
        return toolInstalled || memoryInstalled;
    }

    private Object patchPromptSections(
            Object original,
            PromptTargetType targetType,
            String targetId
    ) {
        if (!(original instanceof Map<?, ?> originalMap)) {
            return original;
        }
        PromptPatchConfig config = loadPromptPatchConfig();
        if (config == null) {
            return original;
        }
        Map<Object, Object> patchedMap = null;
        for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
            if (!(entry.getKey() instanceof String section)
                    || !(entry.getValue() instanceof String prompt)) {
                continue;
            }
            PromptPatchResult result = PromptPatchEngine.apply(
                    prompt, targetType, targetId, section, config);
            logPromptPatchResult(targetType, targetId, section, result);
            if (!result.appliedPatchIds().isEmpty()) {
                if (patchedMap == null) {
                    patchedMap = new LinkedHashMap<>(originalMap);
                }
                patchedMap.put(entry.getKey(), result.text());
            }
        }
        return patchedMap == null ? original : patchedMap;
    }

    private void logPromptPatchResult(
            PromptTargetType targetType,
            String targetId,
            String targetPart,
            PromptPatchResult result
    ) {
        if (!result.appliedPatchIds().isEmpty()) {
            log(Log.INFO, TAG, "Applied Prompt patches: type=" + targetType.serializedName()
                    + ", target=" + redactAgentId(targetId) + ", part=" + targetPart
                    + ", ids=" + result.appliedPatchIds());
        }
        for (PromptPatchResult.SkippedPatch skipped : result.skippedPatches()) {
            log(Log.WARN, TAG, "Skipped Prompt patch: type=" + targetType.serializedName()
                    + ", target=" + redactAgentId(targetId) + ", part=" + targetPart
                    + ", id=" + skipped.id() + ", occurrences=" + skipped.occurrences());
        }
    }

    private PromptPatchConfig loadPromptPatchConfig() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return PromptPatchConfig.defaults();
        }
        try {
            PromptPatchConfig config = PromptPatchCodec.parse(preferences.getString(
                    BridgeContract.PREF_PROMPT_PATCH_JSON,
                    PromptPatchCodec.defaultConfig()
            ));
            invalidPromptPatchLogged.set(false);
            return config;
        } catch (Throwable error) {
            if (invalidPromptPatchLogged.compareAndSet(false, true)) {
                log(Log.ERROR, TAG,
                        "Invalid System Prompt patch configuration ignored; host prompt is unchanged",
                        error);
            }
            return null;
        }
    }

    private boolean isAgentTraceEnabled() {
        SharedPreferences preferences = remotePreferences;
        return preferences == null
                ? BridgeContract.DEFAULT_AGENT_TRACE_ENABLED
                : preferences.getBoolean(
                        BridgeContract.PREF_AGENT_TRACE_ENABLED,
                        BridgeContract.DEFAULT_AGENT_TRACE_ENABLED
                );
    }

    private AgentTraceCapabilities installAgentTraceHooks(
            Context context,
            AgentTraceTargets targets
    ) {
        boolean reasoningInstalled = false;
        boolean initializationMarkerInstalled = false;
        boolean toolDetailsInstalled = false;
        boolean bundlePatchInstalled = false;

        if (targets.reasoningSuppressor() != null) {
            try {
                tryDeoptimize(targets.reasoningSuppressor());
                tryDeoptimize(targets.reasoningResponseMapper());
                hook(targets.reasoningSuppressor())
                        .setId("xiaoai-agent-trace-reasoning")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object original = chain.proceed();
                            if (Boolean.TRUE.equals(original)) {
                                initializationReasoningPending.set(Boolean.TRUE);
                                return false;
                            }
                            return original;
                        });
                reasoningInstalled = true;
            } catch (Throwable error) {
                log(Log.WARN, TAG, "公開 reasoning Hook unavailable", error);
            }
        }

        if (reasoningInstalled && targets.reasoningConstructor() != null) {
            try {
                hook(targets.reasoningConstructor())
                        .setId("xiaoai-agent-trace-reasoning-phase")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            boolean initialization = Boolean.TRUE.equals(
                                    initializationReasoningPending.get());
                            try {
                                Object result = chain.proceed();
                                if (initialization && chain.getThisObject() != null) {
                                    addBoundedIdentity(
                                            initializationReasoningObjects,
                                            chain.getThisObject()
                                    );
                                }
                                return result;
                            } finally {
                                if (initialization) {
                                    initializationReasoningPending.remove();
                                }
                            }
                        });
            } catch (Throwable error) {
                log(Log.WARN, TAG, "reasoning phase marker unavailable", error);
            }
        }

        if (targets.envelopeFrom() != null && targets.reasoningMapper() != null) {
            try {
                hook(targets.envelopeFrom())
                        .setId("xiaoai-agent-trace-reasoning-envelope")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object source = chain.getArg(0);
                            Object result = chain.proceed();
                            if (isInitializationObject(source) && result != null) {
                                addBoundedIdentity(initializationReasoningEnvelopes, result);
                            }
                            return result;
                        });
                hook(targets.reasoningMapper())
                        .setId("xiaoai-agent-trace-reasoning-marker")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object source = chain.getArg(0);
                            Object result = chain.proceed();
                            if (isInitializationEnvelope(source)) {
                                prependInitializationMarker(result, chain.getArg(1));
                            }
                            return result;
                        });
                initializationMarkerInstalled = true;
            } catch (Throwable error) {
                log(Log.WARN, TAG, "初始化 reasoning 标记 unavailable", error);
            }
        }

        if (targets.toolCallBuilder() != null) {
            try {
                hostInstructionBuilderClass = targets.toastStreamBuilder() != null
                        ? targets.toastStreamBuilder().getDeclaringClass()
                        : targets.toolCallBuilder().getDeclaringClass();
                hostToastStreamBuilder = targets.toastStreamBuilder();
                hook(targets.toolCallBuilder())
                        .setId("xiaoai-agent-trace-tool-details")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object rawEvent = chain.getArg(0);
                            Object rawPayload = chain.getArg(1);
                            Object rawDialogId = chain.getArg(2);
                            Object result = chain.proceed();
                            if (!(rawEvent instanceof String event)
                                    || !(rawPayload instanceof String payload)
                                    || !(rawDialogId instanceof String dialogId)) {
                                return result;
                            }
                            String merged = agentToolTraceStore.merge(dialogId, event, payload);
                            if (!merged.equals(payload)) {
                                patchToolInstructionPayload(result, merged);
                            }
                            return result;
                        });
                toolDetailsInstalled = true;
            } catch (Throwable error) {
                log(Log.WARN, TAG, "工具详情关联 Hook unavailable", error);
            }
        }

        if (targets.toolCallBuilder() == null && targets.toastStreamBuilder() != null) {
            hostInstructionBuilderClass = targets.toastStreamBuilder().getDeclaringClass();
            hostToastStreamBuilder = targets.toastStreamBuilder();
        }

        if (targets.bundleLoader() != null && context != null) {
            try {
                hook(targets.bundleLoader())
                        .setId("xiaoai-agent-trace-rn-bundle")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object rawPath = chain.getArg(0);
                            if (!(rawPath instanceof String path)) {
                                return chain.proceed();
                            }
                            try {
                                String patchedPath = AgentTraceBundlePatcher.patchPath(context, path);
                                if (!path.equals(patchedPath)) {
                                    Object[] arguments = new Object[
                                            targets.bundleLoader().getParameterCount()];
                                    arguments[0] = patchedPath;
                                    for (int index = 1; index < arguments.length; index++) {
                                        arguments[index] = chain.getArg(index);
                                    }
                                    return chain.proceedWith(
                                            chain.getThisObject(),
                                            arguments
                                    );
                                }
                                if (path.contains("stream.bundle")
                                        && !path.contains("xiaoai-agent-trace/")
                                        && agentTraceBundleFallbackLogged.compareAndSet(false, true)) {
                                    log(Log.INFO, TAG,
                                            "RN Agent Trace bundle shape not recognized; using host bundle");
                                }
                            } catch (Throwable error) {
                                log(Log.WARN, TAG, "RN Agent Trace bundle patch failed; using host bundle", error);
                            }
                            return chain.proceed();
                        });
                bundlePatchInstalled = true;
            } catch (Throwable error) {
                log(Log.WARN, TAG, "RN Agent Trace bundle Hook unavailable", error);
            }
        }

        return new AgentTraceCapabilities(
                reasoningInstalled,
                toolDetailsInstalled,
                bundlePatchInstalled,
                initializationMarkerInstalled
        );
    }

    private boolean installAgentSessionTraceHook(
            Context context,
            AgentSessionTraceTargets targets
    ) {
        if (context == null || targets == null || !targets.installable()) {
            return false;
        }
        try {
            AgentTraceCaptureWriter writer = agentTraceCaptureWriter;
            if (writer == null) {
                writer = new AgentTraceCaptureWriter(context);
                agentTraceCaptureWriter = writer;
            }
            AgentTraceCaptureWriter captureWriter = writer;
            Method execute = targets.execute();
            int deoptimized = tryDeoptimize(execute) ? 1 : 0;
            for (Method callSite : targets.callSites()) {
                if (tryDeoptimize(callSite)) {
                    deoptimized++;
                }
            }
            log(Log.INFO, TAG, "Agent session trace runtime prepared: deoptimized="
                    + deoptimized + "/" + (targets.callSites().size() + 1));
            hook(execute)
                    .setId("xiaoai-agent-session-trace")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (agentSessionTraceRuntimeLogged.compareAndSet(false, true)) {
                            log(Log.INFO, TAG, "Agent session trace executor intercepted");
                        }
                        Object rawAgentId = chain.getArg(0);
                        Object userInput = chain.getArg(1);
                        Object options = chain.getArg(2);
                        Object callback = chain.getArg(3);
                        if (!(rawAgentId instanceof String agentId)
                                || userInput == null || options == null || callback == null) {
                            return chain.proceed();
                        }
                        AgentTraceCaptureContext capture;
                        Object wrapped;
                        try {
                            capture = AgentTraceCaptureContext.start(
                                    chain.getThisObject(),
                                    userInput,
                                    options,
                                    agentId,
                                    targets,
                                    captureWriter
                            );
                            wrapped = capture.wrapCallback(
                                    callback,
                                    execute.getParameterTypes()[3]
                            );
                        } catch (Throwable ignored) {
                            return chain.proceed();
                        }
                        Object[] arguments = new Object[execute.getParameterCount()];
                        for (int index = 0; index < arguments.length; index++) {
                            arguments[index] = index == 3 ? wrapped : chain.getArg(index);
                        }
                        try {
                            return chain.proceedWith(chain.getThisObject(), arguments);
                        } catch (Throwable error) {
                            try {
                                capture.recordExecutionFailure(error);
                            } catch (Throwable ignored) {
                                // The original host failure stays authoritative.
                            }
                            throw error;
                        }
                    });
            return true;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Agent session trace Hook unavailable", error);
            return false;
        }
    }

    private boolean isInitializationObject(Object value) {
        if (value == null) {
            return false;
        }
        synchronized (initializationReasoningObjects) {
            return initializationReasoningObjects.remove(value);
        }
    }

    private static void addBoundedIdentity(Set<Object> identities, Object value) {
        if (value == null) {
            return;
        }
        synchronized (identities) {
            if (identities.size() >= MAX_INITIALIZATION_IDENTITIES) {
                identities.clear();
            }
            identities.add(value);
        }
    }

    private boolean isInitializationEnvelope(Object value) {
        if (value == null) {
            return false;
        }
        synchronized (initializationReasoningEnvelopes) {
            return initializationReasoningEnvelopes.remove(value);
        }
    }

    @SuppressWarnings("unchecked")
    private void prependInitializationMarker(Object mapped, Object rawDialogId) {
        if (!(rawDialogId instanceof String dialogId) || mapped == null) {
            return;
        }
        try {
            Method getInstructions = mapped.getClass().getMethod("getInstructions");
            Object value = getInstructions.invoke(mapped);
            if (!(value instanceof List<?> rawInstructions)) {
                return;
            }
            Object marker = buildHostToastStream("**初始化推理**", dialogId);
            if (marker == null) {
                return;
            }
            List<Object> instructions = (List<Object>) rawInstructions;
            int index = Math.max(0, instructions.size() - 1);
            instructions.add(index, marker);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to add initialization reasoning marker", error);
        }
    }

    private Object buildHostToastStream(String text, String dialogId) throws Exception {
        Method builder = hostToastStreamBuilder;
        Object instance = hostInstructionBuilder;
        if (builder == null || instance == null) {
            Class<?> owner = hostInstructionBuilderClass;
            if (owner == null) {
                return null;
            }
            for (Field field : owner.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !owner.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object candidate = field.get(null);
                if (candidate != null) {
                    instance = candidate;
                    break;
                }
            }
            for (Method candidate : owner.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals("buildToastStream")
                        && parameters.length == 2
                        && parameters[0] == String.class
                        && parameters[1] == String.class) {
                    candidate.setAccessible(true);
                    builder = candidate;
                    break;
                }
            }
            hostInstructionBuilder = instance;
            hostToastStreamBuilder = builder;
        }
        if (builder == null || instance == null) {
            return null;
        }
        return builder.invoke(instance, text, dialogId);
    }

    private static void patchToolInstructionPayload(Object instruction, String data) {
        if (instruction == null || data == null) {
            return;
        }
        try {
            Method getPayload = instruction.getClass().getMethod("getPayload");
            Object payload = getPayload.invoke(instruction);
            if (payload == null) {
                return;
            }
            Method put = null;
            for (Method method : payload.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("put") && parameters.length == 2
                        && parameters[0] == String.class && parameters[1] == String.class) {
                    put = method;
                    break;
                }
            }
            if (put != null) {
                put.invoke(payload, "data", data);
            }
        } catch (Throwable ignored) {
            // The host summary remains usable when its JSON node implementation changes.
        }
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
                    "File-policy targets unavailable; other capabilities remain active and host file policy is unchanged",
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

    private void installPromptPreferenceListener() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            throw new IllegalStateException("Remote Preferences unavailable");
        }
        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
            if (key == null || BridgeContract.PREF_PROMPT_PATCH_JSON.equals(key)) {
                invalidatePromptMemoryCache();
            }
        };
        preferenceListener = listener;
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private void invalidatePromptMemoryCache() {
        Method method = promptCacheInvalidatorMethod;
        if (method == null) {
            log(Log.INFO, TAG,
                    "System Prompt configuration changed; restart XiaoAi to clear its prompt cache");
            return;
        }
        try {
            method.invoke(promptCacheInvalidator);
            log(Log.INFO, TAG,
                    "System Prompt memory cache invalidated; changes apply to the next conversation");
        } catch (Throwable error) {
            log(Log.WARN, TAG,
                    "Unable to invalidate System Prompt cache; restart XiaoAi to apply changes",
                    error);
        }
    }

}
