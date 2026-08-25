package io.github.finall1008.xiaoaimcp;

public final class BridgeContract {
    public static final String TARGET_PACKAGE = "com.miui.voiceassist";
    public static final String TARGET_PROCESS = "com.miui.voiceassist";
    public static final int MIN_TARGET_MAJOR_VERSION = 8;
    public static final long REFERENCE_VERSION_CODE = 508000030L;
    public static final String REFERENCE_VERSION_NAME = "8.0.30.4121";

    public static final int CONFIG_SCHEMA_VERSION = 1;
    public static final String PREF_GROUP = "mcp_bridge";
    public static final String PREF_SERVERS_JSON = "servers_json";
    public static final String PREF_FILE_POLICY_JSON = "file_policy_json";
    public static final String PREF_AGENT_TRACE_ENABLED = "agent_trace_enabled";
    public static final String PREF_PROMPT_PATCH_JSON = "prompt_patch_json";
    public static final boolean DEFAULT_AGENT_TRACE_ENABLED = true;
    public static final int FILE_POLICY_SCHEMA_VERSION = 2;
    public static final int PROMPT_PATCH_SCHEMA_VERSION = 2;

    private BridgeContract() {
    }
}
