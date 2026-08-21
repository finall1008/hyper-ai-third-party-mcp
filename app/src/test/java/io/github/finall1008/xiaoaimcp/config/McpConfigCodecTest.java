package io.github.finall1008.xiaoaimcp.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpConfigCodecTest {
    private static McpServer server(
            String id,
            String name,
            String url,
            String transport,
            boolean enabled,
            Map<String, String> headers
    ) {
        return new McpServer(id, name, "description", url, transport, enabled, headers);
    }

    @Test
    public void roundTripSupportsHttpSseHeadersAndDisabledServers() throws Exception {
        List<McpServer> input = List.of(
                server("1", "search", "https://example.com/mcp", "http", true,
                        Map.of("Authorization", "Bearer secret")),
                server("2", "legacy-sse", "http://192.168.1.2:3000/sse", "sse", false,
                        Map.of())
        );

        List<McpServer> decoded = McpConfigCodec.parseModuleConfig(
                McpConfigCodec.encodeModuleConfig(input));

        assertEquals(2, decoded.size());
        assertEquals("http", decoded.get(0).transport());
        assertEquals("Bearer secret", decoded.get(0).headers().get("Authorization"));
        assertEquals("sse", decoded.get(1).transport());
        assertFalse(decoded.get(1).enabled());
    }

    @Test
    public void emptyModuleConfigDoesNotRewriteHostJson() throws Exception {
        String host = "{\"gateway_mode\":true,\"servers\":[{\"name\":\"builtin\"}]}";
        assertEquals(host, McpConfigCodec.mergeHostConfig(host, McpConfigCodec.emptyConfig()));
    }

    @Test
    public void moduleServerWinsNameCollisionAndKeepsOtherHostServers() throws Exception {
        String host = "{\"gateway_mode\":true,\"servers\":["
                + "{\"name\":\"same\",\"url\":\"https://old.example/mcp\"},"
                + "{\"name\":\"keep\",\"url\":\"https://keep.example/mcp\"}]}";
        McpServer replacement = server("id", "same", "https://new.example/mcp", "http", false,
                Map.of("X-API-Key", "top-secret"));

        JSONObject merged = new JSONObject(McpConfigCodec.mergeHostConfig(
                host,
                McpConfigCodec.encodeModuleConfig(List.of(replacement))
        ));
        JSONArray servers = merged.getJSONArray("servers");

        assertEquals(2, servers.length());
        assertEquals("keep", servers.getJSONObject(0).getString("name"));
        JSONObject injected = servers.getJSONObject(1);
        assertEquals("same", injected.getString("name"));
        assertEquals("https://new.example/mcp", injected.getString("url"));
        assertFalse(injected.getBoolean("enabled"));
        assertTrue(injected.getBoolean("tool_prefix"));
        assertFalse(injected.has("id"));
    }

    @Test
    public void moduleCollisionAlsoRemovesLegacyMcpServersEntry() throws Exception {
        String host = "{\"mcpServers\":{"
                + "\"same\":{\"url\":\"https://old.example/mcp\"},"
                + "\"keep\":{\"url\":\"https://keep.example/mcp\"}}}";
        McpServer replacement = server("id", "same", "https://new.example/mcp", "sse", true,
                Map.of());

        JSONObject merged = new JSONObject(McpConfigCodec.mergeHostConfig(
                host,
                McpConfigCodec.encodeModuleConfig(List.of(replacement))
        ));

        assertFalse(merged.getJSONObject("mcpServers").has("same"));
        assertTrue(merged.getJSONObject("mcpServers").has("keep"));
        assertEquals("same", merged.getJSONArray("servers").getJSONObject(0).getString("name"));
    }

    @Test
    public void rejectsUnsafeNamesDuplicateNamesUnsupportedTransportAndBadUrl() {
        assertThrows(McpConfigValidator.ValidationException.class,
                () -> McpConfigValidator.validate(server("1", "bad__name",
                        "https://example.com/mcp", "http", true, Map.of())));
        assertThrows(McpConfigValidator.ValidationException.class,
                () -> McpConfigValidator.validate(server("1", "ok",
                        "https://example.com/mcp", "stdio", true, Map.of())));
        assertThrows(McpConfigValidator.ValidationException.class,
                () -> McpConfigValidator.validate(server("1", "ok",
                        "file:///tmp/mcp", "http", true, Map.of())));
        assertThrows(McpConfigValidator.ValidationException.class,
                () -> McpConfigValidator.validateAll(List.of(
                        server("1", "same", "https://one.example/mcp", "http", true, Map.of()),
                        server("2", "same", "https://two.example/mcp", "sse", true, Map.of())
                )));
    }

    @Test
    public void rejectsHeaderInjectionAndMalformedJson() {
        Map<String, String> injected = new LinkedHashMap<>();
        injected.put("Authorization", "Bearer secret\r\nX-Evil: yes");
        assertThrows(McpConfigValidator.ValidationException.class,
                () -> McpConfigValidator.validate(server("1", "safe",
                        "https://example.com/mcp", "http", true, injected)));
        assertThrows(Exception.class,
                () -> McpConfigCodec.parseModuleConfig("{not-json"));
    }

    @Test
    public void logSummaryNeverContainsHeaderValues() {
        McpServer configured = server("1", "safe", "https://example.com/mcp", "http", true,
                Map.of("Authorization", "Bearer do-not-log", "X-API-Key", "secret-two"));

        String summary = McpConfigCodec.redactedForLog(List.of(configured));

        assertTrue(summary.contains("headers=2"));
        assertFalse(summary.contains("do-not-log"));
        assertFalse(summary.contains("secret-two"));
    }
}
