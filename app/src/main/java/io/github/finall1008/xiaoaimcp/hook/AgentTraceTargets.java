package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Independently resolved host entry points used by the Agent trace capability. */
record AgentTraceTargets(
        String mode,
        Method reasoningSuppressor,
        Constructor<?> reasoningConstructor,
        Method reasoningResponseMapper,
        Method envelopeFrom,
        Method reasoningMapper,
        Method toolCallBuilder,
        Method toastStreamBuilder,
        Method bundleLoader
) {
    boolean hasReasoning() {
        return reasoningSuppressor != null && reasoningConstructor != null;
    }

    boolean hasToolDetails() {
        return toolCallBuilder != null;
    }

    boolean hasBundlePatch() {
        return bundleLoader != null;
    }

    boolean hasReasoningMarkerPath() {
        return hasReasoning() && envelopeFrom != null && reasoningMapper != null;
    }

    boolean isEmpty() {
        return !hasReasoning() && !hasToolDetails() && !hasBundlePatch();
    }

    boolean hasAllCapabilities() {
        return hasReasoningMarkerPath() && hasToolDetails() && hasBundlePatch();
    }

    AgentTraceTargets withFallback(AgentTraceTargets fallback) {
        if (fallback == null) {
            return this;
        }
        boolean changed = (!hasReasoning() && fallback.hasReasoning())
                || (envelopeFrom == null && fallback.envelopeFrom() != null)
                || (reasoningResponseMapper == null && fallback.reasoningResponseMapper() != null)
                || (reasoningMapper == null && fallback.reasoningMapper() != null)
                || (toolCallBuilder == null && fallback.toolCallBuilder() != null)
                || (toastStreamBuilder == null && fallback.toastStreamBuilder() != null)
                || (bundleLoader == null && fallback.bundleLoader() != null);
        return new AgentTraceTargets(
                changed ? mode + "+" + fallback.mode() : mode,
                reasoningSuppressor != null ? reasoningSuppressor : fallback.reasoningSuppressor(),
                reasoningConstructor != null ? reasoningConstructor : fallback.reasoningConstructor(),
                reasoningResponseMapper != null
                        ? reasoningResponseMapper : fallback.reasoningResponseMapper(),
                envelopeFrom != null ? envelopeFrom : fallback.envelopeFrom(),
                reasoningMapper != null ? reasoningMapper : fallback.reasoningMapper(),
                toolCallBuilder != null ? toolCallBuilder : fallback.toolCallBuilder(),
                toastStreamBuilder != null ? toastStreamBuilder : fallback.toastStreamBuilder(),
                bundleLoader != null ? bundleLoader : fallback.bundleLoader()
        );
    }
}
