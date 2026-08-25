package io.github.finall1008.xiaoaimcp.hook;

record AgentTraceCapabilities(
        boolean reasoning,
        boolean toolDetails,
        boolean bundlePatch,
        boolean initializationMarker
) {
    boolean any() {
        return reasoning || toolDetails || bundlePatch;
    }
}
