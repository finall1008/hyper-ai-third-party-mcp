package io.github.finall1008.xiaoaimcp.trace;

import android.content.SharedPreferences;

import java.util.Objects;

public record TraceRetentionConfig(
        boolean daysUnlimited,
        int days,
        boolean sessionsUnlimited,
        int sessions
) {
    public TraceRetentionConfig {
        if ((!daysUnlimited && (days < 1 || days > AgentTraceContract.MAX_RETENTION_DAYS))
                || (!sessionsUnlimited
                && (sessions < 1 || sessions > AgentTraceContract.MAX_RETENTION_SESSIONS))) {
            throw new IllegalArgumentException("Invalid Agent Trace retention bounds");
        }
        if (days < 1 || days > AgentTraceContract.MAX_RETENTION_DAYS) {
            days = AgentTraceContract.DEFAULT_RETENTION_DAYS;
        }
        if (sessions < 1 || sessions > AgentTraceContract.MAX_RETENTION_SESSIONS) {
            sessions = AgentTraceContract.DEFAULT_RETENTION_SESSIONS;
        }
    }

    public static TraceRetentionConfig defaults() {
        return new TraceRetentionConfig(
                false,
                AgentTraceContract.DEFAULT_RETENTION_DAYS,
                false,
                AgentTraceContract.DEFAULT_RETENTION_SESSIONS
        );
    }

    public static TraceRetentionConfig load(SharedPreferences preferences) {
        if (preferences == null) {
            return defaults();
        }
        boolean daysUnlimited = preferences.getBoolean(
                AgentTraceContract.PREF_RETENTION_DAYS_UNLIMITED,
                false
        );
        boolean sessionsUnlimited = preferences.getBoolean(
                AgentTraceContract.PREF_RETENTION_SESSIONS_UNLIMITED,
                false
        );
        int days = preferences.getInt(
                AgentTraceContract.PREF_RETENTION_DAYS,
                AgentTraceContract.DEFAULT_RETENTION_DAYS
        );
        int sessions = preferences.getInt(
                AgentTraceContract.PREF_RETENTION_SESSIONS,
                AgentTraceContract.DEFAULT_RETENTION_SESSIONS
        );
        try {
            return new TraceRetentionConfig(
                    daysUnlimited,
                    days,
                    sessionsUnlimited,
                    sessions
            );
        } catch (IllegalArgumentException ignored) {
            return defaults();
        }
    }

    public void save(SharedPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        preferences.edit()
                .putBoolean(
                        AgentTraceContract.PREF_RETENTION_DAYS_UNLIMITED,
                        daysUnlimited
                )
                .putInt(AgentTraceContract.PREF_RETENTION_DAYS, days)
                .putBoolean(
                        AgentTraceContract.PREF_RETENTION_SESSIONS_UNLIMITED,
                        sessionsUnlimited
                )
                .putInt(AgentTraceContract.PREF_RETENTION_SESSIONS, sessions)
                .apply();
    }

    public String summary() {
        String age = daysUnlimited ? "天数不限" : days + " 天";
        String count = sessionsUnlimited ? "会话数不限" : sessions + " 个会话";
        return age + " · " + count;
    }
}
