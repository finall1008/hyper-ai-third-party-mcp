package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DexDiscoveryHintsTest {
    @Test
    public void snapshotsAndDeduplicatesCandidateNames() {
        DexDiscoveryHints hints = new DexDiscoveryHints(
                List.of("host.Manager", "host.Manager", "host.Policy"),
                Set.of("host.Trace"),
                3,
                12L
        );

        assertEquals(List.of("host.Manager", "host.Policy"), hints.classNames());
        assertEquals(Set.of("host.Trace"), hints.agentTraceClassNames());
        assertEquals(3, hints.matchedMethods());
        assertEquals(12L, hints.elapsedMillis());
        assertTrue(DexDiscoveryHints.empty().isEmpty());
    }
}
