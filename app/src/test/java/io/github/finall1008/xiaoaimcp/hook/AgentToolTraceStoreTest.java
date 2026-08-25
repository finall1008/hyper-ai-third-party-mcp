package io.github.finall1008.xiaoaimcp.hook;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
