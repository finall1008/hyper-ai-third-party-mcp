package io.github.finall1008.xiaoaimcp.prompt;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PromptPatchEngineTest {
    @Test
    public void appliesWildcardReplacementAndDeletionInStoredOrder() {
        PromptPatchConfig config = custom(
                patch("replace", "*", "rules.md", "alpha", "beta"),
                patch("delete", "agent.two", "rules.md", " beta", "")
        );

        PromptPatchResult result = PromptPatchEngine.apply(
                "alpha beta", "agent.two", "rules.md", config);

        assertEquals("beta", result.text());
        assertEquals(List.of("replace", "delete"), result.appliedPatchIds());
    }

    @Test
    public void isolatesAgentAndFileSelectors() {
        PromptPatchConfig config = custom(
                patch("wrong-agent", "agent.one", "rules.md", "old", "new"),
                patch("wrong-file", "*", "other.md", "old", "new")
        );

        PromptPatchResult result = PromptPatchEngine.apply(
                "old", "agent.two", "rules.md", config);

        assertEquals("old", result.text());
        assertTrue(result.appliedPatchIds().isEmpty());
        assertTrue(result.skippedPatches().isEmpty());
    }

    @Test
    public void isolatesPromptTargetTypesAndSections() {
        PromptPatch tool = new PromptPatch(
                "tool", true, PromptTargetType.TOOL_PROMPT,
                "cli", "description", "old", "tool-new");
        PromptPatch memory = new PromptPatch(
                "memory", true, PromptTargetType.MEMORY_PROMPT,
                "memorygate/prompt_query_gate.txt", "systemPrompt", "old", "memory-new");
        PromptPatchConfig config = new PromptPatchConfig(true, List.of(tool, memory));

        assertEquals("tool-new", PromptPatchEngine.apply(
                "old", PromptTargetType.TOOL_PROMPT, "cli", "description", config).text());
        assertEquals("memory-new", PromptPatchEngine.apply(
                "old", PromptTargetType.MEMORY_PROMPT,
                "memorygate/prompt_query_gate.txt", "systemPrompt", config).text());
        assertEquals("old", PromptPatchEngine.apply(
                "old", PromptTargetType.AGENT_PROMPT,
                "osbot.main", "description", config).text());
    }

    @Test
    public void skipsZeroAndMultipleMatchesButContinuesIndependentPatches() {
        PromptPatchConfig config = custom(
                patch("missing", "*", "rules.md", "absent", "x"),
                patch("multiple", "*", "rules.md", "same", "x"),
                patch("valid", "*", "rules.md", "tail", "done")
        );

        PromptPatchResult result = PromptPatchEngine.apply(
                "same same tail", "agent", "rules.md", config);

        assertEquals("same same done", result.text());
        assertEquals(2, result.skippedPatches().size());
        assertEquals(0, result.skippedPatches().get(0).occurrences());
        assertEquals(2, result.skippedPatches().get(1).occurrences());
        assertEquals(List.of("valid"), result.appliedPatchIds());
    }

    @Test
    public void disabledConfigurationAndNullInputRemainUnchanged() {
        PromptPatchConfig disabled = new PromptPatchConfig(false,
                List.of(patch("custom", "*", "rules.md", "old", "new")));

        assertEquals("old", PromptPatchEngine.apply(
                "old", "osbot.main", "rules.md", disabled).text());
        assertNull(PromptPatchEngine.apply(
                null, "osbot.main", "rules.md", disabled).text());
    }

    @Test
    public void repositoryDefaultRulesFixVerifiedReferencePrompt() {
        String referenceExcerpt = String.join("\n",
                "**原则：一轮内能并发的工具，绝不拆到多轮。**",
                "- **DON'T**：反复\"看一步走一步\"。如果下一步能预判（几乎总能预判），就把可预判的工具一起发。",
                "- **依赖时才串行**：只有存在**显式输入输出依赖**（如 `app list --query` 先拿包名 → `securitycenter memory-clean --apps <pkg>` 才能填参数）时才拆两轮。",
                "- **严格按 schema 类型传参**：schema 里声明 `type: integer` / `type: number` 的字段必须传 number（`1`），不能传 `\"1\"`；`type: boolean` 必须传 `true` / `false`，不能传 `\"true\"`。类型不符会被工具或服务端拒收，触发一次多余的\"错误重试轮次\"。",
                "- **错误是确定性的**：工具/命令不存在 = 该能力确定不可用（不是你调错了名字）；同一调用失败 2 次 = 真的不可用（不是重试就能好）。遇到这些，如实告知用户即可。",
                "- 流程：`help \"<provider>\"` 获取工具列表（含参数）→ 直接调用，无需再 help 单个工具。多 provider 用逗号合并：`help \"amap,hellobike\"`。`<provider>` 必须用英文 ID。",
                "UNCHANGED SENTINEL"
        );

        PromptPatchResult result = PromptPatchEngine.apply(
                referenceExcerpt,
                "osbot.main",
                "tool_selection_rules.md",
                PromptPatchConfig.defaults()
        );

        assertEquals(result.skippedPatches().toString(),
                6, result.appliedPatchIds().size());
        assertTrue(result.skippedPatches().stream()
                .allMatch(skipped -> skipped.id().startsWith("default-v3-")));
        assertTrue(result.text().contains("help \"<provider>__<tool>\""));
        assertTrue(result.text().contains("schema 声明为 string，也必须加引号传字符串"));
        assertTrue(result.text().contains("错误必须分类"));
        assertTrue(result.text().contains("UNCHANGED SENTINEL"));
        assertFalse(result.text().contains("无需再 help 单个工具"));
    }

    private static PromptPatchConfig custom(PromptPatch... patches) {
        return new PromptPatchConfig(true, List.of(patches));
    }

    private static PromptPatch patch(
            String id, String agent, String file, String find, String replacement) {
        return new PromptPatch(id, true, agent, file, find, replacement);
    }
}
