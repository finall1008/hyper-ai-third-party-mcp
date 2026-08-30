package io.github.finall1008.xiaoaimcp.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

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
    public void exportsNativeConfigWithoutModuleIds() throws Exception {
        McpServer configured = server(
                "module-only-id",
                "search",
                "https://example.com/mcp",
                "http",
                false,
                Map.of("Authorization", "Bearer secret")
        );

        JSONObject exported = new JSONObject(McpConfigCodec.exportNativeConfig(
                List.of(configured)));
        JSONArray servers = exported.getJSONArray("servers");
        JSONObject server = servers.getJSONObject(0);

        assertEquals(1, servers.length());
        assertEquals("search", server.getString("name"));
        assertFalse(server.getBoolean("enabled"));
        assertEquals("Bearer secret",
                server.getJSONObject("headers").getString("Authorization"));
        assertFalse(server.has("id"));
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
}
