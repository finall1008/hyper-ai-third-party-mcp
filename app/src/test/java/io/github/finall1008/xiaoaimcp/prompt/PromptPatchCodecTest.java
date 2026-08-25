package io.github.finall1008.xiaoaimcp.prompt;

import org.junit.Test;

import java.util.List;

import io.github.finall1008.xiaoaimcp.BridgeContract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PromptPatchCodecTest {
    @Test
    public void missingConfigurationUsesEnabledReliabilityDefaults() {
        PromptPatchConfig config = PromptPatchCodec.parse(null);

        assertTrue(config.enabled());
        assertEquals(6, config.patches().size());
        assertTrue(config.patches().stream().allMatch(PromptPatch::enabled));
    }

    @Test
    public void roundTripsOrderedPatches() {
        PromptPatchConfig original = new PromptPatchConfig(false, List.of(
                new PromptPatch("one", true, "*", "prompt.md", "old", "new"),
                new PromptPatch("two", false, "agent", "rules.md", "remove", "")
        ));

        PromptPatchConfig parsed = PromptPatchCodec.parse(PromptPatchCodec.encode(original));

        assertEquals(original, parsed);
    }

    @Test
    public void rejectsUnknownVersionAndUnsafeFileName() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptPatchCodec.parse("{\"version\":3}"));
        assertThrows(IllegalArgumentException.class, () -> PromptPatchCodec.validatePatch(
                new PromptPatch("id", true, "*", "../prompt.md", "old", "new")));
    }

    @Test
    public void rejectsDuplicateIds() {
        PromptPatch duplicate = new PromptPatch("same", true, "*", "prompt.md", "a", "b");
        assertThrows(IllegalArgumentException.class, () -> PromptPatchCodec.validate(
                new PromptPatchConfig(true, List.of(duplicate, duplicate))));
    }

    @Test
    public void migratesVersionOneBuiltInSwitchIntoOrdinaryRules() throws Exception {
        PromptPatchConfig enabled = PromptPatchCodec.parse(
                "{\"version\":1,\"enabled\":true,"
                        + "\"built_in_reliability_enabled\":true,\"patches\":[]}");
        PromptPatchConfig disabled = PromptPatchCodec.parse(
                "{\"version\":1,\"enabled\":true,"
                        + "\"built_in_reliability_enabled\":false,\"patches\":[]}");

        assertEquals(6, enabled.patches().size());
        assertTrue(disabled.patches().isEmpty());
        assertEquals(BridgeContract.PROMPT_PATCH_SCHEMA_VERSION,
                new org.json.JSONObject(PromptPatchCodec.encode(enabled)).getInt("version"));
    }
}
