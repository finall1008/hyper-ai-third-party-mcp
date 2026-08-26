package io.github.finall1008.xiaoaimcp.timeout;

import android.content.SharedPreferences;

import java.util.Objects;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class FirstOutputTimeoutRepository {
    private final SharedPreferences preferences;

    public FirstOutputTimeoutRepository(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public FirstOutputTimeoutConfig load() {
        try {
            return loadStrict();
        } catch (RuntimeException ignored) {
            return FirstOutputTimeoutConfig.hostDefault();
        }
    }

    public FirstOutputTimeoutConfig loadStrict() {
        String rawMode = preferences.getString(
                BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_MODE,
                FirstOutputTimeoutMode.HOST_DEFAULT.preferenceValue()
        );
        long customSeconds = preferences.getLong(
                BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_SECONDS,
                FirstOutputTimeoutConfig.DEFAULT_CUSTOM_SECONDS
        );
        return new FirstOutputTimeoutConfig(
                FirstOutputTimeoutMode.fromPreferenceValue(rawMode),
                customSeconds
        );
    }

    public void save(FirstOutputTimeoutConfig config) {
        Objects.requireNonNull(config, "config");
        SharedPreferences.Editor editor = preferences.edit();
        if (editor == null) {
            throw new IllegalStateException("Xposed Remote Preferences editor unavailable");
        }
        editor.putString(
                BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_MODE,
                config.mode().preferenceValue()
        );
        editor.putLong(
                BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_SECONDS,
                config.customSeconds()
        );
        editor.apply();
    }
}
