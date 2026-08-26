package io.github.finall1008.xiaoaimcp.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;

public final class FirstOutputTimeoutTargetResolverTest {
    @Test
    public void structurallyResolvesUniqueTimeoutOwner() throws Exception {
        FirstOutputTimeoutTargets targets = FirstOutputTimeoutTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> List.of(RelocatedLlmAgent.class.getName()),
                DexDiscoveryHints.empty()
        );

        assertEquals("structural-discovery", targets.mode());
        assertEquals(
                "getFirstVisibleOutputTimeoutMs$runtime",
                targets.timeoutGetter().getName()
        );
    }

    @Test
    public void usesDexKitCandidatesBeforeFullCatalog() throws Exception {
        DexDiscoveryHints hints = new DexDiscoveryHints(
                List.of(RelocatedLlmAgent.class.getName()),
                java.util.Set.of(),
                1,
                1L
        );

        FirstOutputTimeoutTargets targets = FirstOutputTimeoutTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> {
                    throw new AssertionError("Full catalog should not be loaded");
                },
                hints
        );

        assertEquals("dexkit-discovery", targets.mode());
    }

    @Test
    public void rejectsMissingCompanionTimeoutMethods() {
        assertThrows(FirstOutputTimeoutTargetResolver.ResolutionException.class, () ->
                FirstOutputTimeoutTargetResolver.resolve(
                        getClass().getClassLoader(),
                        () -> List.of(IncompleteAgent.class.getName()),
                        DexDiscoveryHints.empty()
                ));
    }

    @Test
    public void rejectsAmbiguousTimeoutOwners() {
        assertThrows(FirstOutputTimeoutTargetResolver.ResolutionException.class, () ->
                FirstOutputTimeoutTargetResolver.resolve(
                        getClass().getClassLoader(),
                        () -> List.of(
                                RelocatedLlmAgent.class.getName(),
                                SecondLlmAgent.class.getName()
                        ),
                        DexDiscoveryHints.empty()
                ));
    }

    public static class RelocatedLlmAgent {
        public long getFirstVisibleOutputTimeoutMs$runtime() {
            return 120_000L;
        }

        public void setFirstVisibleOutputTimeoutMs$runtime(long value) {
        }

        public long getStreamIdleTimeoutMs$runtime() {
            return 60_000L;
        }

        public void setStreamIdleTimeoutMs$runtime(long value) {
        }

        public long getCallAbsoluteTimeoutMs$runtime() {
            return 600_000L;
        }

        public void setCallAbsoluteTimeoutMs$runtime(long value) {
        }
    }

    public static final class SecondLlmAgent extends RelocatedLlmAgent {
        @Override
        public long getFirstVisibleOutputTimeoutMs$runtime() {
            return 120_000L;
        }

        @Override
        public void setFirstVisibleOutputTimeoutMs$runtime(long value) {
        }

        @Override
        public long getStreamIdleTimeoutMs$runtime() {
            return 60_000L;
        }

        @Override
        public void setStreamIdleTimeoutMs$runtime(long value) {
        }

        @Override
        public long getCallAbsoluteTimeoutMs$runtime() {
            return 600_000L;
        }

        @Override
        public void setCallAbsoluteTimeoutMs$runtime(long value) {
        }
    }

    public static final class IncompleteAgent {
        public long getFirstVisibleOutputTimeoutMs$runtime() {
            return 120_000L;
        }

        public void setFirstVisibleOutputTimeoutMs$runtime(long value) {
        }
    }
}
