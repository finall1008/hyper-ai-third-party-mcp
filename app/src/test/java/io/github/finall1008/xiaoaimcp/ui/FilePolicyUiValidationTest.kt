package io.github.finall1008.xiaoaimcp.ui

import io.github.finall1008.xiaoaimcp.filepolicy.MutationConfirmationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FilePolicyUiValidationTest {
    @Test
    fun defaultRuleIsValid() {
        validateRuleFlags(RuleDraft())
    }

    @Test
    fun recursiveDeleteRequiresLockscreenMutation() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateRuleFlags(RuleDraft(allowRecursiveDelete = true))
        }

        assertEquals("锁屏递归删除需先允许锁屏新建及删改", error.message)
    }

    @Test
    fun backgroundAutomaticRequiresBackgroundMutation() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateRuleFlags(
                RuleDraft(
                    confirmationPolicy = MutationConfirmationPolicy.BACKGROUND_AUTOMATIC,
                ),
            )
        }

        assertEquals("后台自动允许需先允许后台/定时 Agent 删改", error.message)
    }

    @Test
    fun lockscreenMutationRequiresBaseMutation() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateRuleFlags(
                RuleDraft(
                    allowMutation = false,
                    allowLockscreenMutation = true,
                ),
            )
        }

        assertEquals("锁屏删改、后台删改和锁屏递归删除需先允许删改既有文件", error.message)
    }
}
