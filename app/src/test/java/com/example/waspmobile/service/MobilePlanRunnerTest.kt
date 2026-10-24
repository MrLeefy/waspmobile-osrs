package com.example.waspmobile.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobilePlanRunnerTest {
    private val guard = ColorGuard(PlanRegion(.1, .1, .9, .9), listOf(255, 0, 0), 0, 4)
    private fun frame(key: String = "window", match: Boolean = true): PlanFrame {
        val pixels = IntArray(4096) { 0xFFFFFF }
        if (match) for (y in 20..23) for (x in 20..23) pixels[y * 64 + x] = 0xFF0000
        return PlanFrame(key, 64, 64, pixels)
    }
    private fun plan(vararg steps: MobilePlanStep) = MobilePlan("Test", "Synthetic unit fixture", 5000, steps.toList())
    @Test fun guardedTapUsesFreshObservationAndFinalCheck() = runTest {
        var captures = 0; var taps = 0
        val runner = MobilePlanRunner(object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = true
            override suspend fun observe(): PlanFrame { captures++; return frame() }
            override suspend fun tap(target: PlanTarget, windowKey: String): Boolean { taps++; return true }
            override fun progress(step: Int, detail: String) {}
        })
        val result = runner.run(plan(MobilePlanStep("tapColor", "tap", guard = guard)), "window")
        assertTrue(result.completed); assertEquals(1, taps); assertEquals(2, captures)
        assertTrue(result.detail.contains("not independently verified"))
    }
    @Test fun failedVisionNeverDispatchesATap() = runTest {
        var taps = 0
        val driver = object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = true
            override suspend fun observe() = frame(match = false)
            override suspend fun tap(target: PlanTarget, windowKey: String): Boolean { taps++; return true }
            override fun progress(step: Int, detail: String) {}
        }
        assertFalse(MobilePlanRunner(driver).run(plan(MobilePlanStep("tapColor", "tap", guard = guard)), "window").completed)
        assertEquals(0, taps)
    }
    @Test fun uncertainGestureIsNotRetried() = runTest {
        var taps = 0
        val driver = object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = true
            override suspend fun observe() = frame()
            override suspend fun tap(target: PlanTarget, windowKey: String): Boolean { taps++; return false }
            override fun progress(step: Int, detail: String) {}
        }
        val result = MobilePlanRunner(driver).run(plan(MobilePlanStep("tapColor", "tap", guard = guard),
            MobilePlanStep("tapColor", "not reached", guard = guard)), "window")
        assertFalse(result.completed); assertEquals(1, taps)
    }
    @Test fun geometryChangeBetweenObservationAndInputStopsPlan() = runTest {
        var taps = 0
        val driver = object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = true
            override suspend fun observe() = frame("other-window")
            override suspend fun tap(target: PlanTarget, windowKey: String): Boolean { taps++; return true }
            override fun progress(step: Int, detail: String) {}
        }
        assertFalse(MobilePlanRunner(driver).run(plan(MobilePlanStep("tapColor", "tap", guard = guard)), "window").completed)
        assertEquals(0, taps)
    }
    @Test fun manualTakeoverDuringWaitStopsBeforeNextAction() = runTest {
        var taps = 0
        val driver = object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = testScheduler.currentTime < 200
            override suspend fun observe() = frame()
            override suspend fun tap(target: PlanTarget, windowKey: String): Boolean { taps++; return true }
            override fun progress(step: Int, detail: String) {}
        }
        val result = MobilePlanRunner(driver).run(plan(MobilePlanStep("wait", "wait", 1000),
            MobilePlanStep("tapColor", "tap", guard = guard)), "window")
        assertFalse(result.completed); assertEquals(0, taps); assertTrue(testScheduler.currentTime <= 300)
    }
    @Test fun cancellationDuringWaitNeverResumesAutomatically() = runTest {
        var taps = 0; var cancelled = false
        val driver = object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = true
            override suspend fun observe() = frame()
            override suspend fun tap(target: PlanTarget, windowKey: String): Boolean { taps++; return true }
            override fun progress(step: Int, detail: String) {}
        }
        val job = launch {
            try { MobilePlanRunner(driver).run(plan(MobilePlanStep("wait", "wait", 1000),
                MobilePlanStep("tapColor", "tap", guard = guard)), "window") }
            catch (e: CancellationException) { cancelled = true; throw e }
        }
        delay(150); job.cancel(); job.join()
        assertTrue(cancelled); assertEquals(0, taps)
    }
    @Test fun captureFailureAndExpiredBudgetCannotClaimCompletion() = runTest {
        val driver = object : MobilePlanDriver {
            override fun now() = testScheduler.currentTime
            override fun ready(windowKey: String) = true
            override suspend fun observe(): PlanFrame? { delay(6000); return null }
            override suspend fun tap(target: PlanTarget, windowKey: String) = error("must not tap")
            override fun progress(step: Int, detail: String) {}
        }
        assertFalse(MobilePlanRunner(driver).run(plan(MobilePlanStep("observe", "observe")), "window").completed)
    }
}
