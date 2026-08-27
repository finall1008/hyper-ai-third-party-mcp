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
        assertEquals(14, config.patches().size());
        assertTrue(config.patches().stream().allMatch(PromptPatch::enabled));
    }

    @Test
    public void roundTripsOrderedPatches() {
        PromptPatchConfig original = new PromptPatchConfig(false, List.of(
                new PromptPatch("one", true, "*", "prompt.md", "old", "new"),
                new PromptPatch("two", false, PromptTargetType.TOOL_PROMPT,
                        "cli", "description", "remove", "")
        ));

        PromptPatchConfig parsed = PromptPatchCodec.parse(PromptPatchCodec.encode(original));

        assertEquals(original, parsed);
    }

    @Test
    public void rejectsUnknownVersionAndUnsafeFileName() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptPatchCodec.parse("{\"version\":4}"));
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

        assertEquals(14, enabled.patches().size());
        assertTrue(disabled.patches().isEmpty());
        assertEquals(BridgeContract.PROMPT_PATCH_SCHEMA_VERSION,
                new org.json.JSONObject(PromptPatchCodec.encode(enabled)).getInt("version"));
    }

    @Test
    public void migratesVersionTwoWithoutRestoringRemovedOldDefaults() {
        String versionTwo = "{\"version\":2,\"enabled\":false,\"patches\":[{"
                + "\"id\":\"custom\",\"enabled\":false,\"agent_id\":\"osbot.main\","
                + "\"file_name\":\"prompt.md\",\"find\":\"old\","
                + "\"replacement\":\"new\"}]}";

        PromptPatchConfig migrated = PromptPatchCodec.parse(versionTwo);

        assertEquals(9, migrated.patches().size());
        assertEquals("custom", migrated.patches().get(0).id());
        assertEquals(PromptTargetType.AGENT_PROMPT,
                migrated.patches().get(0).targetType());
        assertTrue(migrated.patches().stream().skip(1)
                .allMatch(patch -> patch.id().startsWith("default-v3-")));
    }

    @Test
    public void validatesTypedSelectors() {
        PromptPatchCodec.validatePatch(new PromptPatch(
                "memory", true, PromptTargetType.MEMORY_PROMPT,
                "memorygate/prompt_query_gate.txt", "systemPrompt", "old", "new"));
        assertThrows(IllegalArgumentException.class, () -> PromptPatchCodec.validatePatch(
                new PromptPatch("tool", true, PromptTargetType.TOOL_PROMPT,
                        "*", "description", "old", "new")));
        assertThrows(IllegalArgumentException.class, () -> PromptPatchCodec.validatePatch(
                new PromptPatch("memory", true, PromptTargetType.MEMORY_PROMPT,
                        "memorygate/../secret.txt", "systemPrompt", "old", "new")));
        assertThrows(IllegalArgumentException.class, () -> PromptPatchCodec.validatePatch(
                new PromptPatch("memory", true, PromptTargetType.MEMORY_PROMPT,
                        "memorygate/prompt_query_gate", "systemPrompt", "old", "new")));
        assertThrows(IllegalArgumentException.class, () -> PromptPatchCodec.validatePatch(
                new PromptPatch("memory", true, PromptTargetType.MEMORY_PROMPT,
                        "memorygate/prompt_query_gate.txt", "other", "old", "new")));
    }
}
