package io.github.finall1008.xiaoaimcp.trace;

public record TraceTurnRecord(
        String executionId,
        String sessionKey,
        int turnIndex,
        String agentId,
        String agentName,
        String systemPrompt,
        String toolCatalogJson,
        String userInputJson,
        String executionOptionsJson,
        long startedAt,
        long updatedAt,
        String status
) {
}
