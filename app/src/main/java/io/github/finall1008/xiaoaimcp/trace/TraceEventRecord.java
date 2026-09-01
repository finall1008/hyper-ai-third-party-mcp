package io.github.finall1008.xiaoaimcp.trace;

public record TraceEventRecord(
        long databaseId,
        String executionId,
        long sequence,
        long observedAt,
        String eventType,
        String payloadJson,
        String rawJson
) {
}
