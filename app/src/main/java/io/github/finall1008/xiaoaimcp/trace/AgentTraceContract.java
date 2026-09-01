package io.github.finall1008.xiaoaimcp.trace;

import android.net.Uri;

public final class AgentTraceContract {
    public static final int SCHEMA_VERSION = 1;
    public static final String AUTHORITY =
            "io.github.finall1008.xiaoaimcp.agenttrace";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri INGEST_URI = CONTENT_URI.buildUpon()
            .appendPath("ingest")
            .build();

    public static final String RECORD_TURN_START = "turn_start";
    public static final String RECORD_EVENT = "event";
    public static final String RECORD_GAP = "gap";

    public static final String PREF_RETENTION_DAYS_UNLIMITED =
            "agent_trace_retention_days_unlimited";
    public static final String PREF_RETENTION_DAYS =
            "agent_trace_retention_days";
    public static final String PREF_RETENTION_SESSIONS_UNLIMITED =
            "agent_trace_retention_sessions_unlimited";
    public static final String PREF_RETENTION_SESSIONS =
            "agent_trace_retention_sessions";

    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_RETENTION_SESSIONS = 100;
    public static final int MAX_RETENTION_DAYS = 3650;
    public static final int MAX_RETENTION_SESSIONS = 10_000;

    public static final String EXTRA_SESSION_KEY = "agent_trace_session_key";

    private AgentTraceContract() {
    }
}
