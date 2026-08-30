package io.github.finall1008.xiaoaimcp.config;

import io.github.finall1008.xiaoaimcp.BridgeContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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

    /** Converts the retired module format into XiaoAi's native personal MCP format. */
    public static String exportNativeConfig(List<McpServer> servers)
            throws JSONException, McpConfigValidator.ValidationException {
        McpConfigValidator.validateAll(servers);
        JSONObject root = new JSONObject();
        JSONArray nativeServers = new JSONArray();
        for (McpServer server : servers) {
            nativeServers.put(server.toHostJson());
        }
        root.put("servers", nativeServers);
        return root.toString(2);
    }
}
