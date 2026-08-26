package io.github.finall1008.xiaoaimcp.timeout;

public enum FirstOutputTimeoutMode {
    HOST_DEFAULT("host_default"),
    CUSTOM("custom"),
    UNLIMITED("unlimited");

    private final String preferenceValue;

    FirstOutputTimeoutMode(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    public String preferenceValue() {
        return preferenceValue;
    }

    public static FirstOutputTimeoutMode fromPreferenceValue(String value) {
        for (FirstOutputTimeoutMode mode : values()) {
            if (mode.preferenceValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown first-output timeout mode: " + value);
    }
}
