package io.github.finall1008.xiaoaimcp.prompt;

import android.content.SharedPreferences;

import java.util.Objects;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class PromptPatchRepository {
    private final SharedPreferences preferences;

    public PromptPatchRepository(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public PromptPatchConfig load() {
        return PromptPatchCodec.parse(preferences.getString(
                BridgeContract.PREF_PROMPT_PATCH_JSON,
                PromptPatchCodec.defaultConfig()
        ));
    }

    public void save(PromptPatchConfig config) {
        String json = PromptPatchCodec.encode(config);
        SharedPreferences.Editor editor = preferences.edit();
        if (editor == null) {
            throw new IllegalStateException("Xposed Remote Preferences editor unavailable");
        }
        editor.putString(BridgeContract.PREF_PROMPT_PATCH_JSON, json).apply();
    }
}
