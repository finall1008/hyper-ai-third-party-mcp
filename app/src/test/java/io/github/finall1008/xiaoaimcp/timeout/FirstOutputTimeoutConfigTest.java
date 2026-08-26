package io.github.finall1008.xiaoaimcp.timeout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FirstOutputTimeoutConfigTest {
    @Test
    public void hostDefaultPreservesHostTimeout() {
        FirstOutputTimeoutConfig config = FirstOutputTimeoutConfig.hostDefault();

        assertFalse(config.overridesHost());
        assertEquals(120_000L, config.resolveTimeoutMillis(120_000L));
    }

    @Test
    public void customConvertsSecondsToMilliseconds() {
        FirstOutputTimeoutConfig config = new FirstOutputTimeoutConfig(
                FirstOutputTimeoutMode.CUSTOM,
                321L
        );

        assertTrue(config.overridesHost());
        assertEquals(321_000L, config.resolveTimeoutMillis(120_000L));
    }

    @Test
    public void unlimitedUsesCoroutineNeverResumeSentinel() {
        FirstOutputTimeoutConfig config = new FirstOutputTimeoutConfig(
                FirstOutputTimeoutMode.UNLIMITED,
                FirstOutputTimeoutConfig.DEFAULT_CUSTOM_SECONDS
        );

        assertEquals(Long.MAX_VALUE, config.resolveTimeoutMillis(120_000L));
    }

    @Test
    public void rejectsNonPositiveAndOverflowingCustomSeconds() {
        assertThrows(IllegalArgumentException.class, () ->
                new FirstOutputTimeoutConfig(FirstOutputTimeoutMode.CUSTOM, 0L));
        assertThrows(IllegalArgumentException.class, () ->
                new FirstOutputTimeoutConfig(FirstOutputTimeoutMode.CUSTOM, -1L));
        assertThrows(IllegalArgumentException.class, () ->
                new FirstOutputTimeoutConfig(
                        FirstOutputTimeoutMode.CUSTOM,
                        FirstOutputTimeoutConfig.MAX_CUSTOM_SECONDS + 1L
                ));
    }

    @Test
    public void rejectsUnknownModeValue() {
        assertThrows(IllegalArgumentException.class, () ->
                FirstOutputTimeoutMode.fromPreferenceValue("broken"));
    }
}
