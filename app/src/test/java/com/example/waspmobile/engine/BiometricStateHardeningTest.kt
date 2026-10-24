package com.example.waspmobile.engine

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.example.waspmobile.service.CalibrationUXHelper
import com.example.waspmobile.service.DragTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class BiometricStateHardeningTest {

    private val touchEngine = HumanTouchEngine()

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply { this.left = left; this.top = top; this.right = right; this.bottom = bottom }

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply { this.left = left; this.top = top; this.right = right; this.bottom = bottom }

    private fun pointF(x: Float, y: Float): PointF =
        PointF().apply { this.x = x; this.y = y }

    private fun w(r: Rect): Int = r.right - r.left
    private fun h(r: Rect): Int = r.bottom - r.top
    private fun w(r: RectF): Float = r.right - r.left
    private fun h(r: RectF): Float = r.bottom - r.top

    // =========================================================================
    // 1. R3 BIOMETRIC GESTURE TIMING & COORDINATE CLAMPING TESTS
    // =========================================================================

    @Test
    fun testSampleGaussianPointStaysStrictlyInsideScreenBounds() {
        val screenBounds = rect(0, 0, 1080, 2400)
        val maxScreenX = (w(screenBounds) - 2).toFloat()
        val maxScreenY = (h(screenBounds) - 2).toFloat()

        // Test normal target inside screen
        val normalTarget = rect(200, 400, 350, 550)
        for (i in 0 until 100) {
            val pt = touchEngine.sampleGaussianPoint(normalTarget, screenBounds)
            assertTrue("Point X must be >= 2f (was ${pt.x})", pt.x >= 2f)
            assertTrue("Point X must be <= maxScreenX (was ${pt.x})", pt.x <= maxScreenX)
            assertTrue("Point Y must be >= 2f (was ${pt.y})", pt.y >= 2f)
            assertTrue("Point Y must be <= maxScreenY (was ${pt.y})", pt.y <= maxScreenY)
        }

        // Test target rectangle at the extreme top-left border (0, 0, 50, 50)
        val topLeftTarget = rect(0, 0, 50, 50)
        for (i in 0 until 50) {
            val pt = touchEngine.sampleGaussianPoint(topLeftTarget, screenBounds)
            assertTrue("Top-left border target point X must be >= 2f", pt.x >= 2f)
            assertTrue("Top-left border target point Y must be >= 2f", pt.y >= 2f)
        }

        // Test target rectangle at extreme right-bottom border
        val rightBottomTarget = rect(1040, 2360, 1080, 2400)
        for (i in 0 until 50) {
            val pt = touchEngine.sampleGaussianPoint(rightBottomTarget, screenBounds)
            assertTrue("Right-bottom border target point X must be <= maxScreenX", pt.x <= maxScreenX)
            assertTrue("Right-bottom border target point Y must be <= maxScreenY", pt.y <= maxScreenY)
        }
    }

    @Test
    fun testComputeMicroStrokeEndClampingAndInwardAdjustment() {
        val screenBounds = rect(0, 0, 1920, 1080)
        val maxScreenX = (w(screenBounds) - 2).toFloat() // 1918f
        val maxScreenY = (h(screenBounds) - 2).toFloat() // 1078f

        // Case 1: Point at extreme top-left (startPt = 2f, 2f).
        // Since startPt < center, inward adjustment must drift positive toward center.
        val startTopLeft = pointF(2f, 2f)
        val targetTopLeft = rect(0, 0, 10, 10)
        val endTopLeft = touchEngine.computeMicroStrokeEnd(
            startPt = startTopLeft,
            targetRect = targetTopLeft,
            screenBounds = screenBounds,
            rollAngle = 0.0,
            rollDist = 0.0f // Force delta < 0.5f to trigger inward micro-stroke adjustment
        )
        assertTrue("Inward micro-stroke must adjust positively toward centerX (was ${endTopLeft.x})", endTopLeft.x > startTopLeft.x)
        assertTrue("Inward micro-stroke must adjust positively toward centerY (was ${endTopLeft.y})", endTopLeft.y > startTopLeft.y)
        assertTrue("End point must remain clamped inside screen", endTopLeft.x in 2f..maxScreenX)
        assertTrue("End point must remain clamped inside screen", endTopLeft.y in 2f..maxScreenY)

        // Case 2: Point at extreme bottom-right (startPt = 1918f, 1078f).
        // Since startPt >= center, inward adjustment must drift negative toward center.
        val startBottomRight = pointF(maxScreenX, maxScreenY)
        val targetBottomRight = rect(1910, 1070, 1920, 1080)
        val endBottomRight = touchEngine.computeMicroStrokeEnd(
            startPt = startBottomRight,
            targetRect = targetBottomRight,
            screenBounds = screenBounds,
            rollAngle = 0.0,
            rollDist = 0.0f // Force delta < 0.5f
        )
        assertTrue("Inward micro-stroke must adjust negatively toward centerX (was ${endBottomRight.x})", endBottomRight.x < startBottomRight.x)
        assertTrue("Inward micro-stroke must adjust negatively toward centerY (was ${endBottomRight.y})", endBottomRight.y < startBottomRight.y)
        assertTrue("End point must remain clamped inside screen", endBottomRight.x in 2f..maxScreenX)
        assertTrue("End point must remain clamped inside screen", endBottomRight.y in 2f..maxScreenY)
    }

    @Test
    fun testMicroStrokeDeltaIsAlwaysNonZero() {
        val screenBounds = rect(0, 0, 2400, 1080)
        val rng = Random(42)

        for (i in 0 until 1000) {
            val left = rng.nextInt(0, 2300)
            val top = rng.nextInt(0, 1000)
            val right = left + rng.nextInt(2, 80)
            val bottom = top + rng.nextInt(2, 80)
            val target = rect(left, top, right, bottom)

            val startPt = touchEngine.sampleGaussianPoint(target, screenBounds)
            val endPt = touchEngine.computeMicroStrokeEnd(startPt, target, screenBounds)

            val delta = hypot(endPt.x - startPt.x, endPt.y - startPt.y)
            assertTrue(
                "Micro-stroke delta must be non-zero (>= 0.5f) to defeat auto-clicker detection (was $delta)",
                delta >= 0.5f
            )
            assertTrue("Start X within screen bounds", startPt.x in 2f..(w(screenBounds) - 2).toFloat())
            assertTrue("Start Y within screen bounds", startPt.y in 2f..(h(screenBounds) - 2).toFloat())
            assertTrue("End X within screen bounds", endPt.x in 2f..(w(screenBounds) - 2).toFloat())
            assertTrue("End Y within screen bounds", endPt.y in 2f..(h(screenBounds) - 2).toFloat())
        }
    }

    @Test
    fun testTapHoldDurationDistribution() {
        for (i in 0 until 200) {
            val holdMs = touchEngine.sampleTapHoldDurationMs()
            assertTrue("Hold duration must be >= 38ms (was $holdMs)", holdMs >= 38L)
            assertTrue("Hold duration must be <= 132ms (was $holdMs)", holdMs <= 132L)
        }
    }

    // =========================================================================
    // 2. R4 THREAD-SAFE STATE & DATETIMEFORMATTER TESTS
    // =========================================================================

    @Test
    fun testDateTimeFormatterPattern() {
        val fixedTime = LocalTime.of(14, 30, 45)
        val formatted = WaspMobileState.formatTimestamp(fixedTime)
        assertEquals("14:30:45", formatted)

        val midnight = LocalTime.of(0, 5, 9)
        val formattedMidnight = WaspMobileState.formatTimestamp(midnight)
        assertEquals("00:05:09", formattedMidnight)

        val currentStamp = WaspMobileState.formatTimestamp()
        assertTrue(
            "Timestamp must match HH:mm:ss regex (was '$currentStamp')",
            currentStamp.matches(Regex("""^\d{2}:\d{2}:\d{2}$"""))
        )
    }

    @Test
    fun testThreadSafeConcurrentLogging() = runTest {
        val jobsCount = 50
        val uniqueMessages = ConcurrentHashMap.newKeySet<String>()

        val jobs = List(jobsCount) { idx ->
            launch(Dispatchers.Default) {
                val msg = "Log message from worker #$idx at iter 0"
                uniqueMessages.add(msg)
                WaspMobileState.log(msg)
            }
        }
        jobs.joinAll()

        val logs = WaspMobileState.state.value.logs
        assertTrue("Logs list must be non-empty", logs.isNotEmpty())
        assertTrue("Logs list must never exceed 40 entries", logs.size <= 40)
        for (line in logs) {
            assertTrue("Every log entry must start with [HH:mm:ss] timestamp", line.matches(Regex("""^\[\d{2}:\d{2}:\d{2}\].*""")))
        }
    }

    @Test
    fun testAtomicModeTransitions() {
        val initialMode = WaspMobileState.state.value.scriptMode
        val allModes = SingleCharacterPolicy.selectableModes

        for (i in 0 until allModes.size) {
            val expectedNext = SingleCharacterPolicy.next(WaspMobileState.state.value.scriptMode)
            val cycled = WaspMobileState.cycleScriptMode()
            assertEquals("Single-device mode must never select dual split screen", expectedNext, cycled)
            assertEquals("StateFlow must reflect updated mode immediately", expectedNext, WaspMobileState.state.value.scriptMode)
        }

        // Test calibration mode cycle: NONE -> ADJUST_SEARCH_BOX -> ADJUST_INVENTORY_GRID -> NONE
        WaspMobileState.update { it.copy(calibrationMode = CalibrationMode.NONE) }
        val c1 = WaspMobileState.cycleCalibrationMode()
        assertEquals(CalibrationMode.ADJUST_SEARCH_BOX, c1)

        val c2 = WaspMobileState.cycleCalibrationMode()
        assertEquals(CalibrationMode.ADJUST_INVENTORY_GRID, c2)

        val c3 = WaspMobileState.cycleCalibrationMode()
        assertEquals(CalibrationMode.NONE, c3)
    }

    // =========================================================================
    // 3. R4 INTERACTIVE CALIBRATION UX & DRAG TARGET TESTS
    // =========================================================================

    @Test
    fun testCalibrationUXCornerHitDetection() {
        val boxPx = rectF(100f, 150f, 600f, 800f)
        val cornerRadius = 36f
        val edgeThreshold = 24f

        // Exact corners
        assertEquals(DragTarget.TOP_LEFT, CalibrationUXHelper.hitTest(100f, 150f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.TOP_RIGHT, CalibrationUXHelper.hitTest(600f, 150f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.BOTTOM_LEFT, CalibrationUXHelper.hitTest(100f, 800f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.BOTTOM_RIGHT, CalibrationUXHelper.hitTest(600f, 800f, boxPx, cornerRadius, edgeThreshold))

        // Slight offset within corner radius (distance = 25px < 36px)
        assertEquals(DragTarget.TOP_LEFT, CalibrationUXHelper.hitTest(115f, 165f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.TOP_RIGHT, CalibrationUXHelper.hitTest(585f, 165f, boxPx, cornerRadius, edgeThreshold))
    }

    @Test
    fun testCalibrationUXEdgeAndBodyHitDetection() {
        val boxPx = rectF(100f, 150f, 600f, 800f)
        val cornerRadius = 36f
        val edgeThreshold = 24f

        // Mid-edge touches outside corner radius
        assertEquals(DragTarget.EDGE_TOP, CalibrationUXHelper.hitTest(350f, 155f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.EDGE_BOTTOM, CalibrationUXHelper.hitTest(350f, 795f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.EDGE_LEFT, CalibrationUXHelper.hitTest(105f, 475f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.EDGE_RIGHT, CalibrationUXHelper.hitTest(595f, 475f, boxPx, cornerRadius, edgeThreshold))

        // Inside center of box -> BODY_PAN
        assertEquals(DragTarget.BODY_PAN, CalibrationUXHelper.hitTest(350f, 475f, boxPx, cornerRadius, edgeThreshold))

        // Far outside box -> NONE
        assertEquals(DragTarget.NONE, CalibrationUXHelper.hitTest(10f, 10f, boxPx, cornerRadius, edgeThreshold))
        assertEquals(DragTarget.NONE, CalibrationUXHelper.hitTest(900f, 900f, boxPx, cornerRadius, edgeThreshold))
    }

    @Test
    fun testApplyDragEnforcesMinimumDimensionAndPreventsInversion() {
        val initial = rectF(0.20f, 0.20f, 0.60f, 0.60f)
        val minDim = 0.08f

        // 1. Extreme inward drag on TOP_LEFT trying to invert across right/bottom edges
        // User moves finger delta +0.80f (far past the right edge of 0.60f)
        val invertedTL = CalibrationUXHelper.applyDrag(initial, DragTarget.TOP_LEFT, +0.80f, +0.80f, minDim)
        assertEquals("Left must be clamped to right - minDim", initial.right - minDim, invertedTL.left, 0.0001f)
        assertEquals("Top must be clamped to bottom - minDim", initial.bottom - minDim, invertedTL.top, 0.0001f)
        assertTrue("Width must be >= minDim", w(invertedTL) >= minDim - 0.0001f)
        assertTrue("Height must be >= minDim", h(invertedTL) >= minDim - 0.0001f)

        // 2. Extreme inward drag on BOTTOM_RIGHT trying to invert across left/top edges
        // User moves finger delta -0.80f (far past the left edge of 0.20f)
        val invertedBR = CalibrationUXHelper.applyDrag(initial, DragTarget.BOTTOM_RIGHT, -0.80f, -0.80f, minDim)
        assertEquals("Right must be clamped to left + minDim", initial.left + minDim, invertedBR.right, 0.0001f)
        assertEquals("Bottom must be clamped to top + minDim", initial.top + minDim, invertedBR.bottom, 0.0001f)
        assertTrue("Width must be >= minDim", w(invertedBR) >= minDim - 0.0001f)
        assertTrue("Height must be >= minDim", h(invertedBR) >= minDim - 0.0001f)

        // 3. Body panning clamped within screen limits
        val pannedRight = CalibrationUXHelper.applyDrag(initial, DragTarget.BODY_PAN, +0.90f, 0f, minDim)
        assertEquals(0.99f, pannedRight.right, 0.0001f)
        assertEquals(0.99f - w(initial), pannedRight.left, 0.0001f)
        assertEquals(w(initial), w(pannedRight), 0.0001f)
    }
}
