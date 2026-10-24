package com.example.waspmobile.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PlanFrame(val windowKey: String, val width: Int, val height: Int, val pixels: IntArray)
internal data class PlanRunResult(val completed: Boolean, val stepsCompleted: Int, val tapsAttempted: Int, val detail: String)
internal interface MobilePlanDriver {
    fun now(): Long
    fun ready(windowKey: String): Boolean
    suspend fun observe(): PlanFrame?
    suspend fun tap(target: PlanTarget, windowKey: String): Boolean
    fun progress(step: Int, detail: String)
}

/** Bounded local orchestration: no arbitrary code, no retries, no background auto-resume. */
internal class MobilePlanRunner(private val driver: MobilePlanDriver) {
    suspend fun run(plan: MobilePlan, windowKey: String): PlanRunResult {
        val deadline = driver.now() + plan.maxRuntimeMs
        var completed = 0
        var taps = 0
        fun allowed() = driver.now() < deadline && driver.ready(windowKey)
        fun failed(reason: String) = PlanRunResult(false, completed, taps, reason)
        for ((index, step) in plan.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) return failed("Input ownership, connection, window or time budget changed")
            driver.progress(index, step.label)
            if (step.op == "wait") {
                val until = driver.now() + step.ms
                while (driver.now() < until) {
                    currentCoroutineContext().ensureActive()
                    if (!allowed()) return failed("Interrupted while waiting")
                    delay(minOf(100L, until - driver.now()).coerceAtLeast(1L))
                }
            } else {
                val frame = driver.observe() ?: return failed("Fresh game-window capture failed")
                if (!allowed() || frame.windowKey != windowKey) return failed("Window changed during observation")
                if (step.op != "observe") {
                    val target = withContext(Dispatchers.Default) {
                        PlanColorMatcher.find(frame.width, frame.height, frame.pixels, requireNotNull(step.guard))
                    } ?: return failed("Color target missing, ambiguous or background-dominated")
                    if (!allowed()) return failed("Input authorization changed after vision")
                    if (step.op == "tapColor") {
                        taps++
                        if (!driver.tap(target, windowKey)) return failed("Gesture outcome uncertain; no retry was attempted")
                    }
                }
            }
            if (!allowed()) return failed("Plan invalidated before step confirmation")
            completed++
        }
        currentCoroutineContext().ensureActive()
        if (!allowed()) return failed("Plan budget exhausted")
        val final = driver.observe() ?: return failed("Final game-window capture failed")
        if (final.windowKey != windowKey || !allowed()) return failed("Window changed before final observation")
        return PlanRunResult(true, completed, taps, "Plan inputs completed; game objective is not independently verified")
    }
}
