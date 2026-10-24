package com.example.waspmobile.service
import org.junit.Assert.assertEquals
import org.junit.Test
class AgentPrimaryActionTest {
    @Test fun runningAiMustNeverShowStartLegacyScript() {
        assertEquals(AgentPrimaryAction.STOP_PLAN, AgentPrimaryAction.resolve(true, true, false, false))
    }
    @Test fun pairedAgentHasPauseControlEvenWithoutALocalLoop() {
        assertEquals(AgentPrimaryAction.PAUSE_REMOTE, AgentPrimaryAction.resolve(false, true, false, false))
    }
    @Test fun manualHoldNeedsExplicitResume() {
        assertEquals(AgentPrimaryAction.ALLOW_REMOTE, AgentPrimaryAction.resolve(false, true, true, false))
    }
    @Test fun disconnectedLocalLoopCanBeStopped() {
        assertEquals(AgentPrimaryAction.STOP_LOCAL, AgentPrimaryAction.resolve(false, false, false, true))
    }
    @Test fun unpairedIdleDeviceCanStartLocalScript() {
        assertEquals(AgentPrimaryAction.START_LOCAL, AgentPrimaryAction.resolve(false, false, false, false))
    }
}
