package io.github.finall1008.xiaoaimcp.hook;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

final class AgentTraceValueEncoder {
    private static final int MAX_DEPTH = 10;

    private AgentTraceValueEncoder() {
    }

    static Object encode(Object value) {
        return encode(value, 0, new IdentityHashMap<>());
    }

    static JSONObject encodeKnownObject(Object value) {
        Object encoded = encode(value);
        if (encoded instanceof JSONObject object) {
            return object;
        }
        JSONObject wrapper = new JSONObject();
        put(wrapper, "value", encoded);
        return wrapper;
    }

    private static Object encode(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> active
    ) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Uri uri) {
            return uri.toString();
        }
        if (value instanceof File file) {
            return file.getPath();
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            JSONObject omitted = new JSONObject();
            put(omitted, "_encoding", "binary_content_omitted");
            put(omitted, "length", bytes.length);
            return omitted;
        }
        if (value instanceof Throwable error) {
            return throwable(error, depth, active);
        }
        if (depth >= MAX_DEPTH) {
            return marker(value, "depth_limited");
        }
        if (active.put(value, Boolean.TRUE) != null) {
            return marker(value, "cycle");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                JSONObject object = new JSONObject();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (key instanceof String name) {
                        put(object, name, encode(entry.getValue(), depth + 1, active));
                    }
                }
                return object;
            }
            if (value instanceof Iterable<?> iterable) {
                JSONArray array = new JSONArray();
                for (Object item : iterable) {
                    array.put(encode(item, depth + 1, active));
                }
                return array;
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                JSONArray array = new JSONArray();
                int length = Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    array.put(encode(Array.get(value, index), depth + 1, active));
                }
                return array;
            }
            if (type.getName().startsWith("kotlinx.serialization.json.")) {
                String json = value.toString();
                if (json.startsWith("{")) {
                    return new JSONObject(json);
                }
                if (json.startsWith("[")) {
                    return new JSONArray(json);
                }
                return json;
            }
            return reflectGetters(value, depth, active);
        } catch (Throwable ignored) {
            return marker(value, "unreadable");
        } finally {
            active.remove(value);
        }
    }

    private static JSONObject reflectGetters(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> active
    ) {
        JSONObject object = new JSONObject();
        put(object, "_class", value.getClass().getName());
        Method[] methods = value.getClass().getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        boolean attachmentWithText = hasGetter(methods, "getFilename")
                && hasGetter(methods, "getSizeBytes");
        for (Method method : methods) {
            String name = propertyName(method);
            if (name == null) {
                continue;
            }
            try {
                Object property = method.invoke(value);
                if (name.equals("base64Data")
                        || (attachmentWithText && name.equals("text"))) {
                    put(object, name, omittedContent(property));
                    continue;
                }
                put(object, name, encode(property, depth + 1, active));
            } catch (Throwable ignored) {
                // Other stable getters remain available in the same raw record.
            }
        }
        return object;
    }

    private static boolean hasGetter(Method[] methods, String name) {
        for (Method method : methods) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject omittedContent(Object value) {
        JSONObject object = new JSONObject();
        put(object, "_encoding", "attachment_content_omitted");
        if (value instanceof CharSequence sequence) {
            put(object, "length", sequence.length());
        } else if (value != null && value.getClass().isArray()) {
            put(object, "length", Array.getLength(value));
        }
        return object;
    }

    private static String propertyName(Method method) {
        if (!Modifier.isPublic(method.getModifiers())
                || method.getParameterCount() != 0
                || method.getReturnType() == void.class
                || method.getName().equals("getClass")) {
            return null;
        }
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class
                || method.getReturnType() == Boolean.class)) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    private static String decapitalize(String value) {
        if (value.length() == 1) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static JSONObject throwable(
            Throwable error,
            int depth,
            IdentityHashMap<Object, Boolean> active
    ) {
        JSONObject object = new JSONObject();
        put(object, "class", error.getClass().getName());
        put(object, "message", error.getMessage());
        JSONArray stack = new JSONArray();
        for (StackTraceElement frame : error.getStackTrace()) {
            JSONObject item = new JSONObject();
            put(item, "class", frame.getClassName());
            put(item, "method", frame.getMethodName());
            put(item, "file", frame.getFileName());
            put(item, "line", frame.getLineNumber());
            stack.put(item);
        }
        put(object, "stack", stack);
        if (error.getCause() != null && error.getCause() != error) {
            put(object, "cause", encode(error.getCause(), depth + 1, active));
        }
        return object;
    }

    private static JSONObject marker(Object value, String reason) {
        JSONObject object = new JSONObject();
        put(object, "_class", value.getClass().getName());
        put(object, "_encoding", reason);
        return object;
    }

    static void put(JSONObject object, String name, Object value) {
        try {
            object.put(name, value == null ? JSONObject.NULL : value);
        } catch (Throwable ignored) {
            // JSONObject only rejects invalid numeric values; the remaining record is useful.
        }
    }
}
