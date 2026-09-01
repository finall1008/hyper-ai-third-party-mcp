package io.github.finall1008.xiaoaimcp.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.finall1008.xiaoaimcp.trace.AgentTraceContract;

final class AgentTraceCaptureContext {
    private final String executionId;
    private final AgentTraceCaptureWriter writer;
    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicBoolean sessionMarkerSeen = new AtomicBoolean(false);
    private volatile String hostSessionId;

    private AgentTraceCaptureContext(
            String executionId,
            String hostSessionId,
            AgentTraceCaptureWriter writer
    ) {
        this.executionId = executionId;
        this.hostSessionId = hostSessionId;
        this.writer = writer;
    }

    static AgentTraceCaptureContext start(
            Object executor,
            Object userInput,
            Object options,
            String requestedAgentId,
            AgentSessionTraceTargets targets,
            AgentTraceCaptureWriter writer
    ) {
        String executionId = UUID.randomUUID().toString();
        String sessionId = stringProperty(userInput, "getSessionId");
        String chatId = stringProperty(userInput, "getChatId");
        String sessionKey = sessionId != null
                ? "host:" + sessionId
                : chatId != null ? "chat:" + chatId : "local:" + executionId;
        AgentTraceCaptureContext context = new AgentTraceCaptureContext(
                executionId,
                sessionId,
                writer
        );
        JSONObject record = new JSONObject();
        put(record, "schema_version", AgentTraceContract.SCHEMA_VERSION);
        put(record, "record_type", AgentTraceContract.RECORD_TURN_START);
        put(record, "execution_id", executionId);
        put(record, "session_key", sessionKey);
        put(record, "host_session_id", sessionId);
        put(record, "chat_id", chatId);
        put(record, "observed_at", System.currentTimeMillis());

        String userAgentId = stringProperty(userInput, "getAgentId");
        String agentId = userAgentId != null ? userAgentId : requestedAgentId;
        put(record, "agent_id", agentId);
        put(record, "user_text", stringProperty(userInput, "getText"));
        put(record, "user_input", AgentTraceValueEncoder.encodeKnownObject(userInput));
        put(record, "execution_options", AgentTraceValueEncoder.encodeKnownObject(options));

        Object meta = invoke(targets.getAgentMeta(), executor, agentId);
        String systemPrompt = stringProperty(meta, "getResolvedSystemPrompt");
        Object promptOverride = property(options, "getPromptOverride");
        if (promptOverride instanceof String override) {
            systemPrompt = override;
        }

        Object agentInstance = null;
        Field managerField = targets.agentManagerField();
        Method managerGet = targets.agentManagerGet();
        if (managerField != null && managerGet != null) {
            try {
                Object manager = managerField.get(executor);
                agentInstance = invoke(managerGet, manager, agentId);
            } catch (Throwable ignored) {
                // System Prompt capture remains available through AgentMeta.
            }
        }
        String agentName = stringProperty(agentInstance, "getDisplayName");
        if (agentName == null) {
            agentName = stringProperty(agentInstance, "getName");
        }
        put(record, "agent_name", agentName);
        put(record, "system_prompt", systemPrompt);
        Object definitions = property(agentInstance, "getToolDefinitions");
        Object encodedTools = definitions == null
                ? new JSONArray() : AgentTraceValueEncoder.encode(definitions);
        put(record, "tool_catalog", encodedTools);
        writer.enqueue(executionId, record);
        return context;
    }

    Object wrapCallback(Object callback, Class<?> functionType) {
        if (callback == null || functionType == null || !functionType.isInterface()) {
            return callback;
        }
        ClassLoader loader = functionType.getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[]{functionType}, (proxy, method, args) -> {
            if (method.getName().equals("invoke") && args != null && args.length >= 1) {
                try {
                    recordEvent(args[0]);
                } catch (Throwable ignored) {
                    // Trace serialization never changes the original callback behavior.
                }
            }
            try {
                method.setAccessible(true);
                return method.invoke(callback, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        });
    }

    void recordExecutionFailure(Throwable error) {
        JSONObject payload = new JSONObject();
        put(payload, "message", error == null ? null : error.getMessage());
        put(payload, "cause", AgentTraceValueEncoder.encode(error));
        record("ERROR", payload, hostSessionId);
    }

    private void recordEvent(Object event) {
        AgentTraceEventNormalizer.NormalizedEvent normalized =
                AgentTraceEventNormalizer.normalize(event);
        String type = normalized.type();
        String eventSessionId = normalized.hostSessionId();
        if (eventSessionId != null) {
            if ("SESSION_MARKER".equals(type)) {
                type = sessionMarkerSeen.compareAndSet(false, true)
                        ? "SESSION_STARTED" : "STREAM_RESET";
            }
            hostSessionId = eventSessionId;
        }
        record(type, normalized.payload(), eventSessionId);
    }

    private void record(String type, JSONObject payload, String eventSessionId) {
        JSONObject record = new JSONObject();
        put(record, "schema_version", AgentTraceContract.SCHEMA_VERSION);
        put(record, "record_type", AgentTraceContract.RECORD_EVENT);
        put(record, "execution_id", executionId);
        put(record, "sequence", sequence.incrementAndGet());
        put(record, "observed_at", System.currentTimeMillis());
        put(record, "event_type", type);
        put(record, "payload", payload);
        put(record, "host_session_id", eventSessionId != null
                ? eventSessionId : hostSessionId);
        writer.enqueue(executionId, record);
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null || target == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object property(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringProperty(Object target, String methodName) {
        Object value = property(target, methodName);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static void put(JSONObject object, String name, Object value) {
        AgentTraceValueEncoder.put(object, name, value);
    }
}
