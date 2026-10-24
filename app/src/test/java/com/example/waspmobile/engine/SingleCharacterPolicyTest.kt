package com.example.waspmobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleCharacterPolicyTest {
    @Test
    fun onlyFourSingleCharacterModesAreSelectable() {
        assertEquals(4, SingleCharacterPolicy.selectableModes.size)
        assertFalse(SingleCharacterPolicy.selectableModes.contains(WaspScriptMode.DUAL_SPLIT_SCREEN))
        assertTrue(SingleCharacterPolicy.selectableModes.contains(WaspScriptMode.WOODCUTTER_DROP))
    }

    @Test
    fun legacySplitPreferenceMigratesToSafeWoodcutMode() {
        assertEquals(WaspScriptMode.WOODCUTTER_DROP,
            SingleCharacterPolicy.normalize(WaspScriptMode.DUAL_SPLIT_SCREEN))
    }

    @Test
    fun cyclingDoesNotReachDualSplit() {
        var mode = WaspScriptMode.WOODCUTTER_DROP
        repeat(40) {
            mode = SingleCharacterPolicy.next(mode)
            assertFalse(mode == WaspScriptMode.DUAL_SPLIT_SCREEN)
        }
    }

    @Test
    fun theLastSingleCharacterModeCyclesToFirst() {
        assertEquals(WaspScriptMode.WOODCUTTER_DROP,
            SingleCharacterPolicy.next(WaspScriptMode.BANKSTAND_COMBINER))
    }

    @Test
    fun allSingleCharacterModesKeepTheirIdentity() {
        for (mode in SingleCharacterPolicy.selectableModes) {
            assertEquals(mode, SingleCharacterPolicy.normalize(mode))
        }
    }
}
