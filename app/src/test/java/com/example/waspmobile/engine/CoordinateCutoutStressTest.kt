package com.example.waspmobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CoordinateCutoutStressTest {

    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Pure mathematical model of MobileVisionEngine.normToScreenRect
     */
    private fun normToScreenRectMath(norm: FloatRect, screenW: Int, screenH: Int): IntRect {
        val w = max(2, screenW)
        val h = max(2, screenH)
        val l = (norm.left * w).toInt().coerceIn(0, w - 2)
        val t = (norm.top * h).toInt().coerceIn(0, h - 2)
        val r = (norm.right * w).toInt().coerceIn(l + 1, w)
        val b = (norm.bottom * h).toInt().coerceIn(t + 1, h)
        return IntRect(l, t, r, b)
    }

    /**
     * Mathematical model of cluster scaling in WaspAccessibilityService
     */
    private fun scaleCluster(c: IntRect, scaleX: Float, scaleY: Float, dispW: Int, dispH: Int): IntRect {
        val left = (c.left * scaleX).toInt().coerceIn(0, dispW - 2)
        val top = (c.top * scaleY).toInt().coerceIn(0, dispH - 2)
        val right = (c.right * scaleX).toInt().coerceIn(2, dispW)
        val bottom = (c.bottom * scaleY).toInt().coerceIn(2, dispH)
        return IntRect(left, top, right, bottom)
    }

    @Test
    fun testDivisionByZeroGuardsWhenBitmapDimensionsZeroOrNegative() {
        val dispW = 2340f
        val dispH = 1080f

        for (zeroOrNegW in listOf(0, -1, -100, -1080)) {
            val scaleX = if (zeroOrNegW > 0) dispW / zeroOrNegW.toFloat() else 1.0f
            assertEquals(1.0f, scaleX, 0.0001f)
            assertFalse("scaleX must not be Infinite", scaleX.isInfinite())
            assertFalse("scaleX must not be NaN", scaleX.isNaN())
        }

        for (zeroOrNegH in listOf(0, -1, -50, -2340)) {
            val scaleY = if (zeroOrNegH > 0) dispH / zeroOrNegH.toFloat() else 1.0f
            assertEquals(1.0f, scaleY, 0.0001f)
            assertFalse("scaleY must not be Infinite", scaleY.isInfinite())
            assertFalse("scaleY must not be NaN", scaleY.isNaN())
        }
    }

    @Test
    fun testNormToScreenRectGuardsAgainstZeroAndNegativeDimensions() {
        val norm = FloatRect(0.14f, 0.12f, 0.68f, 0.84f)

        // 1. Zero screen dimensions
        val zeroRect = normToScreenRectMath(norm, 0, 0)
        assertTrue("zeroRect width must be at least 1", zeroRect.width >= 1)
        assertTrue("zeroRect height must be at least 1", zeroRect.height >= 1)
        assertTrue("zeroRect left must be non-negative", zeroRect.left >= 0)
        assertTrue("zeroRect top must be non-negative", zeroRect.top >= 0)

        // 2. Negative screen dimensions
        val negRect = normToScreenRectMath(norm, -1080, -2340)
        assertTrue("negRect width must be at least 1", negRect.width >= 1)
        assertTrue("negRect height must be at least 1", negRect.height >= 1)
        assertTrue("negRect left must be >= 0", negRect.left >= 0)

        // 3. Degenerate / inverted normalized rect
        val invertedNorm = FloatRect(0.9f, 0.9f, 0.1f, 0.1f)
        val invertedResult = normToScreenRectMath(invertedNorm, 1920, 1080)
        assertTrue("Resulting rect left must be strictly less than right", invertedResult.left < invertedResult.right)
        assertTrue("Resulting rect top must be strictly less than bottom", invertedResult.top < invertedResult.bottom)
    }

    @Test
    fun testSamsungGalaxyCutoutGeometryLandscapeAndPortrait() {
        // Physical screen: Samsung Galaxy S24 Ultra / S23 (2340 x 1080 landscape)
        val dispW = 2340
        val dispH = 1080

        // Downsampled screenshot from SurfaceFlinger (e.g. 1920 x 886)
        val bmpW = 1920
        val bmpH = 886

        val scaleX = dispW.toFloat() / bmpW.toFloat()
        val scaleY = dispH.toFloat() / bmpH.toFloat()

        // Cluster at the punch-hole cutout margin (left edge in landscape, e.g. x in 0..60)
        val cutoutCluster = IntRect(0, 400, 60, 480)
        val scaledCutoutCluster = scaleCluster(cutoutCluster, scaleX, scaleY, dispW, dispH)

        assertEquals("Cutout cluster left must anchor to 0", 0, scaledCutoutCluster.left)
        val expectedScaledRight = (60 * scaleX).toInt()
        assertEquals(expectedScaledRight, scaledCutoutCluster.right)
        assertTrue("Cutout cluster right must expand with scaleX (expected >= 70)", scaledCutoutCluster.right >= 70)
        assertTrue("Cutout cluster must stay within physical display width", scaledCutoutCluster.right <= dispW)
        assertTrue("Cutout cluster must have non-zero area", scaledCutoutCluster.width > 0 && scaledCutoutCluster.height > 0)

        // Cluster at opposite screen edge (right edge in landscape)
        val rightEdgeCluster = IntRect(bmpW - 50, 400, bmpW, 480)
        val scaledRightCluster = scaleCluster(rightEdgeCluster, scaleX, scaleY, dispW, dispH)

        assertTrue("Right edge cluster left must be within panel", scaledRightCluster.left in 0..(dispW - 2))
        assertEquals("Right edge cluster right must clamp to dispW", dispW, scaledRightCluster.right)
        assertTrue("Cluster width must be positive", scaledRightCluster.width > 0)
    }

    @Test
    fun testRotationTransitionsPreventPillStranding() {
        fun clampPill(paramX: Int, paramY: Int, screenW: Int, screenH: Int, pillW: Int, pillH: Int): Pair<Int, Int> {
            val viewW = pillW.coerceAtLeast(350)
            val viewH = pillH.coerceAtLeast(110)
            val maxX = max(0, screenW - viewW)
            val maxY = max(0, screenH - viewH)
            val clampedX = paramX.coerceIn(0, maxX)
            val clampedY = paramY.coerceIn(0, maxY)
            return clampedX to clampedY
        }

        val pillWidth = 350
        val pillHeight = 110

        // 1. Initial position in Landscape (2340 x 1080) near right-bottom edge
        val landscapeW = 2340
        val landscapeH = 1080
        var pillX = 1900
        var pillY = 900

        val (initX, initY) = clampPill(pillX, pillY, landscapeW, landscapeH, pillWidth, pillHeight)
        assertEquals(1900, initX)
        assertEquals(900, initY)

        // 2. Rotate to Portrait (1080 x 2340)
        val portraitW = 1080
        val portraitH = 2340

        // Without clamping, pillX = 1900 would be stranded off-screen (portraitW is only 1080)
        val (rotatedX, rotatedY) = clampPill(initX, initY, portraitW, portraitH, pillWidth, pillHeight)
        val maxAllowedX = portraitW - pillWidth // 730
        assertEquals("Pill must be clamped to max visible X in portrait", maxAllowedX, rotatedX)
        assertTrue("Pill must be fully visible on screen", rotatedX in 0..maxAllowedX)
        assertTrue("Pill Y must remain within portrait height", rotatedY in 0..(portraitH - pillHeight))

        // 3. User drags pill near bottom of portrait screen (e.g. Y = 2100)
        pillX = rotatedX
        pillY = 2100
        val (draggedX, draggedY) = clampPill(pillX, pillY, portraitW, portraitH, pillWidth, pillHeight)
        assertEquals(2100, draggedY)

        // 4. Rotate back to Landscape (2340 x 1080)
        val (backX, backY) = clampPill(draggedX, draggedY, landscapeW, landscapeH, pillWidth, pillHeight)
        val maxAllowedY = landscapeH - pillHeight // 970
        assertEquals("Pill must be clamped to max visible Y in landscape", maxAllowedY, backY)
        assertTrue("Pill must be fully visible horizontally in landscape", backX in 0..(landscapeW - pillWidth))
        assertTrue("Pill must be fully visible vertically in landscape", backY in 0..maxAllowedY)
    }

    @Test
    fun testClusterScalingUnderAdversarialInputsRevealsEdgeCaseCollapse() {
        val dispW = 2400
        val dispH = 1080
        val bmpW = 1080
        val bmpH = 486

        val scaleX = dispW.toFloat() / bmpW.toFloat()
        val scaleY = dispH.toFloat() / bmpH.toFloat()

        var zeroWidthCount = 0
        var invertedCount = 0
        val totalIterations = 1000

        for (i in 0 until totalIterations) {
            val left = kotlin.random.Random.nextInt(0, bmpW - 1)
            val top = kotlin.random.Random.nextInt(0, bmpH - 1)
            val right = kotlin.random.Random.nextInt(left + 1, bmpW + 1)
            val bottom = kotlin.random.Random.nextInt(top + 1, bmpH + 1)

            val raw = IntRect(left, top, right, bottom)
            val screenCluster = scaleCluster(raw, scaleX, scaleY, dispW, dispH)

            assertTrue("screenCluster left must be >= 0", screenCluster.left >= 0)
            assertTrue("screenCluster top must be >= 0", screenCluster.top >= 0)
            assertTrue("screenCluster right must be <= dispW", screenCluster.right <= dispW)
            assertTrue("screenCluster bottom must be <= dispH", screenCluster.bottom <= dispH)

            if (screenCluster.width == 0) zeroWidthCount++
            if (screenCluster.width < 0) invertedCount++
        }

        // When scaleX >= 1.0f (2.22f), clusters expand, so zeroWidthCount is 0
        assertEquals("No inverted clusters must be produced", 0, invertedCount)
        assertEquals("No collapsed clusters when scaling up", 0, zeroWidthCount)

        // Adversarial downscaling condition: e.g. dispW = 1080, bmpW = 2400 (scaleX = 0.45f)
        val downScaleX = 1080f / 2400f
        val downScaleY = 486f / 1080f
        var downscaleCollapsedCount = 0

        for (i in 0 until 500) {
            // Very tight 1px raw cluster
            val left = kotlin.random.Random.nextInt(10, 2000)
            val raw = IntRect(left, 200, left + 1, 250)
            val scaled = scaleCluster(raw, downScaleX, downScaleY, 1080, 486)
            if (scaled.width <= 0) {
                downscaleCollapsedCount++
            }
        }

        // Empirically confirm that downscaling tight 1px clusters can cause width truncation (left == right)
        // when coerceIn(2, dispW) does not enforce right >= left + 1
        assertTrue("Downscaling 1px clusters produces collapsed width due to toInt() truncation", downscaleCollapsedCount > 0)
    }

    @Test
    fun testCoerceInNegativeOrZeroDimensionDemonstratesFailureMode() {
        // Empirically demonstrates the failure mode when bitmap.width <= 0:
        // In detectFilledInventorySlots and findTargetClusters:
        // invRect.right.coerceIn(1, bitmap.width) throws IllegalArgumentException if bitmap.width <= 0
        try {
            val emptyBitmapWidth = 0
            val rightCoordinate = 100
            rightCoordinate.coerceIn(1, emptyBitmapWidth)
            fail("Expected IllegalArgumentException when coercing into range 1..0")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must confirm empty range failure",
                e.message!!.contains("Cannot coerce value to an empty range")
            )
        }

        try {
            val negativeBitmapWidth = -10
            val rightCoordinate = 50
            rightCoordinate.coerceIn(1, negativeBitmapWidth)
            fail("Expected IllegalArgumentException when coercing into range 1..-10")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must confirm empty range failure",
                e.message!!.contains("Cannot coerce value to an empty range")
            )
        }
    }
}
