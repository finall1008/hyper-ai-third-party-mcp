package io.github.finall1008.xiaoaimcp.ui

import io.github.finall1008.xiaoaimcp.filepolicy.FileAccessRule
import io.github.finall1008.xiaoaimcp.filepolicy.FilePolicyConfig
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutConfig
import io.github.finall1008.xiaoaimcp.timeout.FirstOutputTimeoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MainActivityTest {
    @Test
    fun rootPagesAreStableAndDefaultToHome() {
        assertEquals(listOf(RootPage.HOME, RootPage.ABOUT), ROOT_PAGES)
        assertEquals(listOf("首页", "关于"), ROOT_PAGES.map { it.label })
        assertEquals(RootPage.HOME, DEFAULT_ROOT_PAGE)
    }

    @Test
    fun parsesPositiveWholeSeconds() {
        assertEquals(321L, parseFirstOutputTimeoutSeconds(" 321 "))
    }

    @Test
    fun initializesTimeoutInputWithCursorAtEnd() {
        val value = timeoutTextFieldValue(120L)

        assertEquals("120", value.text)
        assertEquals(3, value.selection.start)
        assertEquals(3, value.selection.end)
    }

    @Test
    fun rejectsInvalidTimeoutText() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFirstOutputTimeoutSeconds("1.5")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFirstOutputTimeoutSeconds("0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFirstOutputTimeoutSeconds("999999999999999999999")
        }
    }

    @Test
    fun summarizesAllModes() {
        assertEquals(
            "跟随宿主（参考版 120 秒）",
            firstOutputTimeoutSummary(FirstOutputTimeoutConfig.hostDefault()),
        )
        assertEquals(
            "自定义 300 秒",
            firstOutputTimeoutSummary(
                FirstOutputTimeoutConfig(FirstOutputTimeoutMode.CUSTOM, 300L),
            ),
        )
        assertEquals(
            "不限制",
            firstOutputTimeoutSummary(
                FirstOutputTimeoutConfig(FirstOutputTimeoutMode.UNLIMITED, 120L),
            ),
        )
    }

    @Test
    fun summarizesFilePolicyStateAndRuleCount() {
        assertEquals(
            "未启用 · 尚未配置目录规则",
            filePolicySummary(FilePolicyConfig.disabled()),
        )
        assertEquals(
            "已启用 · 1 条目录规则",
            filePolicySummary(
                FilePolicyConfig(
                    true,
                    listOf(
                        FileAccessRule(
                            "/storage/emulated/0/Download",
                            true,
                            false,
                            false,
                            false,
                            false,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun exposesVersionLabelAndOfficialReleasePage() {
        assertEquals("当前版本 1.6.0", aboutVersionLabel("1.6.0"))
        assertEquals(
            "https://github.com/finall1008/hyper-ai-third-party-mcp/releases",
            GITHUB_RELEASES_URL,
        )
    }

}
