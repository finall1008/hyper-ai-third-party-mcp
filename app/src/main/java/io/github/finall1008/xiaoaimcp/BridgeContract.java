package io.github.finall1008.xiaoaimcp;

public final class BridgeContract {
    public static final String TARGET_PACKAGE = "com.miui.voiceassist";
    public static final String TARGET_PROCESS = "com.miui.voiceassist";
    public static final String TARGET_LAUNCH_ACTIVITY =
            "com.xiaomi.voiceassistant.LaunchHomeRouterActivity";
    public static final int MIN_TARGET_MAJOR_VERSION = 8;
    public static final String NATIVE_MCP_VERSION_NAME = "8.2.3.1619";
    public static final long REFERENCE_VERSION_CODE = 508000030L;
    public static final String REFERENCE_VERSION_NAME = "8.0.30.4121";

    public static final int CONFIG_SCHEMA_VERSION = 1;
    public static final String PREF_GROUP = "mcp_bridge";
    /** Retained only so 1.6.x can export configurations created by older releases. */
    public static final String PREF_SERVERS_JSON = "servers_json";
    public static final String PREF_LEGACY_MCP_MIGRATION_NOTICE_SEEN =
            "legacy_mcp_migration_notice_seen";
    public static final String PREF_FILE_POLICY_JSON = "file_policy_json";
    public static final String PREF_AGENT_TRACE_ENABLED = "agent_trace_enabled";
    public static final String PREF_FIRST_OUTPUT_TIMEOUT_MODE =
            "first_output_timeout_mode";
    public static final String PREF_FIRST_OUTPUT_TIMEOUT_SECONDS =
            "first_output_timeout_seconds";
    public static final String PREF_PROMPT_PATCH_JSON = "prompt_patch_json";
    public static final boolean DEFAULT_AGENT_TRACE_ENABLED = true;
    public static final int FILE_POLICY_SCHEMA_VERSION = 2;
    public static final int PROMPT_PATCH_SCHEMA_VERSION = 3;

    private BridgeContract() {
    }
}
