package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AgentSessionTraceTargetResolverTest {
    @Test
    public void resolvesUniqueAgentExecutorAndOptionalToolCatalogPath() throws Exception {
        AgentSessionTraceTargets targets = AgentSessionTraceTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> List.of(
                        FakeExecutor.class.getName(),
                        FakeCaller.class.getName(),
                        FakeCaller.InvokeSuspend.class.getName()
                ),
                DexDiscoveryHints.empty()
        );

        assertTrue(targets.available());
        assertTrue(targets.installable());
        assertEquals("execute", targets.execute().getName());
        assertEquals("getAgentMeta", targets.getAgentMeta().getName());
        assertNotNull(targets.agentManagerField());
        assertNotNull(targets.agentManagerGet());
        assertTrue(targets.callSites().stream().anyMatch(
                method -> method.getDeclaringClass() == FakeCaller.InvokeSuspend.class
                        && method.getName().equals("invokeSuspend")
        ));
    }

    @Test
    public void usesExactDexKitCallerClassesWithoutLoadingFullCatalog() throws Exception {
        DexDiscoveryHints hints = new DexDiscoveryHints(
                List.of(),
                Set.of(FakeExecutor.class.getName()),
                Set.of(FakeCaller.InvokeSuspend.class.getName()),
                List.of(),
                2,
                1L
        );

        AgentSessionTraceTargets targets = AgentSessionTraceTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> {
                    throw new AssertionError("Full catalog should not be loaded");
                },
                hints
        );

        assertTrue(targets.installable());
        assertTrue(targets.callSites().stream().anyMatch(
                method -> method.getName().equals("invokeSuspend")
        ));
    }

    @Test
    public void executorWithoutCallSitesFailsClosedForInstallation() throws Exception {
        AgentSessionTraceTargets targets = AgentSessionTraceTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> List.of(FakeExecutor.class.getName()),
                DexDiscoveryHints.empty()
        );

        assertTrue(targets.available());
        assertTrue(!targets.installable());
    }

    @Test
    public void ambiguousExecutorsFailClosed() throws Exception {
        AgentSessionTraceTargets targets = AgentSessionTraceTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> List.of(FakeExecutor.class.getName(), SecondExecutor.class.getName()),
                DexDiscoveryHints.empty()
        );

        assertTrue(!targets.available());
        assertTrue(targets.mode().contains("ambiguous"));
    }

    public interface Callback {
        Object invoke(Object event, Object continuation);
    }

    public interface Continuation {
    }

    public interface Executor {
        Object execute(
                String agentId,
                FakeUserInput input,
                FakeOptions options,
                Callback callback,
                Continuation continuation
        );

        FakeMeta getAgentMeta(String agentId);
    }

    public static final class FakeUserInput {
        public String getText() {
            return "hello";
        }

        public String getAgentId() {
            return "agent";
        }
    }

    public static final class FakeOptions {
        public String getPromptOverride() {
            return null;
        }

        public String getTaskRole() {
            return "FOREGROUND";
        }
    }

    public static final class FakeMeta {
        public String getResolvedSystemPrompt() {
            return "system";
        }
    }

    public static final class FakeAgent {
        public String getResolvedSystemPrompt() {
            return "system";
        }

        public List<Object> getToolDefinitions() {
            return List.of();
        }
    }

    public static final class FakeManager {
        public FakeAgent get(String id) {
            return new FakeAgent();
        }
    }

    public static class FakeExecutor implements Executor {
        public final FakeManager manager = new FakeManager();

        @Override
        public Object execute(
                String agentId,
                FakeUserInput input,
                FakeOptions options,
                Callback callback,
                Continuation continuation
        ) {
            return null;
        }

        @Override
        public FakeMeta getAgentMeta(String agentId) {
            return new FakeMeta();
        }
    }

    public static final class FakeCaller {
        public final Executor executor;

        public FakeCaller(Executor executor) {
            this.executor = executor;
        }

        public static final class InvokeSuspend {
            private final FakeCaller owner;

            public InvokeSuspend(FakeCaller owner) {
                this.owner = owner;
            }

            public Object invokeSuspend(Object ignored) {
                return owner.executor.execute(
                        "agent",
                        new FakeUserInput(),
                        new FakeOptions(),
                        (event, continuation) -> null,
                        new Continuation() {
                        }
                );
            }
        }
    }

    public static final class SecondExecutor extends FakeExecutor {
        public final SecondManager secondManager = new SecondManager();

        @Override
        public Object execute(
                String agentId,
                FakeUserInput input,
                FakeOptions options,
                Callback callback,
                Continuation continuation
        ) {
            return null;
        }

        @Override
        public FakeMeta getAgentMeta(String agentId) {
            return new FakeMeta();
        }
    }

    public static final class SecondManager {
        public FakeAgent get(String id) {
            return new FakeAgent();
        }
    }
}
