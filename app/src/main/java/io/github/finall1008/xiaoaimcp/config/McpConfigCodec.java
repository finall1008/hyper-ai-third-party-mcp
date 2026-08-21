package io.github.finall1008.xiaoaimcp.config;

import io.github.finall1008.xiaoaimcp.BridgeContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class McpConfigCodec {
    private McpConfigCodec() {
    }

    public static String emptyConfig() {
        try {
            return encodeModuleConfig(List.of());
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String encodeModuleConfig(List<McpServer> servers) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", BridgeContract.CONFIG_SCHEMA_VERSION);
        JSONArray array = new JSONArray();
        for (McpServer server : servers) {
            array.put(server.toModuleJson());
        }
        root.put("servers", array);
        return root.toString();
    }

    public static List<McpServer> parseModuleConfig(String json)
            throws JSONException, McpConfigValidator.ValidationException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JSONObject root = new JSONObject(json);
        int schema = root.optInt("schemaVersion", BridgeContract.CONFIG_SCHEMA_VERSION);
        if (schema != BridgeContract.CONFIG_SCHEMA_VERSION) {
            throw new JSONException("Unsupported schemaVersion: " + schema);
        }
        JSONArray array = root.optJSONArray("servers");
        if (array == null) {
            throw new JSONException("Missing servers array");
        }
        List<McpServer> servers = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            servers.add(McpServer.fromJson(array.getJSONObject(i)));
        }
        McpConfigValidator.validateAll(servers);
        return servers;
    }

    public static String mergeHostConfig(String hostJson, String moduleJson)
            throws JSONException, McpConfigValidator.ValidationException {
        List<McpServer> moduleServers = parseModuleConfig(moduleJson);
        if (moduleServers.isEmpty()) {
            return hostJson;
        }

        JSONObject host = new JSONObject(hostJson);
        Set<String> moduleNames = new HashSet<>();
        for (McpServer server : moduleServers) {
            moduleNames.add(server.name());
        }

        JSONArray merged = new JSONArray();
        JSONArray hostServers = host.optJSONArray("servers");
        if (hostServers != null) {
            for (int i = 0; i < hostServers.length(); i++) {
                JSONObject server = hostServers.getJSONObject(i);
                if (!moduleNames.contains(server.optString("name"))) {
                    merged.put(server);
                }
            }
        }

        JSONObject legacy = host.optJSONObject("mcpServers");
        if (legacy != null) {
            Iterator<String> keys = legacy.keys();
            List<String> toRemove = new ArrayList<>();
            while (keys.hasNext()) {
                String key = keys.next();
                if (moduleNames.contains(key)) {
                    toRemove.add(key);
                }
            }
            for (String key : toRemove) {
                legacy.remove(key);
            }
        }

        for (McpServer server : moduleServers) {
            merged.put(server.toHostJson());
        }
        host.put("servers", merged);
        return host.toString();
    }

    public static String redactedForLog(List<McpServer> servers) {
        List<String> summaries = new ArrayList<>(servers.size());
        for (McpServer server : servers) {
            summaries.add(server.name() + "(" + server.transport() + ", headers="
                    + server.headers().size() + ", enabled=" + server.enabled() + ")");
        }
        return summaries.toString();
    }
}
