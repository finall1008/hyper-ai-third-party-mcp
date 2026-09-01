package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class AgentTraceEventNormalizerTest {
    @Test
    public void normalizesReasoningTextAndToolLifecycleWithoutToStringGuessing() {
        AgentTraceEventNormalizer.NormalizedEvent delta =
                AgentTraceEventNormalizer.normalize(new StreamDelta("thinking", 1, "s1"));
        AgentTraceEventNormalizer.NormalizedEvent reasoning =
                AgentTraceEventNormalizer.normalize(new ReasoningDone("full", "s1"));
        AgentTraceEventNormalizer.NormalizedEvent text =
                AgentTraceEventNormalizer.normalize(new TextDone("answer", "s2", List.of()));
        AgentTraceEventNormalizer.NormalizedEvent tool =
                AgentTraceEventNormalizer.normalize(new ToolExecuting(
                        "maps", "c1", "{\"q\":\"x\"}", List.of(), null, "", null));

        assertEquals("STREAM_DELTA", delta.type());
        assertEquals("REASONING_COMPLETED", reasoning.type());
        assertEquals("TEXT_COMPLETED", text.type());
        assertEquals("TOOL_EXECUTING", tool.type());
        assertEquals("thinking", delta.payload().optString("text"));
        assertEquals("c1", tool.payload().optString("toolCallId"));
    }

    @Test
    public void unknownEventKeepsStableGetterFieldsAndNeverCallsToString() {
        AgentTraceEventNormalizer.NormalizedEvent unknown =
                AgentTraceEventNormalizer.normalize(new FutureEvent());

        assertEquals("UNKNOWN", unknown.type());
        assertEquals("future", unknown.payload().optString("newField"));
        assertFalse(unknown.payload().has("toString"));
    }

    @Test
    public void attachmentBinaryContentIsNotCopiedIntoTrace() {
        JSONObjectHolder holder = new JSONObjectHolder(
                AgentTraceValueEncoder.encodeKnownObject(new ImageContent("AAAA", "image/png"))
        );

        assertEquals(
                "attachment_content_omitted",
                holder.object().optJSONObject("base64Data").optString("_encoding")
        );
        assertEquals("image/png", holder.object().optString("mimeType"));
    }

    public record StreamDelta(String text, int index, String streamId) {
        public String getText() { return text; }
        public int getIndex() { return index; }
        public String getStreamId() { return streamId; }
    }

    public record ReasoningDone(String fullText, String streamId) {
        public String getFullText() { return fullText; }
        public String getStreamId() { return streamId; }
    }

    public record TextDone(String fullText, String streamId, List<Object> fileAttachments) {
        public String getFullText() { return fullText; }
        public String getStreamId() { return streamId; }
        public List<Object> getFileAttachments() { return fileAttachments; }
    }

    public record ToolExecuting(
            String toolName,
            String toolCallId,
            String arguments,
            List<Object> toolCalls,
            String assistantMessageId,
            String assistantContent,
            String reasoningContent
    ) {
        public String getToolName() { return toolName; }
        public String getToolCallId() { return toolCallId; }
        public String getArguments() { return arguments; }
        public List<Object> getToolCalls() { return toolCalls; }
        public String getAssistantMessageId() { return assistantMessageId; }
        public String getAssistantContent() { return assistantContent; }
        public String getReasoningContent() { return reasoningContent; }
    }

    public static final class FutureEvent {
        public String getNewField() {
            return "future";
        }

        public Map<String, Object> getStructured() {
            return Map.of("enabled", true);
        }

        @Override
        public String toString() {
            throw new AssertionError("Unknown events must not be classified through toString");
        }
    }

    public record ImageContent(String base64Data, String mimeType) {
        public String getBase64Data() { return base64Data; }
        public String getMimeType() { return mimeType; }
    }

    private record JSONObjectHolder(org.json.JSONObject object) {
    }
}
