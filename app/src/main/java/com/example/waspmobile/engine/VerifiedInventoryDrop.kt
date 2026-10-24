package com.example.waspmobile.engine

/**
 * Fail-closed inventory slot selector.
 *
 * A missing or malformed occupancy scan must never be interpreted as a request
 * to tap all 28 slots. A verified empty scan is different from an unknown one.
 */
internal object VerifiedInventoryDrop {
    fun plan(
        filledSlots: Set<Int>?,
        preferredOrder: List<Int>,
        protectedSlots: Set<Int>
    ): List<Int>? {
        if (filledSlots == null || filledSlots.any { it !in 0..27 }) return null
        return preferredOrder.asSequence()
            .filter { it in 0..27 && it in filledSlots && it !in protectedSlots }
            .distinct()
            .toList()
    }
}
