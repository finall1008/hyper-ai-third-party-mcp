package io.github.finall1008.xiaoaimcp.hook;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Correlates tool payloads while bounding retained argument data by age and count. */
final class AgentToolTraceStore {
    private static final int DEFAULT_MAX_PENDING = 512;
    private static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1_000L;

    private final int maxPending;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, PendingTool> pending =
            new LinkedHashMap<>(16, 0.75f, true);

    AgentToolTraceStore() {
        this(DEFAULT_MAX_PENDING, DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    AgentToolTraceStore(int maxPending, long ttlMillis, LongSupplier clock) {
        if (maxPending < 1 || ttlMillis < 1L || clock == null) {
            throw new IllegalArgumentException("Invalid trace-store bounds");
        }
        this.maxPending = maxPending;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    synchronized String merge(String dialogId, String event, String payload) {
        if (dialogId == null || event == null || payload == null) {
            return payload;
        }
        JSONObject object;
        try {
            object = new JSONObject(payload);
        } catch (Throwable ignored) {
            return payload;
        }

        long now = clock.getAsLong();
        pruneExpired(now);
        String toolName = text(object, "tool");
        String callId = text(object, "tool_call_id");
        String key = key(dialogId, callId, toolName);
        if ("start".equals(event)) {
            pending.put(key, new PendingTool(
                    toolName,
                    object.has("arguments") && !object.isNull("arguments")
                            ? String.valueOf(object.opt("arguments")) : null,
                    callId,
                    now
            ));
            pruneOverflow();
            return payload;
        }

        PendingTool previous = pending.get(key);
        if (previous != null) {
            previous.lastSeenMillis = now;
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

        if (isTerminal(event) && previous != null) {
            pending.remove(key);
        }
        return object.toString();
    }

    synchronized void clear() {
        pending.clear();
    }

    synchronized void clearDialog(String dialogId) {
        if (dialogId == null) {
            return;
        }
        String prefix = dialogId + '\u0000';
        pending.keySet().removeIf(key -> key.startsWith(prefix));
    }

    synchronized int size() {
        pruneExpired(clock.getAsLong());
        return pending.size();
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, PendingTool>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingTool value = iterator.next().getValue();
            if (now - value.lastSeenMillis >= ttlMillis) {
                iterator.remove();
            }
        }
    }

    private void pruneOverflow() {
        Iterator<String> iterator = pending.keySet().iterator();
        while (pending.size() > maxPending && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
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
        private long lastSeenMillis;

        private PendingTool(String toolName, String arguments, String callId, long now) {
            this.toolName = toolName == null ? "" : toolName;
            this.arguments = arguments;
            this.hasArguments = arguments != null;
            this.callId = callId == null ? "" : callId;
            this.lastSeenMillis = now;
        }
    }
}
