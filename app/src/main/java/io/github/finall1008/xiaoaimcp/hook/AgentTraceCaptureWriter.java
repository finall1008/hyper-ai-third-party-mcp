package io.github.finall1008.xiaoaimcp.hook;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import io.github.finall1008.xiaoaimcp.trace.AgentTraceContract;

final class AgentTraceCaptureWriter {
    private static final String TAG = "XiaoAiMcpBridge";
    private static final int MAX_QUEUED_RECORDS = 2_048;
    private static final int MAX_BATCH_RECORDS = 64;

    private final Context context;
    private final BlockingQueue<Envelope> queue =
            new ArrayBlockingQueue<>(MAX_QUEUED_RECORDS);
    private final Map<String, Long> droppedByExecution = new HashMap<>();
    private final AtomicBoolean successLogged = new AtomicBoolean(false);
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    AgentTraceCaptureWriter(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext != null ? applicationContext : context;
        Thread worker = new Thread(this::run, "AgentTraceCaptureWriter");
        worker.setDaemon(true);
        worker.start();
    }

    void enqueue(String executionId, JSONObject record) {
        if (executionId == null || executionId.isBlank() || record == null) {
            return;
        }
        synchronized (droppedByExecution) {
            long dropped = droppedByExecution.getOrDefault(executionId, 0L);
            if (dropped > 0L) {
                JSONObject gap = gapRecord(executionId, dropped);
                if (!queue.offer(new Envelope(executionId, gap.toString()))) {
                    droppedByExecution.put(executionId, dropped + 1L);
                    return;
                }
                droppedByExecution.remove(executionId);
            }
            if (!queue.offer(new Envelope(executionId, record.toString()))) {
                droppedByExecution.merge(executionId, 1L, Long::sum);
            }
        }
    }

    private void run() {
        ArrayList<Envelope> batch = new ArrayList<>(MAX_BATCH_RECORDS);
        while (true) {
            try {
                Envelope first = queue.take();
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH_RECORDS - 1);
                writeBatch(batch);
                if (successLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "Agent Trace ingest pipe accepted its first batch");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                if (failureLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "Agent Trace ingest unavailable: "
                            + error.getClass().getName());
                }
                markDropped(batch);
            } finally {
                batch.clear();
            }
        }
    }

    private void writeBatch(List<Envelope> batch) throws IOException {
        OutputStream output = context.getContentResolver().openOutputStream(
                AgentTraceContract.INGEST_URI,
                "w"
        );
        if (output == null) {
            throw new IOException("Agent Trace provider returned no stream");
        }
        try (OutputStream raw = output;
             GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            for (Envelope envelope : batch) {
                gzip.write(envelope.json().getBytes(StandardCharsets.UTF_8));
                gzip.write('\n');
            }
        }
    }

    private void markDropped(List<Envelope> batch) {
        synchronized (droppedByExecution) {
            for (Envelope envelope : batch) {
                droppedByExecution.merge(envelope.executionId(), 1L, Long::sum);
            }
        }
    }

    private static JSONObject gapRecord(String executionId, long dropped) {
        JSONObject record = new JSONObject();
        AgentTraceValueEncoder.put(
                record,
                "schema_version",
                AgentTraceContract.SCHEMA_VERSION
        );
        AgentTraceValueEncoder.put(record, "record_type", AgentTraceContract.RECORD_GAP);
        AgentTraceValueEncoder.put(record, "execution_id", executionId);
        AgentTraceValueEncoder.put(record, "sequence", System.nanoTime());
        AgentTraceValueEncoder.put(record, "observed_at", System.currentTimeMillis());
        AgentTraceValueEncoder.put(record, "dropped_records", dropped);
        return record;
    }

    private record Envelope(String executionId, String json) {
    }
}
