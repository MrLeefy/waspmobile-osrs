package com.example.waspmobile.service

import org.junit.Assert.*
import org.junit.Test

class RemoteSessionGateTest {
    private var clock = 1000L
    private val gate = RemoteSessionGate { clock }
    @Test fun disabledSessionRejectsCommands() {
        assertNull(gate.accept("cmd", "session", 1000, clock))
    }
    @Test fun commandDeadlineUsesElapsedTimeRatherThanWallClock() {
        gate.enable("session")
        assertEquals(2000L, gate.accept("cmd", "session", 1000, clock))
        clock = 2001
        assertNull(gate.accept("late", "session", 1000, 1000))
    }
    @Test fun duplicateCommandsNeverRunTwice() {
        gate.enable("session")
        assertNotNull(gate.accept("cmd", "session", 1000, clock))
        assertNull(gate.accept("cmd", "session", 1000, clock))
    }
    @Test fun oldSessionIsRejectedAfterReconnect() {
        gate.enable("old"); gate.enable("new")
        assertNull(gate.accept("cmd", "old", 1000, clock))
        assertNotNull(gate.accept("cmd", "new", 1000, clock))
    }
    @Test fun disablingImmediatelyRevokesExistingFrames() {
        gate.enable("session"); gate.recordFrame("frame", "window", clock, "session"); gate.disable()
        assertFalse(gate.consumeFrame("frame", "window", "session"))
    }
    @Test fun aFrameCanAuthorizeOnlyOneTap() {
        gate.enable("session"); gate.recordFrame("frame", "window", clock, "session")
        assertTrue(gate.consumeFrame("frame", "window", "session"))
        assertFalse(gate.consumeFrame("frame", "window", "session"))
    }
    @Test fun geometryChangesCannotReuseCoordinates() {
        gate.enable("session"); gate.recordFrame("frame", "portrait", clock, "session")
        assertFalse(gate.consumeFrame("frame", "landscape", "session"))
    }
    @Test fun expiredFramesDoNotAuthorizeInput() {
        gate.enable("session"); gate.recordFrame("frame", "window", clock, "session")
        clock += 15_001
        assertFalse(gate.consumeFrame("frame", "window", "session"))
    }
    @Test fun invalidTimeBudgetsAreRejected() {
        gate.enable("session")
        for (ttl in listOf(-1L, 0L, 10_001L, Long.MAX_VALUE)) assertNull(gate.accept("cmd", "session", ttl, clock))
        assertNull(gate.accept("future", "session", 1000, clock + 1))
    }
    @Test fun changedOrInterruptedObservationIsInvalidated() {
        gate.enable("session"); gate.recordFrame("frame", "window", clock, "session")
        gate.invalidateFrame()
        assertFalse(gate.consumeFrame("frame", "window", "session"))
    }
    @Test fun receiptMemoryHasABoundedLimit() {
        gate.enable("session")
        for (i in 0 until 4096) assertNotNull(gate.accept("cmd-$i", "session", 1000, clock))
        assertNull(gate.accept("overflow", "session", 1000, clock))
    }
}
