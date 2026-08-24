package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class FilePolicyHookResolverTest {
    @Test
    public void structurallyResolvesRelocatedMutationAndLockscreenTargets() throws Exception {
        FilePolicyHookTargets targets = resolve(
                RelocatedUriResolver.class,
                RelocatedStorage.class,
                RelocatedLockscreenAllowlist.class,
                RelocatedToolCall.class,
                RelocatedRiskManager.class,
                RelocatedRiskContext.class,
                RelocatedMovePair.class
        );

        assertEquals("structural-discovery", targets.mode());
        assertTrue(targets.hasMutationPolicy());
        assertTrue(targets.hasLockscreenPolicy());
        assertTrue(targets.hasConfirmationPolicy());
        assertEquals("isExternalUserAsset", targets.externalUserAssetCheck().getName());
        assertTrue(targets.lockscreenCliMatchers().size() >= 2);
    }

    @Test
    public void keepsIndependentMutationCapabilityWhenLockscreenTargetIsMissing() throws Exception {
        FilePolicyHookTargets targets = resolve(
                RelocatedUriResolver.class,
                RelocatedStorage.class
        );

        assertTrue(targets.hasMutationPolicy());
        assertFalse(targets.hasLockscreenPolicy());
    }

    @Test
    public void keepsIndependentLockscreenCapabilityWhenMutationTargetIsMissing() throws Exception {
        FilePolicyHookTargets targets = resolve(
                RelocatedLockscreenAllowlist.class,
                RelocatedToolCall.class
        );

        assertFalse(targets.hasMutationPolicy());
        assertTrue(targets.hasLockscreenPolicy());
    }

    @Test
    public void keepsIndependentConfirmationCapabilityWhenOtherTargetsAreMissing()
            throws Exception {
        FilePolicyHookTargets targets = resolve(
                RelocatedRiskManager.class,
                RelocatedRiskContext.class,
                RelocatedMovePair.class
        );

        assertFalse(targets.hasMutationPolicy());
        assertFalse(targets.hasLockscreenPolicy());
        assertTrue(targets.hasConfirmationPolicy());
    }

    @Test
    public void mergesDexKitCandidatesWithIndependentStructuralFallbacks() throws Exception {
        String mutationClass = RelocatedUriResolver.class.getName();
        DexDiscoveryHints hints = new DexDiscoveryHints(
                List.of(mutationClass), Set.of(), 2, 4L);
        List<String> structuralClasses = List.of(
                RelocatedLockscreenAllowlist.class.getName(),
                RelocatedToolCall.class.getName(),
                RelocatedRiskManager.class.getName(),
                RelocatedRiskContext.class.getName(),
                RelocatedMovePair.class.getName()
        );

        FilePolicyHookTargets targets = FilePolicyHookResolver.resolve(
                FilePolicyHookResolverTest.class.getClassLoader(),
                () -> structuralClasses,
                hints
        );

        assertEquals("dexkit-discovery+structural-discovery", targets.mode());
        assertTrue(targets.hasMutationPolicy());
        assertTrue(targets.hasLockscreenPolicy());
        assertTrue(targets.hasConfirmationPolicy());
    }

    @Test
    public void failsClosedWhenAllCapabilitiesAreAmbiguous() {
        assertThrows(FilePolicyHookResolver.ResolutionException.class,
                () -> resolve(RelocatedUriResolver.class, SecondUriResolver.class,
                        RelocatedStorage.class));
    }

    private static FilePolicyHookTargets resolve(Class<?>... classes) throws Exception {
        List<String> names = Arrays.stream(classes).map(Class::getName).toList();
        return FilePolicyHookResolver.resolve(
                FilePolicyHookResolverTest.class.getClassLoader(),
                () -> names
        );
    }

    public static final class RelocatedStorage {
        public boolean isExternalUserAsset(String path) {
            return true;
        }

        public Object checkPrivateDataWriteProtection(File file) {
            return null;
        }

        public Object checkCrossGroupOperation(File source, File destination) {
            return null;
        }
    }

    public static class RelocatedUriResolver {
        public Object resolve(String path, String agentId, File agentDir,
                              RelocatedStorage storage, boolean write) {
            return null;
        }

        public Object resolveToFile(String path, String agentId, File agentDir,
                                    RelocatedStorage storage, boolean write) {
            return null;
        }

        public List<String> listVirtualRoot() {
            return List.of();
        }

        public Object relocatedCallSite(String path, RelocatedStorage storage,
                                        boolean write, String label) {
            return null;
        }
    }

    public static final class SecondUriResolver extends RelocatedUriResolver {
        @Override
        public Object resolve(String path, String agentId, File agentDir,
                              RelocatedStorage storage, boolean write) {
            return null;
        }

        @Override
        public Object resolveToFile(String path, String agentId, File agentDir,
                                    RelocatedStorage storage, boolean write) {
            return null;
        }

        @Override
        public List<String> listVirtualRoot() {
            return List.of();
        }
    }

    public static final class RelocatedToolCall {
        public String getName() {
            return "read_file";
        }

        public Map<String, Object> getArguments() {
            return Map.of();
        }
    }

    public static final class RelocatedLockscreenAllowlist {
        public boolean isAllowed(RelocatedToolCall toolCall) {
            return false;
        }

        public boolean isCommandNameAllowed(String command, List<String> args) {
            return false;
        }

        public boolean relocatedCliMatcher(String command, List<String> args) {
            return false;
        }
    }

    public static final class RelocatedRiskContext {
        public String getAgentId() {
            return "osbot.main";
        }

        public Map<String, Object> getSharedState() {
            return Map.of();
        }

        public String getSessionId() {
            return "session";
        }
    }

    public static final class RelocatedMovePair {
        public String getFirst() {
            return "/sdcard/source";
        }

        public String getSecond() {
            return "/sdcard/destination";
        }
    }

    public static final class RelocatedRiskManager {
        public Object checkToolRisk(String name, Object schema, Object arguments,
                                    RelocatedRiskContext context, Object continuation) {
            return null;
        }

        public Object checkCliCommand(String command, List<String> arguments,
                                      RelocatedRiskContext context, Object continuation) {
            return null;
        }

        public Object confirmByCategory$runtime(String category, String title,
                                                RelocatedRiskContext context,
                                                Object continuation) {
            return null;
        }

        public Object requestConsent$runtime(String title, Object mode, String category,
                                             RelocatedRiskContext context,
                                             Object continuation) {
            return null;
        }

        public boolean relocatedFileExemption(List<String> paths,
                                               RelocatedRiskContext context,
                                               RelocatedMovePair move,
                                               String message) {
            return false;
        }
    }
}
