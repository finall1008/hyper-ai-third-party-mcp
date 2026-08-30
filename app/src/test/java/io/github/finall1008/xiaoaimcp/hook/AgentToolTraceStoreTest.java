package io.github.finall1008.xiaoaimcp.hook;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

public final class AgentToolTraceStoreTest {
    @Test
    public void terminalPayloadRetainsStartArguments() throws Exception {
        AgentToolTraceStore store = new AgentToolTraceStore();

        store.merge("dialog-1", "start", "{\"tool\":\"maps\",\"tool_call_id\":\"c1\","
                + "\"arguments\":\"{\\\"query\\\":\\\"Shanghai\\\"}\"}");
        String terminal = store.merge("dialog-1", "done", "{\"tool\":\"maps\","
                + "\"tool_call_id\":\"c1\",\"result\":\"ok\",\"duration_ms\":12}");

        JSONObject value = new JSONObject(terminal);
        assertEquals("{\"query\":\"Shanghai\"}", value.getString("arguments"));
        assertEquals("ok", value.getString("result"));
        assertEquals(12, value.getInt("duration_ms"));
    }

    @Test
    public void callsWithDifferentIdsDoNotOverwriteEachOther() throws Exception {
        AgentToolTraceStore store = new AgentToolTraceStore();

        store.merge("dialog-1", "start", "{\"tool\":\"same\",\"tool_call_id\":\"a\","
                + "\"arguments\":\"A\"}");
        store.merge("dialog-1", "start", "{\"tool\":\"same\",\"tool_call_id\":\"b\","
                + "\"arguments\":\"B\"}");

        assertEquals("A", new JSONObject(store.merge(
                "dialog-1", "done", "{\"tool_call_id\":\"a\",\"result\":1}"
        )).getString("arguments"));
        assertEquals("B", new JSONObject(store.merge(
                "dialog-1", "done", "{\"tool_call_id\":\"b\",\"result\":2}"
        )).getString("arguments"));
    }

    @Test
    public void malformedPayloadIsPassedThroughUnchanged() {
        AgentToolTraceStore store = new AgentToolTraceStore();
        String payload = "not-json";
        assertTrue(store.merge("dialog-1", "done", payload).equals(payload));
    }

    @Test
    public void evictsOldestEntriesAtCapacity() throws Exception {
        AtomicLong clock = new AtomicLong();
        AgentToolTraceStore store = new AgentToolTraceStore(2, 1_000L, clock::get);

        store.merge("dialog", "start", "{\"tool_call_id\":\"a\",\"arguments\":\"A\"}");
        store.merge("dialog", "start", "{\"tool_call_id\":\"b\",\"arguments\":\"B\"}");
        store.merge("dialog", "start", "{\"tool_call_id\":\"c\",\"arguments\":\"C\"}");

        assertEquals(2, store.size());
        assertFalse(new JSONObject(store.merge(
                "dialog", "done", "{\"tool_call_id\":\"a\"}"
        )).has("arguments"));
    }

    @Test
    public void expiresMissingTerminalEventsAndClearsDialogs() {
        AtomicLong clock = new AtomicLong();
        AgentToolTraceStore store = new AgentToolTraceStore(4, 100L, clock::get);
        store.merge("one", "start", "{\"tool_call_id\":\"a\",\"arguments\":\"A\"}");
        store.merge("two", "start", "{\"tool_call_id\":\"b\",\"arguments\":\"B\"}");
        store.clearDialog("one");
        assertEquals(1, store.size());

        clock.set(100L);
        assertEquals(0, store.size());
    }
}
