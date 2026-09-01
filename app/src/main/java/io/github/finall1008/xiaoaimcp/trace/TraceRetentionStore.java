package io.github.finall1008.xiaoaimcp.trace;

import android.content.Context;
import android.content.SharedPreferences;

public final class TraceRetentionStore {
    private static final String LOCAL_PREFERENCES = "agent_trace_retention_mirror";

    private TraceRetentionStore() {
    }

    public static TraceRetentionConfig load(
            Context context,
            SharedPreferences remotePreferences
    ) {
        if (remotePreferences != null) {
            TraceRetentionConfig config = TraceRetentionConfig.load(remotePreferences);
            saveMirror(context, config);
            return config;
        }
        return TraceRetentionConfig.load(
                context.getSharedPreferences(LOCAL_PREFERENCES, Context.MODE_PRIVATE)
        );
    }

    public static void saveMirror(Context context, TraceRetentionConfig config) {
        config.save(context.getSharedPreferences(
                LOCAL_PREFERENCES,
                Context.MODE_PRIVATE
        ));
    }
}
