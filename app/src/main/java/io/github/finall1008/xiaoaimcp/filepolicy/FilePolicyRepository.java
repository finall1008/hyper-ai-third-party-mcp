package io.github.finall1008.xiaoaimcp.filepolicy;

import android.content.SharedPreferences;

import java.util.Objects;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class FilePolicyRepository {
    private final SharedPreferences preferences;

    public FilePolicyRepository(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public FilePolicyConfig load() {
        String json = preferences.getString(
                BridgeContract.PREF_FILE_POLICY_JSON,
                FilePolicyCodec.emptyConfig()
        );
        return FilePolicyCodec.parse(json);
    }

    public void save(FilePolicyConfig config) {
        SharedPreferences.Editor editor = preferences.edit();
        if (editor == null) {
            throw new IllegalStateException("Xposed Remote Preferences editor unavailable");
        }
        editor.putString(BridgeContract.PREF_FILE_POLICY_JSON, FilePolicyCodec.encode(config)).apply();
    }
}
