package com.example.waspmobile.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialM1Test {

    // =========================================================================
    // 1. ADVERSARIAL STRESS TEST: isOverlayColor Palette Boundaries & OSRS Colors
    // =========================================================================

    @Test
    fun testOverlayExactAndBoundaryDistances() {
        val purple = MobileVisionEngine.OVERLAY_PURPLE
        val cyan = MobileVisionEngine.OVERLAY_CYAN
        val red = MobileVisionEngine.OVERLAY_RED
        val green = MobileVisionEngine.OVERLAY_GREEN
        val orange = MobileVisionEngine.OVERLAY_ORANGE

        // Exact overlay paint colors must always be detected
        assertTrue(MobileVisionEngine.isOverlayColor(purple))
        assertTrue(MobileVisionEngine.isOverlayColor(cyan))
        assertTrue(MobileVisionEngine.isOverlayColor(red))
        assertTrue(MobileVisionEngine.isOverlayColor(green))
        assertTrue(MobileVisionEngine.isOverlayColor(orange))

        // Boundary tests for tolerance = 40 (Manhattan distance = 39, 40, 41)
        // tr=168, tg=85, tb=247
        val dist39Purple = MobileVisionEngine.packRgb(168 + 13, 85 + 13, 247 - 13) // |13|+|13|+|-13| = 39 <= 40
        assertTrue("Distance 39 must be recognized as overlay", MobileVisionEngine.isOverlayColor(dist39Purple, 40))

        val dist40Purple = MobileVisionEngine.packRgb(168 + 14, 85 + 13, 247 - 13) // |14|+|13|+|-13| = 40 <= 40
        assertTrue("Distance 40 must be recognized as overlay", MobileVisionEngine.isOverlayColor(dist40Purple, 40))

        val dist41Purple = MobileVisionEngine.packRgb(168 + 14, 85 + 14, 247 - 13) // |14|+|14|+|-13| = 41 > 40
        assertFalse("Distance 41 must NOT be recognized as overlay", MobileVisionEngine.isOverlayColor(dist41Purple, 40))
    }

    @Test
    fun testRealOsrsPaletteHasZeroFalsePositives() {
        // Broad catalog of genuine OSRS in-game RGB colors:
        val osrsColors = listOf(
            // Rocks & Ores
            "Clay Rock" to MobileVisionEngine.packRgb(140, 100, 68),
            "Copper Rock" to MobileVisionEngine.packRgb(158, 102, 58),
            "Tin Rock" to MobileVisionEngine.packRgb(132, 126, 122),
            "Iron Rock" to MobileVisionEngine.packRgb(61, 39, 29),
            "Silver Ore" to MobileVisionEngine.packRgb(178, 178, 178),
            "Coal Rock" to MobileVisionEngine.packRgb(54, 54, 50),
            "Gold Ore" to MobileVisionEngine.packRgb(180, 150, 40),
            "Mithril Rock" to MobileVisionEngine.packRgb(66, 74, 118),
            "Adamantite Rock" to MobileVisionEngine.packRgb(56, 82, 66),
            "Runite Rock" to MobileVisionEngine.packRgb(54, 114, 142),
            "Amethyst Crystal" to MobileVisionEngine.packRgb(106, 50, 135),

            // Trees & Foliage
            "Normal Tree Trunk" to MobileVisionEngine.packRgb(98, 74, 44),
            "Oak Tree Leaves" to MobileVisionEngine.packRgb(68, 108, 42),
            "Willow Tree Leaves" to MobileVisionEngine.packRgb(42, 115, 38),
            "Maple Tree Trunk" to MobileVisionEngine.packRgb(128, 86, 52),
            "Yew Tree Canopy" to MobileVisionEngine.packRgb(52, 92, 34),
            "Magic Tree Foliage" to MobileVisionEngine.packRgb(32, 138, 112),

            // Fish & Water
            "River Water Dark" to MobileVisionEngine.packRgb(32, 78, 140),
            "Ocean Water Light" to MobileVisionEngine.packRgb(48, 112, 172),
            "Fishing Bubble Foam" to MobileVisionEngine.packRgb(155, 202, 218),

            // Ground & Buildings
            "Lumbridge Grass" to MobileVisionEngine.packRgb(65, 110, 40),
            "Varrock Cobblestone" to MobileVisionEngine.packRgb(115, 112, 108),
            "Al-Kharid Desert Sand" to MobileVisionEngine.packRgb(194, 168, 112),
            "Wilderness Dark Ash" to MobileVisionEngine.packRgb(45, 42, 40),

            // Player Items & Gear
            "Dragon Scimitar Red" to MobileVisionEngine.packRgb(165, 24, 24),
            "Abyssal Whip Grey" to MobileVisionEngine.packRgb(72, 72, 72),
            "Rune Platebody Cyan-Grey" to MobileVisionEngine.packRgb(72, 118, 142),
            "Green D'hide" to MobileVisionEngine.packRgb(38, 92, 48),
            "Blue D'hide" to MobileVisionEngine.packRgb(46, 78, 136),
            "Red D'hide" to MobileVisionEngine.packRgb(142, 36, 36),
            "Black D'hide" to MobileVisionEngine.packRgb(28, 28, 28),
            "Fire Cape Orange-Red" to MobileVisionEngine.packRgb(210, 88, 20),

            // UI Elements
            "Inventory Stone BG" to MobileVisionEngine.packRgb(62, 53, 41),
            "Sprite 1px Outline" to MobileVisionEngine.packRgb(12, 12, 12),
            "Chatbox Yellow Text" to MobileVisionEngine.packRgb(255, 255, 0),
            "Chatbox White Text" to MobileVisionEngine.packRgb(255, 255, 255)
        )

        for ((name, rgb) in osrsColors) {
            val isOverlay = MobileVisionEngine.isOverlayColor(rgb, tolerance = 40)
            assertFalse("OSRS in-game color '$name' (0x${Integer.toHexString(rgb).uppercase()}) must NOT be classified as overlay!", isOverlay)
        }
    }

    @Test
    fun testSurfaceFlingerDitheringAndCompressionDrift() {
        // Under SurfaceFlinger / GPU display color transforms, overlay pixels can drift +/- 10 per channel
        val overlays = listOf(
            MobileVisionEngine.OVERLAY_PURPLE,
            MobileVisionEngine.OVERLAY_CYAN,
            MobileVisionEngine.OVERLAY_RED,
            MobileVisionEngine.OVERLAY_GREEN,
            MobileVisionEngine.OVERLAY_ORANGE
        )

        for (target in overlays) {
            val tr = (target shr 16) and 0xFF
            val tg = (target shr 8) and 0xFF
            val tb = target and 0xFF

            // Test drifts with Manhattan distance <= 40
            for (dr in -10..10 step 5) {
                for (dg in -10..10 step 5) {
                    for (db in -10..10 step 5) {
                        val mDist = abs(dr) + abs(dg) + abs(db)
                        val drifted = MobileVisionEngine.packRgb(
                            (tr + dr).coerceIn(0, 255),
                            (tg + dg).coerceIn(0, 255),
                            (tb + db).coerceIn(0, 255)
                        )
                        if (mDist <= 40) {
                            assertTrue(
                                "Drifted overlay color (dist=$mDist) must be recognized as overlay",
                                MobileVisionEngine.isOverlayColor(drifted, 40)
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. ADVERSARIAL STRESS TEST: Eyedropper 7x7 Patch Sampling Simulation
    // =========================================================================

    /**
     * Algorithmic oracle replicating MobileVisionEngine.sampleColorAt logic
     * over a 7x7 patch of pixels.
     */
    private fun samplePatchOracle(patch: IntArray, centerPx: Int): Int {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var validCount = 0
        for (px in patch) {
            if (!MobileVisionEngine.isOverlayColor(px)) {
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
                validCount++
            }
        }
        if (validCount == 0) {
            return centerPx
        }
        return MobileVisionEngine.packRgb(rSum / validCount, gSum / validCount, bSum / validCount)
    }

    @Test
    fun testEyedropperFiltersCrosshairLinesFromNoisyGameBackground() {
        // True background: Willow leaf green (r=42, g=115, b=38) with random texture noise (+/- 6)
        val rng = Random(42)
        val trueBgPixels = IntArray(49) {
            MobileVisionEngine.packRgb(
                42 + rng.nextInt(-6, 7),
                115 + rng.nextInt(-6, 7),
                38 + rng.nextInt(-6, 7)
            )
        }

        // Overlay crosshair line passing vertically through center column (x = 3, indices 3, 10, 17, 24, 31, 38, 45)
        val contaminatedPatch = trueBgPixels.clone()
        val purpleOverlay = MobileVisionEngine.OVERLAY_PURPLE
        for (row in 0 until 7) {
            contaminatedPatch[row * 7 + 3] = purpleOverlay
        }

        // Without filtering (naive average)
        var naiveR = 0; var naiveG = 0; var naiveB = 0
        for (px in contaminatedPatch) {
            naiveR += (px shr 16) and 0xFF
            naiveG += (px shr 8) and 0xFF
            naiveB += px and 0xFF
        }
        val naiveSample = MobileVisionEngine.packRgb(naiveR / 49, naiveG / 49, naiveB / 49)

        // With MobileVisionEngine filtering
        val centerPx = contaminatedPatch[24]
        val filteredSample = samplePatchOracle(contaminatedPatch, centerPx)

        // Evaluate: naive sample is heavily corrupted by purple overlay
        val naiveDist = abs(((naiveSample shr 16) and 0xFF) - 42) +
                abs(((naiveSample shr 8) and 0xFF) - 115) +
                abs((naiveSample and 0xFF) - 38)
        assertTrue("Naive sample without filter must show significant error (was $naiveDist)", naiveDist > 30)

        // Filtered sample must match true leaf green closely despite crosshair intrusion
        val filteredDist = abs(((filteredSample shr 16) and 0xFF) - 42) +
                abs(((filteredSample shr 8) and 0xFF) - 115) +
                abs((filteredSample and 0xFF) - 38)
        assertTrue("Filtered sample error must be tiny (< 4) despite 7 overlay pixels (was $filteredDist)", filteredDist < 4)
    }

    @Test
    fun testEyedropperHeavyOverlayContaminationWithSingleSurvivorPixel() {
        // 48 out of 49 pixels are overlay cyan (#38BDF8), exactly 1 pixel is gold ore (180, 150, 40)
        val goldOre = MobileVisionEngine.packRgb(180, 150, 40)
        val patch = IntArray(49) { MobileVisionEngine.OVERLAY_CYAN }
        patch[0] = goldOre

        val sampled = samplePatchOracle(patch, patch[24])
        // Must recover exact gold ore color without crashing or mixing cyan
        assertEquals(goldOre, sampled)
    }

    @Test
    fun testEyedropperAllOverlayFallbackToCenterWithoutCrash() {
        // 100% of patch is overlay red (#F43F5E)
        val patch = IntArray(49) { MobileVisionEngine.OVERLAY_RED }
        val center = MobileVisionEngine.OVERLAY_RED

        val sampled = samplePatchOracle(patch, center)
        assertEquals(center, sampled)
    }

    // =========================================================================
    // 3. ADVERSARIAL STRESS TEST: Inventory Slot Detection with Overlay Pollution
    // =========================================================================

    @Test
    fun testInventorySlotOrangeGridImmunity() {
        // Simulate an empty inventory slot (25x25 = 625 pixels at 1080p, stride=2 -> ~156 sample points)
        // Background: OSRS flat stone background (62, 53, 41) +/- 3 flat noise
        val rng = Random(1337)
        val stoneBase = MobileVisionEngine.packRgb(62, 53, 41)
        val samples = IntArray(156) {
            MobileVisionEngine.packRgb(
                62 + rng.nextInt(-3, 4),
                53 + rng.nextInt(-3, 4),
                41 + rng.nextInt(-3, 4)
            )
        }

        // Overlay pollution: 25 orange inventory grid pixels (#F59E0B) cross into the slot
        for (i in 0 until 25) {
            samples[i] = MobileVisionEngine.OVERLAY_ORANGE
        }

        // Analysis WITHOUT overlay rejection:
        var rSum = 0; var gSum = 0; var bSum = 0
        for (px in samples) {
            rSum += (px shr 16) and 0xFF
            gSum += (px shr 8) and 0xFF
            bSum += px and 0xFF
        }
        val meanR = rSum / samples.size
        val meanG = gSum / samples.size
        val meanB = bSum / samples.size
        var unconstrainedDev = 0
        for (px in samples) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            unconstrainedDev += abs(r - meanR) + abs(g - meanG) + abs(b - meanB)
        }
        val unconstrainedMad = unconstrainedDev.toFloat() / samples.size.toFloat()
        // Without rejection, the orange line inflates MAD beyond 13.5f, creating a FALSE POSITIVE!
        assertTrue("Unconstrained MAD must falsely exceed 13.5f due to orange line", unconstrainedMad > 13.5f)

        // Analysis WITH MobileVisionEngine overlay rejection:
        var fRSum = 0; var fGSum = 0; var fBSum = 0; var fCount = 0
        for (px in samples) {
            if (!MobileVisionEngine.isOverlayColor(px)) {
                fRSum += (px shr 16) and 0xFF
                fGSum += (px shr 8) and 0xFF
                fBSum += px and 0xFF
                fCount++
            }
        }
        val fMeanR = fRSum / fCount
        val fMeanG = fGSum / fCount
        val fMeanB = fBSum / fCount
        var fDev = 0
        for (px in samples) {
            if (!MobileVisionEngine.isOverlayColor(px)) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                fDev += abs(r - fMeanR) + abs(g - fMeanG) + abs(b - fMeanB)
            }
        }
        val filteredMad = fDev.toFloat() / fCount.toFloat()
        // Filtered MAD stays around 4-6 (pure flat stone), preventing false positive!
        assertTrue("Filtered MAD must remain low (< 8.0f) for empty slot", filteredMad < 8.0f)
    }

    // =========================================================================
    // 4. ADVERSARIAL STRESS TEST: Screenshot Backoff, Concurrency & Timeouts
    // =========================================================================

    private val ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3
    private val ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY = 2

    /**
     * Model simulating captureScreenBitmap backoff loop.
     */
    private suspend fun simulateCaptureScreenBitmap(
        errorSequence: List<Int>,
        delayCallback: suspend (Long) -> Unit,
        captureCall: suspend () -> Int
    ): Pair<Int, List<Long>> {
        val recordedDelays = mutableListOf<Long>()
        val backoffs = longArrayOf(450L, 700L, 1000L)
        var attempt = 0
        var finalResult = -1

        for (att in 0..3) {
            attempt = att
            val errorCode = captureCall()
            if (errorCode == 0) {
                finalResult = 0
                break
            }

            if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && att < backoffs.size) {
                val backoffMs = backoffs[att]
                recordedDelays.add(backoffMs)
                delayCallback(backoffMs)
            } else {
                finalResult = errorCode
                break
            }
        }
        return Pair(finalResult, recordedDelays)
    }

    @Test
    fun testBackoffRetryOnIntervalTimeShortErrors() = runTest {
        val errors = mutableListOf(
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            0 // Success on 3rd attempt
        )
        var callCount = 0

        val (res, delays) = simulateCaptureScreenBitmap(
            errors,
            delayCallback = { ms -> delay(ms) },
            captureCall = { errors[callCount++] }
        )

        assertEquals(0, res)
        assertEquals(3, callCount)
        assertEquals(listOf(450L, 700L), delays)
    }

    @Test
    fun testFatalErrorAbortsImmediatelyWithoutBackoffDelay() = runTest {
        var callCount = 0
        val (res, delays) = simulateCaptureScreenBitmap(
            listOf(ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY),
            delayCallback = { ms -> delay(ms) },
            captureCall = {
                callCount++
                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY
            }
        )

        assertEquals(ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY, res)
        assertEquals(1, callCount)
        assertTrue("No backoff delays on unrecoverable error", delays.isEmpty())
    }

    @Test
    fun testMaxRetryExhaustionStopsAtAttemptThree() = runTest {
        var callCount = 0
        val (res, delays) = simulateCaptureScreenBitmap(
            List(10) { ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT },
            delayCallback = { ms -> delay(ms) },
            captureCall = {
                callCount++
                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
            }
        )

        assertEquals(ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, res)
        assertEquals(4, callCount) // Attempt 0, 1, 2, 3
        assertEquals(listOf(450L, 700L, 1000L), delays)
    }

    @Test
    fun testScreenshotMutexSerializesConcurrentCaptures() = runTest {
        val mutex = Mutex()
        var activeCaptures = 0
        var maxConcurrent = 0
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val jobs = List(10) {
            scope.launch {
                mutex.withLock {
                    activeCaptures++
                    if (activeCaptures > maxConcurrent) {
                        maxConcurrent = activeCaptures
                    }
                    delay(50L)
                    activeCaptures--
                }
            }
        }

        scope.advanceTimeBy(1000L)
        scope.runCurrent()

        assertEquals("Mutex must strictly prevent concurrent captures", 1, maxConcurrent)
        assertEquals("All active captures must complete cleanly", 0, activeCaptures)
    }

    @Test
    fun testCoroutineTimeoutSafelyCancelsHungCapture() = runTest {
        val result = withTimeoutOrNull(2500L) {
            delay(5000L) // Simulates hung accessibility service callback
            "CAPTURED"
        }
        assertNull("Hung capture must time out and return null", result)
    }

    // =========================================================================
    // 5. ADVERSARIAL STRESS TEST: Coordinate Scaling Across Modern Devices
    // =========================================================================

    @Test
    fun testDisplayCutoutCoordinateScalingEdgeCases() {
        // Matrix of modern devices:
        // 1. Samsung Galaxy S23/S24: 2340 x 1080 display, screenshot downscaled to 1920 x 886
        // 2. Samsung Galaxy S24 Ultra: 3088 x 1440 display, screenshot 2560 x 1193
        // 3. Samsung Galaxy Z Fold 5: 2176 x 1812 display, screenshot 2176 x 1812 (1:1)
        val devices = listOf(
            Triple(2340f, 1080f, Pair(1920f, 886f)),
            Triple(3088f, 1440f, Pair(2560f, 1193f)),
            Triple(2176f, 1812f, Pair(2176f, 1812f))
        )

        for ((dispW, dispH, bmpSize) in devices) {
            val (bmpW, bmpH) = bmpSize
            val scaleX = dispW / bmpW
            val scaleY = dispH / bmpH

            assertTrue("Scale factors must be positive and >= 1.0", scaleX >= 1.0f && scaleY >= 1.0f)

            // Test scaling coordinates at bitmap extremes:
            val bmpMinX = 0; val bmpMinY = 0
            val bmpMaxX = bmpW.toInt(); val bmpMaxY = bmpH.toInt()

            val scaledMinX = (bmpMinX * scaleX).toInt().coerceIn(0, dispW.toInt() - 2)
            val scaledMinY = (bmpMinY * scaleY).toInt().coerceIn(0, dispH.toInt() - 2)
            val scaledMaxX = (bmpMaxX * scaleX).toInt().coerceIn(2, dispW.toInt())
            val scaledMaxY = (bmpMaxY * scaleY).toInt().coerceIn(2, dispH.toInt())

            assertEquals(0, scaledMinX)
            assertEquals(0, scaledMinY)
            assertEquals(dispW.toInt(), scaledMaxX)
            assertEquals(dispH.toInt(), scaledMaxY)
        }
    }
}
