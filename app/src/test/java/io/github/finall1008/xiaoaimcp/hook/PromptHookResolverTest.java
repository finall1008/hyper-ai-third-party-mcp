package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PromptHookResolverTest {
    @Test
    public void structurallyResolvesDescriptorAndCacheInvalidator() throws Exception {
        PromptHookTargets targets = resolve(RelocatedDescriptor.class, RelocatedPromptManager.class,
                RelocatedInvalidator.class);

        assertEquals("structural-discovery", targets.mode());
        assertEquals("resolvePrompt", targets.resolvePrompt().getName());
        assertEquals("fileName", targets.fileNameField().getName());
        assertEquals(1, targets.callSites().size());
        assertTrue(targets.canInvalidateMemoryCache());
    }

    @Test
    public void keepsPromptHookWhenInvalidatorIsMissing() throws Exception {
        PromptHookTargets targets = resolve(RelocatedDescriptor.class);

        assertFalse(targets.canInvalidateMemoryCache());
    }

    @Test
    public void failsClosedWhenDescriptorsAreAmbiguous() {
        assertThrows(PromptHookResolver.ResolutionException.class,
                () -> resolve(RelocatedDescriptor.class, SecondDescriptor.class));
    }

    private static PromptHookTargets resolve(Class<?>... classes) throws Exception {
        List<String> names = Arrays.stream(classes).map(Class::getName).toList();
        return PromptHookResolver.resolve(
                PromptHookResolverTest.class.getClassLoader(),
                () -> names,
                DexDiscoveryHints.empty()
        );
    }

    public enum Scope { AGENT }

    public static final class FakeFileSystem {
    }

    public static class RelocatedDescriptor {
        private final String fileName;
        private final Scope scope;

        public RelocatedDescriptor(String fileName, Scope scope) {
            this.fileName = fileName;
            this.scope = scope;
        }

        public String resolvePrompt(FakeFileSystem fs, String agentId, File agentDir,
                                    boolean overridesEnabled) {
            return fileName + agentId + agentDir + overridesEnabled + scope;
        }
    }

    public static final class SecondDescriptor {
        private final String name;
        private final Scope scope;

        public SecondDescriptor(String name, Scope scope) {
            this.name = name;
            this.scope = scope;
        }

        public String anotherResolve(FakeFileSystem fs, String agentId, File agentDir,
                                     boolean overridesEnabled) {
            return name;
        }
    }

    public static final class RelocatedInvalidator {
        public void invalidateMemoryCache() {
        }
    }

    public static final class RelocatedPromptManager {
        public static final RelocatedInvalidator COMPANION = new RelocatedInvalidator();

        public static String load(RelocatedDescriptor descriptor, FakeFileSystem fs,
                                  String agentId, File agentDir) {
            return descriptor.resolvePrompt(fs, agentId, agentDir, true);
        }
    }
}
