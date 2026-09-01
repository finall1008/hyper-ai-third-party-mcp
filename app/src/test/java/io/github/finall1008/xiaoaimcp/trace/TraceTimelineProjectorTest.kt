package io.github.finall1008.xiaoaimcp.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceTimelineProjectorTest {
    @Test
    fun foldsStreamsAndToolLifecycleIntoMobileTimelineCards() {
        val summary = TraceSessionSummary(
            "host:s1", "s1", "agent", "Main Agent", "hello",
            100L, 200L, "COMPLETED", false, 1, 1,
        )
        val turn = TraceTurnRecord(
            "exec", "host:s1", 1, "agent", "Main Agent", "system prompt",
            "[{\"name\":\"maps\"}]",
            "{\"text\":\"hello\",\"source\":\"USER\"}",
            "{\"taskRole\":\"FOREGROUND\"}",
            100L, 200L, "COMPLETED",
        )
        val events = listOf(
            event(1, 110, "STREAM_DELTA", "{\"text\":\"rea\",\"streamId\":\"r1\"}"),
            event(2, 120, "REASONING_COMPLETED", "{\"fullText\":\"reasoning\",\"streamId\":\"r1\"}"),
            event(3, 130, "TOOL_EXECUTING", "{\"toolName\":\"maps\",\"toolCallId\":\"c1\",\"arguments\":\"{q:x}\"}"),
            event(4, 150, "TOOL_COMPLETED", "{\"toolName\":\"maps\",\"toolCallId\":\"c1\",\"result\":\"ok\",\"durationMs\":20}"),
            event(5, 160, "TEXT_COMPLETED", "{\"fullText\":\"answer\",\"streamId\":\"t1\",\"fileAttachments\":[]}"),
        )

        val cards = TraceTimelineProjector.project(
            TraceSessionDetail(summary, listOf(turn), events),
        )

        assertEquals(5, cards.size)
        assertEquals(
            listOf(
                TraceCardKind.SYSTEM,
                TraceCardKind.USER,
                TraceCardKind.ASSISTANT,
                TraceCardKind.TOOL,
                TraceCardKind.ASSISTANT,
            ),
            cards.map { it.kind },
        )
        assertTrue(cards.first { it.kind == TraceCardKind.TOOL }.detail.contains("输入"))
        assertTrue(cards.first { it.title == "Reasoning" }.detail.contains("reasoning"))
        assertTrue(cards.first { it.title == "Assistant Output" }.detail.contains("answer"))
    }

    private fun event(id: Long, time: Long, type: String, payload: String): TraceEventRecord {
        return TraceEventRecord(
            id,
            "exec",
            id,
            time,
            type,
            payload,
            "{\"event_type\":\"$type\",\"payload\":$payload}",
        )
    }
}
