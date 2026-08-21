package io.github.finall1008.xiaoaimcp.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class McpServer {
    private final String id;
    private final String name;
    private final String description;
    private final String url;
    private final String transport;
    private final boolean enabled;
    private final Map<String, String> headers;

    public McpServer(
            String id,
            String name,
            String description,
            String url,
            String transport,
            boolean enabled,
            Map<String, String> headers
    ) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = Objects.requireNonNull(name).trim();
        this.description = description == null ? "" : description.trim();
        this.url = Objects.requireNonNull(url).trim();
        this.transport = Objects.requireNonNull(transport).trim();
        this.enabled = enabled;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String url() {
        return url;
    }

    public String transport() {
        return transport;
    }

    public boolean enabled() {
        return enabled;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public McpServer withEnabled(boolean newEnabled) {
        return new McpServer(id, name, description, url, transport, newEnabled, headers);
    }

    public JSONObject toModuleJson() throws JSONException {
        JSONObject object = toHostJson();
        object.put("id", id);
        return object;
    }

    public JSONObject toHostJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", name);
        object.put("url", url);
        object.put("transport", transport);
        object.put("description", description);
        object.put("enabled", enabled);
        object.put("tool_prefix", true);
        if (!headers.isEmpty()) {
            object.put("headers", new JSONObject(headers));
        }
        return object;
    }

    public static McpServer fromJson(JSONObject object) throws JSONException {
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject headerObject = object.optJSONObject("headers");
        if (headerObject != null) {
            Iterator<String> keys = headerObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = headerObject.get(key);
                if (!(value instanceof String)) {
                    throw new JSONException("Header value must be a string: " + key);
                }
                headers.put(key, (String) value);
            }
        }
        return new McpServer(
                object.optString("id", ""),
                object.getString("name"),
                object.optString("description", ""),
                object.getString("url"),
                object.optString("transport", "http"),
                object.optBoolean("enabled", true),
                headers
        );
    }
}
