package io.github.finall1008.xiaoaimcp.config;

import android.content.SharedPreferences;

import io.github.finall1008.xiaoaimcp.BridgeContract;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class RemoteConfigRepository {
    private final SharedPreferences preferences;

    public RemoteConfigRepository(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public List<McpServer> load() throws Exception {
        String json = preferences.getString(
                BridgeContract.PREF_SERVERS_JSON,
                McpConfigCodec.emptyConfig()
        );
        return new ArrayList<>(McpConfigCodec.parseModuleConfig(json));
    }

    public void save(List<McpServer> servers) throws Exception {
        McpConfigValidator.validateAll(servers);
        String json = McpConfigCodec.encodeModuleConfig(servers);
        SharedPreferences.Editor editor = preferences.edit();
        if (editor == null) {
            throw new IllegalStateException("Xposed Remote Preferences editor unavailable");
        }
        editor.putString(BridgeContract.PREF_SERVERS_JSON, json).apply();
    }

    public McpServer findById(String id) throws Exception {
        for (McpServer server : load()) {
            if (server.id().equals(id)) {
                return server;
            }
        }
        return null;
    }

    public void upsert(McpServer server) throws Exception {
        List<McpServer> servers = load();
        boolean replaced = false;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id().equals(server.id())) {
                servers.set(i, server);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            servers.add(server);
        }
        save(servers);
    }

    public void delete(String id) throws Exception {
        List<McpServer> servers = load();
        servers.removeIf(server -> server.id().equals(id));
        save(servers);
    }

    public void setEnabled(String id, boolean enabled) throws Exception {
        List<McpServer> servers = load();
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id().equals(id)) {
                servers.set(i, servers.get(i).withEnabled(enabled));
                save(servers);
                return;
            }
        }
        throw new JSONException("Server not found: " + id);
    }
}
