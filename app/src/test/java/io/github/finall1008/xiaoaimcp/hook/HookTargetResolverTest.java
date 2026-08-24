package io.github.finall1008.xiaoaimcp.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HookTargetResolverTest {
    @Test
    public void structurallyResolvesRelocatedManagerAndObjectTypes() throws Exception {
        ResolvedHookTargets targets = resolve(AdapterManager.class, ServerConfig.class);

        assertEquals("structural-discovery", targets.mode());
        assertEquals(AdapterManager.class, targets.managerClass());
        assertTrue(targets.hasTextConfig());
        assertTrue(targets.hasObjectConfig());
        assertNotNull(targets.reloadMethod());
        assertEquals(TestContinuation.class, targets.continuationClass());
    }

    @Test
    public void resolvesWithoutLookingUpKotlinContinuationByName() throws Exception {
        ClassLoader rejectingNamedLookup = new ClassLoader(
                HookTargetResolverTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if ("kotlin.coroutines.Continuation".equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        ResolvedHookTargets targets = resolve(
                rejectingNamedLookup,
                AdapterManager.class,
                ServerConfig.class
        );

        assertEquals(TestContinuation.class, targets.continuationClass());
    }

    @Test
    public void safelyDegradesToUniqueTextReader() throws Exception {
        ResolvedHookTargets targets = resolve(TextOnlyManager.class);

        assertTrue(targets.hasTextConfig());
        assertFalse(targets.hasObjectConfig());
    }

    @Test
    public void keepsConfigCapabilityWhenReloadAnchorIsMissing() throws Exception {
        ResolvedHookTargets targets = resolve(NoReloadManager.class);

        assertTrue(targets.hasTextConfig());
        assertFalse(targets.hasObjectConfig());
        assertNull(targets.reloadMethod());
    }

    @Test
    public void keepsTextCapabilityWhenObjectConstructorChanges() throws Exception {
        ResolvedHookTargets targets = resolve(BrokenObjectManager.class, BrokenServer.class);

        assertTrue(targets.hasTextConfig());
        assertFalse(targets.hasObjectConfig());
    }

    @Test
    public void resolvesRenamedManagerFromUniqueDexKitMarkerHint() throws Exception {
        String managerName = MarkerOnlyManager.class.getName();
        DexDiscoveryHints hints = new DexDiscoveryHints(
                List.of(managerName), Set.of(managerName), 1, 7L);

        ResolvedHookTargets targets = HookTargetResolver.resolve(
                HookTargetResolverTest.class.getClassLoader(),
                List::of,
                hints
        );

        assertEquals("dexkit-discovery", targets.mode());
        assertEquals(MarkerOnlyManager.class, targets.managerClass());
        assertTrue(targets.hasTextConfig());
        assertNull(targets.reloadMethod());
    }

    @Test
    public void ambiguousDexKitManagerHintsFallBackToStructuralDiscovery() throws Exception {
        List<String> hintedNames = List.of(
                MarkerOnlyManager.class.getName(),
                SecondMarkerOnlyManager.class.getName()
        );
        DexDiscoveryHints hints = new DexDiscoveryHints(
                hintedNames, Set.copyOf(hintedNames), 2, 9L);

        ResolvedHookTargets targets = HookTargetResolver.resolve(
                HookTargetResolverTest.class.getClassLoader(),
                () -> List.of(TextOnlyManager.class.getName()),
                hints
        );

        assertEquals("structural-discovery", targets.mode());
        assertEquals(TextOnlyManager.class, targets.managerClass());
    }

    @Test
    public void markerOnlyHintDoesNotOverrideDifferentStructuralManager() throws Exception {
        String hintedName = MarkerOnlyManager.class.getName();
        DexDiscoveryHints hints = new DexDiscoveryHints(
                List.of(hintedName), Set.of(hintedName), 1, 3L);

        ResolvedHookTargets targets = HookTargetResolver.resolve(
                HookTargetResolverTest.class.getClassLoader(),
                () -> List.of(TextOnlyManager.class.getName()),
                hints
        );

        assertEquals("structural-discovery", targets.mode());
        assertEquals(TextOnlyManager.class, targets.managerClass());
    }

    @Test
    public void rejectsAmbiguousManagersAndMissingAnchors() {
        assertThrows(HookTargetResolver.ResolutionException.class,
                () -> resolve(TextOnlyManager.class, SecondManager.class));
        assertThrows(HookTargetResolver.ResolutionException.class,
                () -> resolve(NoAnchors.class));
        assertThrows(HookTargetResolver.ResolutionException.class,
                () -> resolve(MismatchedSuspendManager.class));
    }

    private static ResolvedHookTargets resolve(Class<?>... classes) throws Exception {
        return resolve(HookTargetResolverTest.class.getClassLoader(), classes);
    }

    private static ResolvedHookTargets resolve(ClassLoader classLoader, Class<?>... classes)
            throws Exception {
        List<String> names = java.util.Arrays.stream(classes)
                .map(Class::getName)
                .toList();
        return HookTargetResolver.resolve(
                classLoader,
                () -> names
        );
    }

    public interface TestContinuation {
    }

    public interface OtherContinuation {
    }

    public static final class AdapterManager {
        public String renamedTextReader() {
            return "{}";
        }

        public ServersConfig renamedObjectReader() {
            return null;
        }

        public void syncConfigAndDiscoverIfNeeded() {
        }

        public Object reloadConfig(TestContinuation continuation) {
            return null;
        }

        public Object loadCatalogAndRegister(TestContinuation continuation) {
            return null;
        }
    }

    public static final class TextOnlyManager {
        public String relocatedReader() {
            return "{}";
        }

        public void syncConfigAndDiscoverIfNeeded() {
        }

        public Object reloadConfig(TestContinuation continuation) {
            return null;
        }

        public Object loadCatalogAndRegister(TestContinuation continuation) {
            return null;
        }
    }

    public static final class SecondManager {
        public String anotherReader() {
            return "{}";
        }

        public void syncConfigAndDiscoverIfNeeded() {
        }

        public Object reloadConfig(TestContinuation continuation) {
            return null;
        }

        public Object loadCatalogAndRegister(TestContinuation continuation) {
            return null;
        }
    }

    public static final class NoReloadManager {
        public String textReader() {
            return "{}";
        }

        public void syncConfigAndDiscoverIfNeeded() {
        }

        public Object loadCatalogAndRegister(TestContinuation continuation) {
            return null;
        }
    }

    public static final class BrokenObjectManager {
        public String textReader() {
            return "{}";
        }

        public BrokenServers objectReader() {
            return null;
        }

        public void syncConfigAndDiscoverIfNeeded() {
        }

        public Object reloadConfig(TestContinuation continuation) {
            return null;
        }

        public Object loadCatalogAndRegister(TestContinuation continuation) {
            return null;
        }
    }

    public static final class NoAnchors {
        public String config() {
            return "{}";
        }
    }

    public static final class MarkerOnlyManager {
        public String renamedConfigReader() {
            return "{}";
        }
    }

    public static final class SecondMarkerOnlyManager {
        public String anotherRenamedConfigReader() {
            return "{}";
        }
    }

    public static final class MismatchedSuspendManager {
        public String textReader() {
            return "{}";
        }

        public void syncConfigAndDiscoverIfNeeded() {
        }

        public Object reloadConfig(TestContinuation continuation) {
            return null;
        }

        public Object loadCatalogAndRegister(OtherContinuation continuation) {
            return null;
        }
    }

    public static final class ServersConfig {
        private final List<ServerConfig> servers;
        private final boolean gatewayMode;

        public ServersConfig(List<ServerConfig> servers, Map<String, Object> ignored,
                             boolean gatewayMode) {
            this.servers = servers;
            this.gatewayMode = gatewayMode;
        }

        public List<ServerConfig> getAllServers() {
            return servers;
        }

        public boolean getGatewayMode() {
            return gatewayMode;
        }
    }

    public static final class ServerConfig {
        private final String name;

        public ServerConfig(
                String name,
                String url,
                Object connection,
                String first,
                String second,
                List<?> list,
                Map<?, ?> map,
                String description,
                boolean enabled,
                Map<?, ?> headers,
                Long timeout,
                int seconds,
                boolean toolPrefix,
                boolean builtin,
                List<?> tags,
                String transport,
                String suffix
        ) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static final class BrokenServers {
        public BrokenServers(List<BrokenServer> servers, Map<String, Object> ignored,
                             boolean gatewayMode) {
        }

        public List<BrokenServer> getAllServers() {
            return List.of();
        }

        public boolean getGatewayMode() {
            return false;
        }
    }

    public static final class BrokenServer {
        public BrokenServer(String name) {
        }

        public String getName() {
            return "broken";
        }
    }
}
