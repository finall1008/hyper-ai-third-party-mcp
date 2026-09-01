package io.github.finall1008.xiaoaimcp.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class TraceRetentionConfigTest {
    @Test
    public void defaultsMatchProductDecision() {
        TraceRetentionConfig config = TraceRetentionConfig.defaults();

        assertEquals(30, config.days());
        assertEquals(100, config.sessions());
        assertEquals("30 天 · 100 个会话", config.summary());
    }

    @Test
    public void eachLimitCanBeUnlimitedIndependently() {
        assertEquals(
                "天数不限 · 500 个会话",
                new TraceRetentionConfig(true, 30, false, 500).summary()
        );
        assertEquals(
                "90 天 · 会话数不限",
                new TraceRetentionConfig(false, 90, true, 100).summary()
        );
        assertEquals(
                "天数不限 · 会话数不限",
                new TraceRetentionConfig(true, 30, true, 100).summary()
        );
    }

    @Test
    public void rejectsEnabledLimitsOutsideBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraceRetentionConfig(false, 0, false, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceRetentionConfig(false, 30, false, 10_001));
    }
}
