package com.example.waspmobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedInventoryDropTest {
    @Test
    fun missingScanNeverFallsBackToAllSlots() {
        assertNull(VerifiedInventoryDrop.plan(null, (0..27).toList(), emptySet()))
    }

    @Test
    fun outOfRangeOccupancyIsRejected() {
        assertNull(VerifiedInventoryDrop.plan(setOf(0, 28), (0..27).toList(), emptySet()))
        assertNull(VerifiedInventoryDrop.plan(setOf(-1), (0..27).toList(), emptySet()))
    }

    @Test
    fun verifiedEmptyInventoryProducesNoActions() {
        assertEquals(emptyList<Int>(), VerifiedInventoryDrop.plan(emptySet(), (0..27).toList(), emptySet()))
    }

    @Test
    fun respectsBothProtectedSlotsAndScannedOccupancy() {
        assertEquals(
            listOf(7, 3),
            VerifiedInventoryDrop.plan(setOf(0, 1, 3, 7), listOf(7, 7, 0, 2, 3, 1), setOf(0, 1))
        )
    }

    @Test
    fun invalidOrderEntriesAreDiscarded() {
        assertEquals(
            listOf(4),
            VerifiedInventoryDrop.plan(setOf(4), listOf(-1, 28, 4, 4), emptySet())
        )
    }
}
