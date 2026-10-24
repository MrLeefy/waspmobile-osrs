package com.example.waspmobile.service

internal enum class AgentPrimaryAction(val label: String, val stopsInput: Boolean) {
    STOP_PLAN("STOP AI", true), PAUSE_REMOTE("PAUSE REMOTE", true),
    ALLOW_REMOTE("ALLOW REMOTE", false), STOP_LOCAL("PAUSE", true), START_LOCAL("START", false);
    companion object {
        fun resolve(planRunning: Boolean, remoteEnabled: Boolean, manualHold: Boolean, localRunning: Boolean): AgentPrimaryAction = when {
            planRunning -> STOP_PLAN
            remoteEnabled && manualHold -> ALLOW_REMOTE
            remoteEnabled -> PAUSE_REMOTE
            localRunning -> STOP_LOCAL
            else -> START_LOCAL
        }
    }
}
