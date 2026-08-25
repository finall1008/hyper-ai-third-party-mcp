package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AgentTraceBundlePatcherTest {
    @Test
    public void semanticToolCallModuleIsPatchedAndPatchIsIdempotent() {
        String source = "var boot=1;__d(function(){"
                + "var y='ToolCallItem:';"
                + "var M,_,j,w,x,b=e.cardData,S=e.RNInlineMarkdownView;"
                + "var Q=renderToolMessage,normalizeStatusWithCancel=parseArguments;"
                + "return K?(0,p.jsxs)(n.View,{numberOfLines:1}):(console.warn(y,'x'),null)"
                + "},2994755);__reactNativeBundleEndSuccess__;";

        String patched = AgentTraceBundlePatcher.patchSource(source);
        assertNotNull(patched);
        assertTrue(patched.contains("xiaoai-agent-trace-v10"));
        assertTrue(patched.contains("__xiaoaiTraceDetails("));
        assertTrue(patched.contains("n.Text,{selectable:!0"));
        assertTrue(patched.contains("children:__xiaoaiTraceDetails("));
        assertTrue(patched.contains("n.Pressable"));
        assertTrue(patched.contains("__xiaoaiTraceState=(0,t.useState)(!1)"));
        assertTrue(patched.contains("\"查看详情 ▼\""));
        assertTrue(patched.contains("\"收起详情 ▲\""));
        assertFalse(patched.equals(source));
        assertFalse(AgentTraceBundlePatcher.patchSource(patched) != null);
    }

    @Test
    public void moduleNumberDoesNotControlMatching() {
        String source = "__d(function(){var y='ToolCallItem:';"
                + "var M,_,j,w,x,b=e.cardData,S=e.RNInlineMarkdownView;"
                + "normalizeStatusWithCancel parseArguments renderToolMessage numberOfLines:1;"
                + "return K?(0,p.jsxs)(n.View,{x:1}):(console.warn(y,'x'),null)"
                + "},777);__reactNativeBundleEndSuccess__;";
        assertNotNull(AgentTraceBundlePatcher.patchSource(source));
    }

    @Test
    public void unsupportedBundleIsLeftForTheHost() {
        assertFalse(AgentTraceBundlePatcher.patchSource(
                "__reactNativeBundleEndSuccess__;__d(function(){},1);"
        ) != null);
    }

    @Test
    public void ambiguousToolModulesFallBackToTheHost() {
        String module = "__d(function(){var y='ToolCallItem:';"
                + "var M,_,j,w,x,b=e.cardData,S=e.RNInlineMarkdownView;"
                + "normalizeStatusWithCancel parseArguments renderToolMessage numberOfLines:1;"
                + "return K?(0,p.jsxs)(n.View,{x:1}):(console.warn(y,'x'),null)},1);";
        assertFalse(AgentTraceBundlePatcher.patchSource(
                module + module + "__reactNativeBundleEndSuccess__;"
        ) != null);
    }

    @Test
    public void reasoningMarkdownIsMadeVisibleWhenSemanticRendererMatches() {
        String tool = "__d(function(){var y='ToolCallItem:';"
                + "var M,_,j,w,x,b=e.cardData,S=e.RNInlineMarkdownView;"
                + "normalizeStatusWithCancel parseArguments renderToolMessage numberOfLines:1;"
                + "return K?(0,p.jsxs)(n.View,{x:1}):(console.warn(y,'x'),null)},1);";
        String markdown = "__d(function(){var r=e.StyleSheet.create({wrap:{flex:1}});"
                + "var s=i.RNInlineMarkdownView;getRendererProps;"
                + "return!0===(null==o?void 0:o.showCancel)||S?"
                + "(0,t.jsx)(e.View,{style:r.wrap}):null},2);";
        String hidden = "__d(function(){var c={hiddenMarkdown:{width:0,height:0,"
                + "opacity:0,overflow:'hidden'}};groupSections;},3);";
        String patched = AgentTraceBundlePatcher.patchSource(
                tool + markdown + hidden + "__reactNativeBundleEndSuccess__;"
        );
        assertNotNull(patched);
        assertTrue(patched.contains("hiddenMarkdown:{width:'100%',opacity:1,overflow:'visible'}"));
        assertTrue(patched.contains("markdownText:S,textStyle:h.textStyle||{},numberOfLines:999999"));
    }
}
