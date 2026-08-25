package io.github.finall1008.xiaoaimcp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptPatchUiValidationTest {
    @Test
    fun trimsSelectorsButPreservesMultilinePatchText() {
        val patch = PromptPatchDraft(
            agentId = "  *  ",
            fileName = "  prompt.md  ",
            findText = "line one\nline two ",
            replacementText = " replacement\n",
        ).toPatch("id")

        assertEquals("*", patch.agentId())
        assertEquals("prompt.md", patch.fileName())
        assertEquals("line one\nline two ", patch.findText())
        assertEquals(" replacement\n", patch.replacementText())
    }

    @Test
    fun rejectsEmptyFindTextAndPathFileName() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptPatchDraft(findText = "").toPatch("id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PromptPatchDraft(fileName = "dir/prompt.md", findText = "text").toPatch("id")
        }
    }
}
