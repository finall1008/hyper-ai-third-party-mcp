package io.github.finall1008.xiaoaimcp.hook;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

final class AgentTraceEventNormalizer {
    private AgentTraceEventNormalizer() {
    }

    static NormalizedEvent normalize(Object event) {
        if (event == null) {
            return new NormalizedEvent("UNKNOWN", new JSONObject(), null);
        }
        Set<String> getters = getterNames(event.getClass());
        String type = classify(getters);
        JSONObject payload = AgentTraceValueEncoder.encodeKnownObject(event);
        String sessionId = stringProperty(event, "getSessionId");
        return new NormalizedEvent(type, payload, sessionId);
    }

    private static String classify(Set<String> getters) {
        if (has(getters, "getToolName", "getArguments")) {
            return "TOOL_EXECUTING";
        }
        if (has(getters, "getToolName", "getResult", "getSuccess")) {
            return "TOOL_RESULT_READY";
        }
        if (has(getters, "getToolName", "getResult", "getDurationMs")) {
            return "TOOL_COMPLETED";
        }
        if (has(getters, "getToolName", "getError")) {
            return "TOOL_FAILED";
        }
        if (has(getters, "getToolName", "getMetadata")) {
            return "TOOL_PROGRESS";
        }
        if (has(getters, "getName", "getId")) {
            return "TOOL_CALL_DETECTED";
        }
        if (has(getters, "getFullText", "getStreamId", "getFileAttachments")) {
            return "TEXT_COMPLETED";
        }
        if (has(getters, "getFullText", "getStreamId")) {
            return "REASONING_COMPLETED";
        }
        if (has(getters, "getText", "getIndex", "getStreamId")) {
            return "STREAM_DELTA";
        }
        if (has(getters, "getRetryIndex", "getMaxRetries", "getDelayMs")) {
            return "LLM_RETRYING";
        }
        if (has(getters, "getFromModel", "getToModel", "getReason")) {
            return "LLM_FALLBACK";
        }
        if (has(getters, "getIteration", "getSessionId")) {
            return "ITERATION_STARTED";
        }
        if (has(getters, "getTarget")) {
            return "ROUTE_SELECTED";
        }
        if (has(getters, "getResult", "getSessionId", "isInlineSubAgent")) {
            return "COMPLETED";
        }
        if (has(getters, "getMessage", "getSessionId", "getHttpStatus")) {
            return "ERROR";
        }
        if (has(getters, "getMessage", "getSessionId")) {
            return "ASSISTANT_MESSAGE_READY";
        }
        if (has(getters, "getAction", "getDetails", "getRequestId")) {
            return "CONSENT_REQUIRED";
        }
        if (has(getters, "getPermission", "getRationale", "getRequestId")) {
            return "PERMISSION_REQUIRED";
        }
        if (has(getters, "getAgentId", "getAgentName")) {
            return "NAVIGATE_TO_AGENT";
        }
        if (has(getters, "getMessageId", "getSessionId")) {
            return "MESSAGE_DISCARDED";
        }
        if (has(getters, "getSessionId")) {
            return "SESSION_MARKER";
        }
        return "UNKNOWN";
    }

    private static Set<String> getterNames(Class<?> type) {
        HashSet<String> names = new HashSet<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static boolean has(Set<String> names, String... expected) {
        for (String value : expected) {
            if (!names.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static String stringProperty(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof String string && !string.isBlank() ? string : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    record NormalizedEvent(String type, JSONObject payload, String hostSessionId) {
    }
}
