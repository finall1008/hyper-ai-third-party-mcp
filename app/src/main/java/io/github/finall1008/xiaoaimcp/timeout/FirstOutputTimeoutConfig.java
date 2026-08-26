package io.github.finall1008.xiaoaimcp.timeout;

import java.util.Objects;

public record FirstOutputTimeoutConfig(
        FirstOutputTimeoutMode mode,
        long customSeconds
) {
    public static final long DEFAULT_CUSTOM_SECONDS = 120L;
    public static final long MAX_CUSTOM_SECONDS = Long.MAX_VALUE / 1_000L;

    public FirstOutputTimeoutConfig {
        Objects.requireNonNull(mode, "mode");
        if (customSeconds <= 0L || customSeconds > MAX_CUSTOM_SECONDS) {
            throw new IllegalArgumentException(
                    "首次输出超时必须是大于 0 的整数秒"
            );
        }
    }

    public static FirstOutputTimeoutConfig hostDefault() {
        return new FirstOutputTimeoutConfig(
                FirstOutputTimeoutMode.HOST_DEFAULT,
                DEFAULT_CUSTOM_SECONDS
        );
    }

    public boolean overridesHost() {
        return mode != FirstOutputTimeoutMode.HOST_DEFAULT;
    }

    public long resolveTimeoutMillis(long hostTimeoutMillis) {
        return switch (mode) {
            case HOST_DEFAULT -> hostTimeoutMillis;
            case CUSTOM -> Math.multiplyExact(customSeconds, 1_000L);
            case UNLIMITED -> Long.MAX_VALUE;
        };
    }
}
