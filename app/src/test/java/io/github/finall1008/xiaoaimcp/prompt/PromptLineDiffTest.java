package io.github.finall1008.xiaoaimcp.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public final class PromptLineDiffTest {
    @Test
    public void replacementKeepsCompleteUnchangedContext() {
        List<PromptLineDiff.Line> lines = PromptLineDiff.calculate(
                "first\nold rule\nlast",
                "first\nnew rule\nlast"
        );

        assertEquals(4, lines.size());
        assertLine(lines.get(0), PromptLineDiff.Type.UNCHANGED, "first", 1, 1);
        assertLine(lines.get(1), PromptLineDiff.Type.REMOVED, "old rule", 2, null);
        assertLine(lines.get(2), PromptLineDiff.Type.ADDED, "new rule", null, 2);
        assertLine(lines.get(3), PromptLineDiff.Type.UNCHANGED, "last", 3, 3);
    }

    @Test
    public void insertionAndDeletionHaveCorrectLineNumbers() {
        List<PromptLineDiff.Line> lines = PromptLineDiff.calculate(
                "one\ntwo\nremoved\nfour",
                "inserted\none\ntwo\nfour"
        );

        assertLine(lines.get(0), PromptLineDiff.Type.ADDED, "inserted", null, 1);
        assertLine(lines.get(1), PromptLineDiff.Type.UNCHANGED, "one", 1, 2);
        assertLine(lines.get(2), PromptLineDiff.Type.UNCHANGED, "two", 2, 3);
        assertLine(lines.get(3), PromptLineDiff.Type.REMOVED, "removed", 3, null);
        assertLine(lines.get(4), PromptLineDiff.Type.UNCHANGED, "four", 4, 4);
    }

    @Test
    public void identicalPromptReturnsEveryLineAsUnchanged() {
        List<PromptLineDiff.Line> lines = PromptLineDiff.calculate("a\n\nb", "a\n\nb");

        assertEquals(3, lines.size());
        for (PromptLineDiff.Line line : lines) {
            assertEquals(PromptLineDiff.Type.UNCHANGED, line.type());
        }
    }

    private static void assertLine(
            PromptLineDiff.Line line,
            PromptLineDiff.Type type,
            String text,
            Integer oldLine,
            Integer newLine
    ) {
        assertEquals(type, line.type());
        assertEquals(text, line.text());
        if (oldLine == null) {
            assertNull(line.oldLine());
        } else {
            assertEquals(oldLine, line.oldLine());
        }
        if (newLine == null) {
            assertNull(line.newLine());
        } else {
            assertEquals(newLine, line.newLine());
        }
    }
}
