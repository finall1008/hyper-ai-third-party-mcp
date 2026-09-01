package io.github.finall1008.xiaoaimcp.trace;

import java.util.List;

public record TraceSessionDetail(
        TraceSessionSummary summary,
        List<TraceTurnRecord> turns,
        List<TraceEventRecord> events
) {
    public TraceSessionDetail {
        turns = List.copyOf(turns);
        events = List.copyOf(events);
    }
}
