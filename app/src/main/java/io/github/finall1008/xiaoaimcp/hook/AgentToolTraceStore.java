package io.github.finall1008.xiaoaimcp.hook;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Correlates the host's start/progress/terminal tool payloads without logging their contents.
 */
final class AgentToolTraceStore {
    private final ConcurrentMap<String, PendingTool> pending = new ConcurrentHashMap<>();

    String merge(String dialogId, String event, String payload) {
        if (dialogId == null || event == null || payload == null) {
            return payload;
        }
        JSONObject object;
        try {
            object = new JSONObject(payload);
        } catch (Throwable ignored) {
            return payload;
        }

        String toolName = text(object, "tool");
        String callId = text(object, "tool_call_id");
        String key = key(dialogId, callId, toolName);
        if ("start".equals(event)) {
            pending.put(key, new PendingTool(
                    toolName,
                    object.has("arguments") && !object.isNull("arguments")
                            ? String.valueOf(object.opt("arguments")) : null,
                    callId
            ));
            return payload;
        }

        PendingTool previous = pending.get(key);
        if (previous != null) {
            try {
                if (!object.has("tool") && !previous.toolName.isEmpty()) {
                    object.put("tool", previous.toolName);
                }
                if (!object.has("tool_call_id") && !previous.callId.isEmpty()) {
                    object.put("tool_call_id", previous.callId);
                }
                if (!object.has("arguments") && previous.hasArguments) {
                    object.put("arguments", previous.arguments);
                }
            } catch (Throwable ignored) {
                return payload;
            }
        }

        if (isTerminal(event)) {
            if (previous != null) {
                pending.remove(key, previous);
            }
        }
        return object.toString();
    }

    void clear() {
        pending.clear();
    }

    private static boolean isTerminal(String event) {
        return "done".equals(event) || "failed".equals(event)
                || "error".equals(event) || "timeout".equals(event);
    }

    private static String key(String dialogId, String callId, String toolName) {
        String id = callId == null || callId.isEmpty() ? "name:" + toolName : "id:" + callId;
        return dialogId + '\u0000' + id;
    }

    private static String text(JSONObject object, String name) {
        if (!object.has(name) || object.isNull(name)) {
            return "";
        }
        return String.valueOf(object.opt(name));
    }

    private static final class PendingTool {
        private final String toolName;
        private final String arguments;
        private final boolean hasArguments;
        private final String callId;

        private PendingTool(String toolName, String arguments, String callId) {
            this.toolName = toolName == null ? "" : toolName;
            this.arguments = arguments;
            this.hasArguments = arguments != null;
            this.callId = callId == null ? "" : callId;
        }
    }
}
