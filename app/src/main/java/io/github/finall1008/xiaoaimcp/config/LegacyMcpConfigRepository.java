package io.github.finall1008.xiaoaimcp.config;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import io.github.finall1008.xiaoaimcp.BridgeContract;

/** Read-only access to MCP configurations saved by module releases before 1.6.0. */
public final class LegacyMcpConfigRepository {
    private final SharedPreferences preferences;

    public LegacyMcpConfigRepository(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public List<McpServer> load() throws Exception {
        String json = preferences.getString(
                BridgeContract.PREF_SERVERS_JSON,
                McpConfigCodec.emptyConfig()
        );
        return new ArrayList<>(McpConfigCodec.parseModuleConfig(json));
    }

    public String exportNativeConfig() throws Exception {
        return McpConfigCodec.exportNativeConfig(load());
    }
}
