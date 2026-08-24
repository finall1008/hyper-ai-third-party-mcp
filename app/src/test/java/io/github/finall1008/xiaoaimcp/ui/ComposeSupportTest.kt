package io.github.finall1008.xiaoaimcp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeSupportTest {
    @Test
    fun hyperOsMaximumWeightEnablesBoldText() {
        assertTrue(shouldUseBoldText(miuiFontWeightScale = 100, standardAdjustment = 0))
    }

    @Test
    fun androidFontWeightAdjustmentEnablesBoldText() {
        assertTrue(shouldUseBoldText(miuiFontWeightScale = 50, standardAdjustment = 300))
    }

    @Test
    fun normalWeightKeepsDefaultTypography() {
        assertFalse(shouldUseBoldText(miuiFontWeightScale = 50, standardAdjustment = 0))
    }
}
