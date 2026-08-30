package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AgentTraceTargetResolverTest {
    @Test
    public void resolvesReasoningFilterWithStreamOriginParameter() throws Exception {
        AgentTraceTargets targets = AgentTraceTargetResolver.resolve(
                getClass().getClassLoader(),
                () -> names(
                        TwoParameterReasoningFilter.class,
                        RelocatedReasoning.class,
                        RelocatedEnvelopeMapper.class,
                        RelocatedReasoningMapper.class,
                        RelocatedToolBuilder.class,
                        RelocatedBundleLoader.class
                ),
                DexDiscoveryHints.empty()
        );

        assertTrue(targets.hasAllCapabilities());
        assertEquals(3, targets.bundleLoader().getParameterCount());
    }

    private static List<String> names(Class<?>... classes) {
        return Arrays.stream(classes).map(Class::getName).toList();
    }

    public static final class StreamOrigin {
    }

    public static final class ReasoningChunk {
    }

    public static final class TwoParameterReasoningFilter {
        public boolean shouldSuppressReasoning() {
            return true;
        }

        public ReasoningChunk stripDelta(String text, StreamOrigin origin) {
            return new ReasoningChunk();
        }

        public ReasoningChunk stripCompleted(String text, StreamOrigin origin) {
            return new ReasoningChunk();
        }

        public ReasoningChunk flushPending() {
            return new ReasoningChunk();
        }
    }

    public abstract static class FakeStreamingState {
    }

    public static final class RelocatedReasoning extends FakeStreamingState {
        private final String text;

        public RelocatedReasoning(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    public static final class ReasoningEnvelope {
        public String getText() {
            return "reasoning";
        }
    }

    public static final class RelocatedEnvelopeMapper {
        public ReasoningEnvelope from(FakeStreamingState state) {
            return new ReasoningEnvelope();
        }
    }

    public static final class MappedInstructions {
        public List<Object> getInstructions() {
            return List.of();
        }
    }

    public static final class RelocatedReasoningMapper {
        public MappedInstructions map(ReasoningEnvelope envelope, String dialogId) {
            return new MappedInstructions();
        }
    }

    public static final class RelocatedToolBuilder {
        public Object buildToolCallItem(String event, String payload, String dialogId) {
            return new Object();
        }

        public Object buildToastStream(String text, String dialogId) {
            return new Object();
        }
    }

    public static final class RelocatedBundleLoader {
        public boolean loadScript(String path, boolean shared) {
            return loadScript(path, shared, null);
        }

        public boolean loadScript(String path, boolean shared, FakeReactContext context) {
            return true;
        }

        public Object getReactInstanceManager() {
            return new Object();
        }

        public Object getJavaScriptExecutorFactory() {
            return new Object();
        }
    }

    public static final class FakeReactContext {
    }
}
