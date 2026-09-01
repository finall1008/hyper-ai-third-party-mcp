package io.github.finall1008.xiaoaimcp.trace;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class AgentTraceDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "agent_trace.db";
    private static final int DATABASE_VERSION = 1;
    private static volatile AgentTraceDatabase instance;

    private final Context context;

    private AgentTraceDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    public static AgentTraceDatabase get(Context context) {
        AgentTraceDatabase current = instance;
        if (current != null) {
            return current;
        }
        synchronized (AgentTraceDatabase.class) {
            current = instance;
            if (current == null) {
                current = new AgentTraceDatabase(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(false);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions ("
                + "session_key TEXT PRIMARY KEY NOT NULL,"
                + "host_session_id TEXT,"
                + "chat_id TEXT,"
                + "agent_id TEXT NOT NULL DEFAULT '',"
                + "agent_name TEXT NOT NULL DEFAULT '',"
                + "preview TEXT NOT NULL DEFAULT '',"
                + "started_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'RUNNING',"
                + "partial INTEGER NOT NULL DEFAULT 0,"
                + "turn_count INTEGER NOT NULL DEFAULT 0,"
                + "tool_count INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE TABLE turns ("
                + "execution_id TEXT PRIMARY KEY NOT NULL,"
                + "session_key TEXT NOT NULL,"
                + "turn_index INTEGER NOT NULL,"
                + "agent_id TEXT NOT NULL DEFAULT '',"
                + "agent_name TEXT NOT NULL DEFAULT '',"
                + "system_prompt TEXT NOT NULL DEFAULT '',"
                + "tool_catalog_json TEXT NOT NULL DEFAULT '[]',"
                + "user_input_json TEXT NOT NULL DEFAULT '{}',"
                + "execution_options_json TEXT NOT NULL DEFAULT '{}',"
                + "started_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'RUNNING'"
                + ")");
        db.execSQL("CREATE INDEX turns_session_index ON turns(session_key, turn_index)");
        db.execSQL("CREATE TABLE events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "execution_id TEXT NOT NULL,"
                + "sequence INTEGER NOT NULL,"
                + "observed_at INTEGER NOT NULL,"
                + "event_type TEXT NOT NULL,"
                + "payload_json TEXT NOT NULL DEFAULT '{}',"
                + "raw_json TEXT NOT NULL,"
                + "UNIQUE(execution_id, sequence) ON CONFLICT IGNORE"
                + ")");
        db.execSQL("CREATE INDEX events_execution_index "
                + "ON events(execution_id, sequence)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException(
                "Unsupported Agent Trace database migration: " + oldVersion + " -> " + newVersion
        );
    }

    public synchronized int ingestCompressed(InputStream input, TraceRetentionConfig retention)
            throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        int accepted = 0;
        db.beginTransaction();
        try (GZIPInputStream gzip = new GZIPInputStream(input);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     gzip,
                     StandardCharsets.UTF_8
             ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ingestLine(db, new JSONObject(line), line);
                    accepted++;
                } catch (Throwable ignored) {
                    // A malformed record is isolated to its line and never affects the host.
                }
            }
            pruneLocked(db, retention, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (accepted > 0) {
            notifyChanged();
        }
        return accepted;
    }

    private static void ingestLine(SQLiteDatabase db, JSONObject record, String rawLine) {
        if (record.optInt("schema_version", -1) != AgentTraceContract.SCHEMA_VERSION) {
            return;
        }
        String recordType = record.optString("record_type", "");
        if (AgentTraceContract.RECORD_TURN_START.equals(recordType)) {
            ingestTurnStart(db, record);
        } else if (AgentTraceContract.RECORD_EVENT.equals(recordType)) {
            ingestEvent(db, record, rawLine);
        } else if (AgentTraceContract.RECORD_GAP.equals(recordType)) {
            ingestGap(db, record, rawLine);
        }
    }

    private static void ingestTurnStart(SQLiteDatabase db, JSONObject record) {
        String executionId = required(record, "execution_id");
        String sessionKey = required(record, "session_key");
        long observedAt = record.optLong("observed_at", System.currentTimeMillis());
        String hostSessionId = nullable(record, "host_session_id");
        String chatId = nullable(record, "chat_id");
        if (hostSessionId == null && chatId != null) {
            String knownSession = sessionForChat(db, chatId);
            if (knownSession != null) {
                sessionKey = knownSession;
            }
        }
        String agentId = record.optString("agent_id", "");
        String agentName = record.optString("agent_name", "");
        String preview = preview(record.optString("user_text", ""), 240);

        ContentValues session = new ContentValues();
        session.put("session_key", sessionKey);
        session.put("host_session_id", hostSessionId);
        session.put("chat_id", chatId);
        session.put("agent_id", agentId);
        session.put("agent_name", agentName);
        session.put("preview", preview);
        session.put("started_at", observedAt);
        session.put("updated_at", observedAt);
        session.put("status", "RUNNING");
        db.insertWithOnConflict(
                "sessions",
                null,
                session,
                SQLiteDatabase.CONFLICT_IGNORE
        );
        ContentValues sessionUpdate = new ContentValues();
        sessionUpdate.put("updated_at", observedAt);
        sessionUpdate.put("status", "RUNNING");
        if (!agentId.isEmpty()) {
            sessionUpdate.put("agent_id", agentId);
        }
        if (!agentName.isEmpty()) {
            sessionUpdate.put("agent_name", agentName);
        }
        if (!preview.isEmpty()) {
            sessionUpdate.put("preview", preview);
        }
        if (hostSessionId != null) {
            sessionUpdate.put("host_session_id", hostSessionId);
        }
        if (chatId != null) {
            sessionUpdate.put("chat_id", chatId);
        }
        db.update("sessions", sessionUpdate, "session_key = ?", new String[]{sessionKey});

        int turnIndex = nextTurnIndex(db, sessionKey);
        ContentValues turn = new ContentValues();
        turn.put("execution_id", executionId);
        turn.put("session_key", sessionKey);
        turn.put("turn_index", turnIndex);
        turn.put("agent_id", agentId);
        turn.put("agent_name", agentName);
        turn.put("system_prompt", record.optString("system_prompt", ""));
        turn.put("tool_catalog_json", jsonText(record, "tool_catalog", "[]"));
        turn.put("user_input_json", jsonText(record, "user_input", "{}"));
        turn.put("execution_options_json", jsonText(
                record,
                "execution_options",
                "{}"
        ));
        turn.put("started_at", observedAt);
        turn.put("updated_at", observedAt);
        turn.put("status", "RUNNING");
        db.insertWithOnConflict("turns", null, turn, SQLiteDatabase.CONFLICT_IGNORE);
        recomputeSession(db, sessionKey);
    }

    private static void ingestEvent(SQLiteDatabase db, JSONObject record, String rawLine) {
        String executionId = required(record, "execution_id");
        String eventType = record.optString("event_type", "UNKNOWN");
        long observedAt = record.optLong("observed_at", System.currentTimeMillis());
        String hostSessionId = nullable(record, "host_session_id");
        String sessionKey = sessionForExecution(db, executionId);
        if (sessionKey == null) {
            sessionKey = createPartialPlaceholder(
                    db,
                    executionId,
                    hostSessionId,
                    observedAt
            );
        }
        if (hostSessionId != null && !hostSessionId.isBlank()) {
            sessionKey = reconcileSession(db, executionId, hostSessionId);
        }

        ContentValues event = new ContentValues();
        event.put("execution_id", executionId);
        event.put("sequence", record.optLong("sequence", observedAt));
        event.put("observed_at", observedAt);
        event.put("event_type", eventType);
        event.put("payload_json", jsonText(record, "payload", "{}"));
        event.put("raw_json", rawLine);
        db.insertWithOnConflict("events", null, event, SQLiteDatabase.CONFLICT_IGNORE);

        ContentValues turnUpdate = new ContentValues();
        turnUpdate.put("updated_at", observedAt);
        String terminalStatus = terminalStatus(eventType);
        if (terminalStatus != null) {
            turnUpdate.put("status", terminalStatus);
        }
        db.update("turns", turnUpdate, "execution_id = ?", new String[]{executionId});
        if (sessionKey != null) {
            recomputeSession(db, sessionKey);
        }
    }

    private static void ingestGap(SQLiteDatabase db, JSONObject record, String rawLine) {
        String executionId = required(record, "execution_id");
        long observedAt = record.optLong("observed_at", System.currentTimeMillis());
        JSONObject payload = new JSONObject();
        try {
            payload.put("dropped_records", Math.max(1, record.optLong("dropped_records", 1)));
        } catch (Throwable ignored) {
            // The empty object still carries a visible GAP event.
        }
        ContentValues event = new ContentValues();
        event.put("execution_id", executionId);
        event.put("sequence", record.optLong("sequence", observedAt));
        event.put("observed_at", observedAt);
        event.put("event_type", "GAP");
        event.put("payload_json", payload.toString());
        event.put("raw_json", rawLine);
        db.insertWithOnConflict("events", null, event, SQLiteDatabase.CONFLICT_IGNORE);
        String sessionKey = sessionForExecution(db, executionId);
        if (sessionKey == null) {
            sessionKey = createPartialPlaceholder(db, executionId, null, observedAt);
        }
        if (sessionKey != null) {
            ContentValues turnUpdate = new ContentValues();
            turnUpdate.put("updated_at", observedAt);
            db.update("turns", turnUpdate, "execution_id = ?", new String[]{executionId});
            ContentValues partial = new ContentValues();
            partial.put("partial", 1);
            partial.put("updated_at", observedAt);
            db.update("sessions", partial, "session_key = ?", new String[]{sessionKey});
            recomputeSession(db, sessionKey);
        }
    }

    private static String createPartialPlaceholder(
            SQLiteDatabase db,
            String executionId,
            String hostSessionId,
            long observedAt
    ) {
        String sessionKey = hostSessionId == null || hostSessionId.isBlank()
                ? "local:" + executionId : "host:" + hostSessionId;
        ContentValues session = new ContentValues();
        session.put("session_key", sessionKey);
        session.put("host_session_id", hostSessionId);
        session.put("started_at", observedAt);
        session.put("updated_at", observedAt);
        session.put("status", "RUNNING");
        session.put("partial", 1);
        db.insertWithOnConflict("sessions", null, session, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues partial = new ContentValues();
        partial.put("partial", 1);
        db.update("sessions", partial, "session_key = ?", new String[]{sessionKey});

        ContentValues turn = new ContentValues();
        turn.put("execution_id", executionId);
        turn.put("session_key", sessionKey);
        turn.put("turn_index", nextTurnIndex(db, sessionKey));
        turn.put("started_at", observedAt);
        turn.put("updated_at", observedAt);
        turn.put("status", "RUNNING");
        db.insertWithOnConflict("turns", null, turn, SQLiteDatabase.CONFLICT_IGNORE);
        recomputeSession(db, sessionKey);
        return sessionKey;
    }

    private static String reconcileSession(
            SQLiteDatabase db,
            String executionId,
            String hostSessionId
    ) {
        String oldKey = sessionForExecution(db, executionId);
        String newKey = "host:" + hostSessionId;
        if (oldKey == null || newKey.equals(oldKey)) {
            if (oldKey != null) {
                ContentValues values = new ContentValues();
                values.put("host_session_id", hostSessionId);
                db.update("sessions", values, "session_key = ?", new String[]{oldKey});
            }
            return oldKey;
        }
        if (!sessionExists(db, newKey)) {
            db.execSQL("INSERT INTO sessions (session_key, host_session_id, chat_id, agent_id,"
                            + " agent_name, preview, started_at, updated_at, status, partial,"
                            + " turn_count, tool_count) "
                            + "SELECT ?, ?, chat_id, agent_id, agent_name, preview, started_at,"
                            + " updated_at, status, partial, turn_count, tool_count "
                            + "FROM sessions WHERE session_key = ?",
                    new Object[]{newKey, hostSessionId, oldKey});
        } else {
            try (Cursor cursor = db.rawQuery(
                    "SELECT partial FROM sessions WHERE session_key = ?",
                    new String[]{oldKey}
            )) {
                if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                    ContentValues partial = new ContentValues();
                    partial.put("partial", 1);
                    db.update("sessions", partial, "session_key = ?", new String[]{newKey});
                }
            }
        }
        db.execSQL("UPDATE turns SET session_key = ? WHERE session_key = ?",
                new Object[]{newKey, oldKey});
        db.delete("sessions", "session_key = ?", new String[]{oldKey});
        reindexTurns(db, newKey);
        recomputeSession(db, newKey);
        return newKey;
    }

    private static void reindexTurns(SQLiteDatabase db, String sessionKey) {
        ArrayList<String> executionIds = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT execution_id FROM turns WHERE session_key = ?"
                        + " ORDER BY started_at, execution_id",
                new String[]{sessionKey}
        )) {
            while (cursor.moveToNext()) {
                executionIds.add(cursor.getString(0));
            }
        }
        int index = 1;
        for (String executionId : executionIds) {
            ContentValues values = new ContentValues();
            values.put("turn_index", index++);
            db.update("turns", values, "execution_id = ?", new String[]{executionId});
        }
    }

    private static int nextTurnIndex(SQLiteDatabase db, String sessionKey) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COALESCE(MAX(turn_index), 0) + 1 FROM turns WHERE session_key = ?",
                new String[]{sessionKey}
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 1;
        }
    }

    private static String sessionForExecution(SQLiteDatabase db, String executionId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT session_key FROM turns WHERE execution_id = ?",
                new String[]{executionId}
        )) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static String sessionForChat(SQLiteDatabase db, String chatId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT session_key FROM sessions WHERE chat_id = ?"
                        + " ORDER BY updated_at DESC LIMIT 1",
                new String[]{chatId}
        )) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static boolean sessionExists(SQLiteDatabase db, String sessionKey) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sessions WHERE session_key = ? LIMIT 1",
                new String[]{sessionKey}
        )) {
            return cursor.moveToFirst();
        }
    }

    private static void recomputeSession(SQLiteDatabase db, String sessionKey) {
        ContentValues values = new ContentValues();
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*), COALESCE(MIN(started_at), 0),"
                        + " COALESCE(MAX(updated_at), 0) FROM turns WHERE session_key = ?",
                new String[]{sessionKey}
        )) {
            if (cursor.moveToFirst()) {
                values.put("turn_count", cursor.getInt(0));
                values.put("started_at", cursor.getLong(1));
                values.put("updated_at", cursor.getLong(2));
            }
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM events e JOIN turns t ON e.execution_id = t.execution_id"
                        + " WHERE t.session_key = ? AND e.event_type = 'TOOL_EXECUTING'",
                new String[]{sessionKey}
        )) {
            if (cursor.moveToFirst()) {
                values.put("tool_count", cursor.getInt(0));
            }
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT status FROM turns WHERE session_key = ?"
                        + " ORDER BY updated_at DESC LIMIT 1",
                new String[]{sessionKey}
        )) {
            if (cursor.moveToFirst()) {
                values.put("status", cursor.getString(0));
            }
        }
        db.update("sessions", values, "session_key = ?", new String[]{sessionKey});
    }

    private static String terminalStatus(String eventType) {
        return switch (eventType) {
            case "COMPLETED" -> "COMPLETED";
            case "ERROR" -> "ERROR";
            default -> null;
        };
    }

    public synchronized List<TraceSessionSummary> listSessions() {
        ArrayList<TraceSessionSummary> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT session_key, host_session_id, agent_id, agent_name, preview,"
                        + " started_at, updated_at, status, partial, turn_count, tool_count"
                        + " FROM sessions ORDER BY updated_at DESC",
                null
        )) {
            while (cursor.moveToNext()) {
                result.add(sessionSummary(cursor));
            }
        }
        return List.copyOf(result);
    }

    public synchronized TraceSessionDetail loadSession(String sessionKey) {
        TraceSessionSummary summary = null;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT session_key, host_session_id, agent_id, agent_name, preview,"
                        + " started_at, updated_at, status, partial, turn_count, tool_count"
                        + " FROM sessions WHERE session_key = ?",
                new String[]{sessionKey}
        )) {
            if (cursor.moveToFirst()) {
                summary = sessionSummary(cursor);
            }
        }
        if (summary == null) {
            return null;
        }
        ArrayList<TraceTurnRecord> turns = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT execution_id, session_key, turn_index, agent_id, agent_name,"
                        + " system_prompt, tool_catalog_json, user_input_json,"
                        + " execution_options_json, started_at, updated_at, status"
                        + " FROM turns WHERE session_key = ? ORDER BY turn_index",
                new String[]{sessionKey}
        )) {
            while (cursor.moveToNext()) {
                turns.add(new TraceTurnRecord(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getString(7),
                        cursor.getString(8),
                        cursor.getLong(9),
                        cursor.getLong(10),
                        cursor.getString(11)
                ));
            }
        }
        ArrayList<TraceEventRecord> events = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT e.id, e.execution_id, e.sequence, e.observed_at, e.event_type,"
                        + " e.payload_json, e.raw_json FROM events e JOIN turns t"
                        + " ON e.execution_id = t.execution_id WHERE t.session_key = ?"
                        + " ORDER BY t.turn_index, e.observed_at, e.id",
                new String[]{sessionKey}
        )) {
            while (cursor.moveToNext()) {
                events.add(new TraceEventRecord(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getString(6)
                ));
            }
        }
        return new TraceSessionDetail(summary, turns, events);
    }

    private static TraceSessionSummary sessionSummary(Cursor cursor) {
        return new TraceSessionSummary(
                cursor.getString(0),
                cursor.isNull(1) ? null : cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
                cursor.getString(4),
                cursor.getLong(5),
                cursor.getLong(6),
                cursor.getString(7),
                cursor.getInt(8) != 0,
                cursor.getInt(9),
                cursor.getInt(10)
        );
    }

    public synchronized void deleteSession(String sessionKey) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM events WHERE execution_id IN"
                    + " (SELECT execution_id FROM turns WHERE session_key = ?)",
                    new Object[]{sessionKey});
            db.delete("turns", "session_key = ?", new String[]{sessionKey});
            db.delete("sessions", "session_key = ?", new String[]{sessionKey});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        notifyChanged();
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("events", null, null);
            db.delete("turns", null, null);
            db.delete("sessions", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        notifyChanged();
    }

    public synchronized void prune(TraceRetentionConfig config) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            pruneLocked(db, config, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        notifyChanged();
    }

    private static void pruneLocked(
            SQLiteDatabase db,
            TraceRetentionConfig config,
            long now
    ) {
        ArrayList<String> deleteKeys = new ArrayList<>();
        if (!config.daysUnlimited()) {
            long cutoff = now - config.days() * 24L * 60L * 60L * 1_000L;
            try (Cursor cursor = db.rawQuery(
                    "SELECT session_key FROM sessions WHERE updated_at < ?",
                    new String[]{Long.toString(cutoff)}
            )) {
                while (cursor.moveToNext()) {
                    deleteKeys.add(cursor.getString(0));
                }
            }
        }
        if (!config.sessionsUnlimited()) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT session_key FROM sessions ORDER BY updated_at DESC"
                            + " LIMIT -1 OFFSET ?",
                    new String[]{Integer.toString(config.sessions())}
            )) {
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    if (!deleteKeys.contains(key)) {
                        deleteKeys.add(key);
                    }
                }
            }
        }
        for (String key : deleteKeys) {
            db.execSQL("DELETE FROM events WHERE execution_id IN"
                    + " (SELECT execution_id FROM turns WHERE session_key = ?)",
                    new Object[]{key});
            db.delete("turns", "session_key = ?", new String[]{key});
            db.delete("sessions", "session_key = ?", new String[]{key});
        }
    }

    private void notifyChanged() {
        context.getContentResolver().notifyChange(AgentTraceContract.CONTENT_URI, null);
    }

    private static String required(JSONObject object, String name) {
        String value = object.optString(name, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static String nullable(JSONObject object, String name) {
        if (!object.has(name) || object.isNull(name)) {
            return null;
        }
        String value = object.optString(name, "");
        return value.isBlank() ? null : value;
    }

    private static String jsonText(JSONObject object, String name, String fallback) {
        if (!object.has(name) || object.isNull(name)) {
            return fallback;
        }
        Object value = object.opt(name);
        return value == null ? fallback : value.toString();
    }

    private static String preview(String value, int maximum) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maximum ? compact : compact.substring(0, maximum) + "…";
    }
}
