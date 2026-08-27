package io.github.finall1008.xiaoaimcp.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InstalledPromptPreviewLoaderTest {
    @Test
    public void exactAndWildcardRulesProduceDistinctInstalledDocuments() {
        FakeAssets assets = new FakeAssets()
                .put("agent.one", "rules.md", "before one")
                .put("agent.two", "rules.md", "before two")
                .put("agent.two", "other.md", "other");
        PromptPatchConfig config = config(
                patch("exact", "agent.one", "rules.md", "before", "after"),
                patch("wildcard", "*", "rules.md", "one", "ONE")
        );

        List<PromptPreviewDocument> documents =
                new InstalledPromptPreviewLoader(assets).load(config);

        assertEquals(2, documents.size());
        assertEquals("agent.one", documents.get(0).agentId());
        assertEquals("after ONE", documents.get(0).patchedText());
        assertEquals(List.of("exact", "wildcard"), documents.get(0).appliedPatchIds());
        assertEquals("agent.two", documents.get(1).agentId());
        assertEquals("before two", documents.get(1).patchedText());
        assertEquals(List.of("wildcard"), documents.get(1).skippedPatches().stream()
                .map(PromptPatchResult.SkippedPatch::id).toList());
    }

    @Test
    public void missingExactTargetRemainsVisibleWithError() {
        PromptPatchConfig config = config(
                patch("missing", "agent.none", "rules.md", "a", "b"));

        List<PromptPreviewDocument> documents =
                new InstalledPromptPreviewLoader(new FakeAssets()).load(config);

        assertEquals(1, documents.size());
        assertFalse(documents.get(0).available());
        assertTrue(documents.get(0).error().contains("不存在"));
    }

    @Test
    public void wildcardWithoutMatchingAssetsReportsUnavailableTarget() {
        FakeAssets assets = new FakeAssets().put("agent.one", "other.md", "text");
        PromptPatchConfig config = config(
                patch("wildcard", "*", "rules.md", "a", "b"));

        PromptPreviewDocument document =
                new InstalledPromptPreviewLoader(assets).load(config).get(0);

        assertEquals("*", document.agentId());
        assertFalse(document.available());
        assertTrue(document.error().contains("没有 Agent"));
    }

    @Test
    public void disabledRulesDoNotCreatePreviewTargets() {
        PromptPatch disabled = new PromptPatch(
                "disabled", false, "agent.one", "rules.md", "a", "b");

        assertTrue(new InstalledPromptPreviewLoader(new FakeAssets())
                .load(new PromptPatchConfig(true, List.of(disabled))).isEmpty());
    }

    @Test
    public void globalSwitchShowsOriginalDocumentWithoutApplyingRules() {
        FakeAssets assets = new FakeAssets().put("agent.one", "rules.md", "before");
        PromptPatch patch = patch("patch", "agent.one", "rules.md", "before", "after");

        PromptPreviewDocument document = new InstalledPromptPreviewLoader(assets)
                .load(new PromptPatchConfig(false, List.of(patch))).get(0);

        assertEquals("before", document.patchedText());
        assertTrue(document.appliedPatchIds().isEmpty());
    }

    @Test
    public void previewsToolAndMemoryPromptSections() {
        FakeAssets assets = new FakeAssets()
                .put(PromptTargetType.TOOL_PROMPT, "cli", "description", "tool old")
                .put(PromptTargetType.MEMORY_PROMPT,
                        "memorygate/prompt_query_gate.txt", "systemPrompt", "memory old");
        PromptPatch tool = new PromptPatch(
                "tool", true, PromptTargetType.TOOL_PROMPT,
                "cli", "description", "old", "new");
        PromptPatch memory = new PromptPatch(
                "memory", true, PromptTargetType.MEMORY_PROMPT,
                "memorygate/prompt_query_gate.txt", "systemPrompt", "old", "new");

        List<PromptPreviewDocument> documents = new InstalledPromptPreviewLoader(assets)
                .load(new PromptPatchConfig(true, List.of(tool, memory)));

        assertEquals(2, documents.size());
        assertEquals(PromptTargetType.TOOL_PROMPT, documents.get(0).targetType());
        assertEquals("tool new", documents.get(0).patchedText());
        assertEquals(PromptTargetType.MEMORY_PROMPT, documents.get(1).targetType());
        assertEquals("memory new", documents.get(1).patchedText());
    }

    private static PromptPatchConfig config(PromptPatch... patches) {
        return new PromptPatchConfig(true, List.of(patches));
    }

    private static PromptPatch patch(
            String id,
            String agentId,
            String fileName,
            String find,
            String replacement
    ) {
        return new PromptPatch(id, true, agentId, fileName, find, replacement);
    }

    private static final class FakeAssets
            implements InstalledPromptPreviewLoader.PromptAssetSource {
        private final Map<String, String> prompts = new LinkedHashMap<>();

        FakeAssets put(String agentId, String fileName, String text) {
            return put(PromptTargetType.AGENT_PROMPT, agentId, fileName, text);
        }

        FakeAssets put(
                PromptTargetType targetType,
                String targetId,
                String targetPart,
                String text
        ) {
            prompts.put(targetType.name() + '\0' + targetId + '\0' + targetPart, text);
            return this;
        }

        @Override
        public List<String> listAgentIds() {
            return prompts.keySet().stream()
                    .filter(key -> key.startsWith(PromptTargetType.AGENT_PROMPT.name() + '\0'))
                    .map(key -> key.split("\\u0000", -1)[1])
                    .distinct()
                    .toList();
        }

        @Override
        public String readPrompt(
                PromptTargetType targetType,
                String targetId,
                String targetPart
        ) throws IOException {
            return prompts.get(targetType.name() + '\0' + targetId + '\0' + targetPart);
        }
    }
}
