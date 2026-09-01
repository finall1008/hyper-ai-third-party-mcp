package io.github.finall1008.xiaoaimcp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentTraceRetentionUiTest {
    @Test
    fun parsesCustomRetentionBounds() {
        assertEquals(90, parseRetentionValue(" 90 ", 3650, "保留天数"))
        assertEquals(500, parseRetentionValue("500", 10_000, "最大会话数"))
    }

    @Test
    fun rejectsInvalidCustomRetentionValues() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRetentionValue("0", 3650, "保留天数")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRetentionValue("forever", 10_000, "最大会话数")
        }
    }

    @Test
    fun sessionTitlesAreSingleLineAndBounded() {
        assertEquals("hello world", traceTitle(" hello\nworld "))
        assertEquals("无用户正文", traceTitle("  \n "))
        assertEquals("12345…", traceTitle("123456789", maximum = 5))
    }
}
