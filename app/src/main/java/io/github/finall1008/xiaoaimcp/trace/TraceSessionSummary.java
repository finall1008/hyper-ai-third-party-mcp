package io.github.finall1008.xiaoaimcp.trace;

public record TraceSessionSummary(
        String sessionKey,
        String hostSessionId,
        String agentId,
        String agentName,
        String preview,
        long startedAt,
        long updatedAt,
        String status,
        boolean partial,
        int turnCount,
        int toolCount
) {
}
