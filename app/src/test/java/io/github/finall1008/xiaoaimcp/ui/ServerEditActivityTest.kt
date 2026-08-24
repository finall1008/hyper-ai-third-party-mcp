package io.github.finall1008.xiaoaimcp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEditActivityTest {
    @Test
    fun parseHeadersPreservesOrderAndColonInValue() {
        val headers = ServerEditActivity.parseHeaders(
            "Authorization: Bearer abc:def\nX-API-Key: secret",
        )

        assertEquals(listOf("Authorization", "X-API-Key"), headers.keys.toList())
        assertEquals("Bearer abc:def", headers["Authorization"])
    }

    @Test
    fun parseHeadersRejectsDuplicateNames() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerEditActivity.parseHeaders("X-Key: first\nX-Key: second")
        }

        assertEquals("请求头名称重复：X-Key", error.message)
    }

    @Test
    fun parseHeadersRejectsMissingSeparator() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerEditActivity.parseHeaders("Authorization Bearer token")
        }

        assertEquals("请求头每行必须使用 Name: Value 格式", error.message)
    }
}
