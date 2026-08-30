package io.github.finall1008.xiaoaimcp.hook;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AgentTraceBundlePatcherTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    @Test
    public void cachePruningKeepsCurrentAndNewestKnownBundles() throws Exception {
        File root = temporaryFolder.newFolder("cache");
        File oldest = cacheDirectory(root, 'a', 1L);
        File middle = cacheDirectory(root, 'b', 2L);
        File current = cacheDirectory(root, 'c', 3L);
        File newest = cacheDirectory(root, 'd', 4L);
        File unrelated = new File(root, "do-not-delete");
        assertTrue(unrelated.mkdir());

        AgentTraceBundlePatcher.pruneCache(root, current, 3);

        assertFalse(oldest.exists());
        assertTrue(middle.exists());
        assertTrue(current.exists());
        assertTrue(newest.exists());
        assertTrue(unrelated.exists());
    }

    private static File cacheDirectory(File root, char name, long modified) throws Exception {
        File directory = new File(root, String.valueOf(name).repeat(64));
        assertTrue(directory.mkdir());
        File bundle = new File(directory, "stream.bundle");
        assertTrue(bundle.createNewFile());
        assertTrue(directory.setLastModified(modified));
        return directory;
    }
}
