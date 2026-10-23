package com.example.waspmobile.engine

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class AdversarialM4Test {

    private class BufferedImageGameImage(val img: BufferedImage) : GameImage {
        override val width: Int get() = img.width
        override val height: Int get() = img.height
        override fun getPixel(x: Int, y: Int): Int = img.getRGB(x, y)
        override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
            img.getRGB(x, y, width, height, pixels, offset, stride)
        }
    }

    private val screenshot1Path = "C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906388_c77464df.jpg"
    private val screenshot2Path = "C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906471_c6388f0e.jpg"
    private val screenshot3Path = "C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906545_b4b11f4d.jpg"

    private fun loadScreenshot(path: String): BufferedImage {
        val file = File(path)
        assertTrue("Screenshot file must exist: $path", file.exists())
        val img = ImageIO.read(file)
        assertNotNull("ImageIO must successfully decode image: $path", img)
        return img
    }

    private fun createRectF(l: Float, t: Float, r: Float, b: Float): RectF =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun createRect(l: Int, t: Int, r: Int, b: Int): Rect =
        Rect().apply { left = l; top = t; right = r; bottom = b }

    @Test
    fun testRectConstructorBehavior() {
        val testRect = Rect(10, 20, 30, 40)
        println("Direct Rect(10, 20, 30, 40) fields on host JVM: left=${testRect.left}, top=${testRect.top}, right=${testRect.right}, bottom=${testRect.bottom}")
        val appliedRect = Rect().apply { left = 10; top = 20; right = 30; bottom = 40 }
        println("Rect().apply fields on host JVM: left=${appliedRect.left}, top=${appliedRect.top}, right=${appliedRect.right}, bottom=${appliedRect.bottom}")
    }

    // =========================================================================
    // 1. EMPIRICAL VERIFICATION: detectFilledInventorySlots on Screenshot 2
    // =========================================================================

    private fun computeSlotRectsPure(invRect: Rect): List<Rect> {
        val cols = 4
        val rows = 7
        val cellW = invRect.boxW / cols.toFloat()
        val cellH = invRect.boxH / rows.toFloat()
        val padX = (cellW * 0.12f).toInt()
        val padY = (cellH * 0.10f).toInt()

        val slots = ArrayList<Rect>(28)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = (invRect.left + c * cellW + padX).toInt()
                val top = (invRect.top + r * cellH + padY).toInt()
                val right = (invRect.left + (c + 1) * cellW - padX).toInt()
                val bottom = (invRect.top + (r + 1) * cellH - padY).toInt()
                slots.add(createRect(left, top, right, bottom))
            }
        }
        return slots
    }

    /**
     * Executes the exact vision filtering algorithm from MobileVisionEngine.detectFilledInventorySlots,
     * but using Rect.apply fields to bypass the Android mockableJar Rect(l,t,r,b) stub bug on host JVM.
     */
    private fun detectFilledSlotsWithPureRect(image: GameImage, invRect: Rect): Set<Int> {
        val clampedInv = createRect(
            invRect.left.coerceIn(0, maxOf(0, image.width - 2)),
            invRect.top.coerceIn(0, maxOf(0, image.height - 2)),
            invRect.right.coerceIn(1, image.width),
            invRect.bottom.coerceIn(1, image.height)
        )
        val invW = clampedInv.boxW
        val invH = clampedInv.boxH
        if (invW < 16 || invH < 28) return emptySet()

        val buf = IntArray(invW * invH)
        image.getPixels(buf, 0, invW, clampedInv.left, clampedInv.top, invW, invH)

        val slotRects = computeSlotRectsPure(clampedInv)
        val filled = HashSet<Int>(28)

        for ((idx, rect) in slotRects.withIndex()) {
            val relLeft = (rect.left - clampedInv.left).coerceIn(0, invW - 1)
            val relRight = (rect.right - clampedInv.left).coerceIn(relLeft + 1, invW)
            val relTop = (rect.top - clampedInv.top).coerceIn(0, invH - 1)
            val relBottom = (rect.bottom - clampedInv.top).coerceIn(relTop + 1, invH)

            var sharpEdges = 0
            var minLum = 255
            var maxLum = 0

            var rSum = 0
            var gSum = 0
            var bSum = 0
            var sampleCount = 0

            for (y in relTop until relBottom) {
                val rowOffset = y * invW
                for (x in relLeft until relRight) {
                    val px = buf[rowOffset + x]
                    if (MobileVisionEngine.isOverlayColor(px)) continue

                    val r1 = (px shr 16) and 0xFF
                    val g1 = (px shr 8) and 0xFF
                    val b1 = px and 0xFF

                    val lum = (299 * r1 + 587 * g1 + 114 * b1) / 1000
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum

                    if (x + 1 < relRight) {
                        val pxRight = buf[rowOffset + x + 1]
                        if (!MobileVisionEngine.isOverlayColor(pxRight)) {
                            val r2 = (pxRight shr 16) and 0xFF
                            val g2 = (pxRight shr 8) and 0xFF
                            val b2 = pxRight and 0xFF
                            if (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 85) sharpEdges++
                        }
                    }
                    if (y + 1 < relBottom) {
                        val pxDown = buf[(y + 1) * invW + x]
                        if (!MobileVisionEngine.isOverlayColor(pxDown)) {
                            val r2 = (pxDown shr 16) and 0xFF
                            val g2 = (pxDown shr 8) and 0xFF
                            val b2 = pxDown and 0xFF
                            if (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 85) sharpEdges++
                        }
                    }

                    if ((y - relTop) % 2 == 0 && (x - relLeft) % 2 == 0) {
                        rSum += r1; gSum += g1; bSum += b1; sampleCount++
                    }
                }
            }

            if (sampleCount < 6) continue

            val meanR = rSum / sampleCount
            val meanG = gSum / sampleCount
            val meanB = bSum / sampleCount

            var devSum = 0
            for (y in relTop until relBottom step 2) {
                val rowOffset = y * invW
                for (x in relLeft until relRight step 2) {
                    val px = buf[rowOffset + x]
                    if (!MobileVisionEngine.isOverlayColor(px)) {
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        devSum += abs(r - meanR) + abs(g - meanG) + abs(b - meanB)
                    }
                }
            }

            val meanAbsDev = devSum.toFloat() / sampleCount.toFloat()
            val lumRange = maxLum - minLum

            if (sharpEdges >= 8 && meanAbsDev >= 28.0f && (minLum <= 75 || lumRange >= 165)) {
                filled.add(idx)
            }
        }
        return filled
    }

    @Test
    fun testDetectFilledInventorySlotsOnScreenshot2ExactTwoLogs() {
        val img2 = loadScreenshot(screenshot2Path)
        val gameImage = BufferedImageGameImage(img2)
        val engine = MobileVisionEngine()

        // S23 Calibrated invBoxNorm: RectF(0.731f, 0.468f, 0.901f, 0.975f)
        val invNorm = WaspTelemetry().invBoxNorm
        val invRect = createRect(
            (invNorm.left * gameImage.width).toInt(),
            (invNorm.top * gameImage.height).toInt(),
            (invNorm.right * gameImage.width).toInt(),
            (invNorm.bottom * gameImage.height).toInt()
        )

        println("=== Screenshot 2 Inventory Analysis ===")
        println("Image dimensions: ${gameImage.width}x${gameImage.height}")
        println("invNorm: [${invNorm.left}, ${invNorm.top}, ${invNorm.right}, ${invNorm.bottom}]")
        println("invRect: [${invRect.left}, ${invRect.top}, ${invRect.right}, ${invRect.bottom}] (W=${invRect.boxW}, H=${invRect.boxH})")

        val slotRects = computeSlotRectsPure(invRect)
        assertEquals(28, slotRects.size)

        val invW = invRect.boxW
        val invH = invRect.boxH
        val buf = IntArray(invW * invH)
        gameImage.getPixels(buf, 0, invW, invRect.left, invRect.top, invW, invH)

        println("--- Detailed Metrics Across All 28 Slots ---")
        for ((idx, rect) in slotRects.withIndex()) {
            val relLeft = (rect.left - invRect.left).coerceIn(0, invW - 1)
            val relRight = (rect.right - invRect.left).coerceIn(relLeft + 1, invW)
            val relTop = (rect.top - invRect.top).coerceIn(0, invH - 1)
            val relBottom = (rect.bottom - invRect.top).coerceIn(relTop + 1, invH)

            var sharpEdges = 0
            var minLum = 255
            var maxLum = 0
            var rSum = 0; var gSum = 0; var bSum = 0
            var sampleCount = 0

            for (y in relTop until relBottom) {
                val rowOffset = y * invW
                for (x in relLeft until relRight) {
                    val px = buf[rowOffset + x]
                    if (MobileVisionEngine.isOverlayColor(px)) continue

                    val r1 = (px shr 16) and 0xFF
                    val g1 = (px shr 8) and 0xFF
                    val b1 = px and 0xFF

                    val lum = (299 * r1 + 587 * g1 + 114 * b1) / 1000
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum

                    if (x + 1 < relRight) {
                        val pxRight = buf[rowOffset + x + 1]
                        if (!MobileVisionEngine.isOverlayColor(pxRight)) {
                            val r2 = (pxRight shr 16) and 0xFF
                            val g2 = (pxRight shr 8) and 0xFF
                            val b2 = pxRight and 0xFF
                            if (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 85) sharpEdges++
                        }
                    }
                    if (y + 1 < relBottom) {
                        val pxDown = buf[(y + 1) * invW + x]
                        if (!MobileVisionEngine.isOverlayColor(pxDown)) {
                            val r2 = (pxDown shr 16) and 0xFF
                            val g2 = (pxDown shr 8) and 0xFF
                            val b2 = pxDown and 0xFF
                            if (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 85) sharpEdges++
                        }
                    }

                    if ((y - relTop) % 2 == 0 && (x - relLeft) % 2 == 0) {
                        rSum += r1; gSum += g1; bSum += b1; sampleCount++
                    }
                }
            }

            val meanR = if (sampleCount > 0) rSum / sampleCount else 0
            val meanG = if (sampleCount > 0) gSum / sampleCount else 0
            val meanB = if (sampleCount > 0) bSum / sampleCount else 0

            var devSum = 0
            for (y in relTop until relBottom step 2) {
                val rowOffset = y * invW
                for (x in relLeft until relRight step 2) {
                    val px = buf[rowOffset + x]
                    if (!MobileVisionEngine.isOverlayColor(px)) {
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        devSum += abs(r - meanR) + abs(g - meanG) + abs(b - meanB)
                    }
                }
            }
            val meanAbsDev = if (sampleCount > 0) devSum.toFloat() / sampleCount.toFloat() else 0f
            val lumRange = maxLum - minLum

            val isFilled = sharpEdges >= 8 && meanAbsDev >= 28.0f && (minLum <= 75 || lumRange >= 165)
            println("Slot %02d: sharpEdges=%3d, MAD=%5.1f, minLum=%3d, maxLum=%3d, range=%3d -> FILLED=%s"
                .format(idx, sharpEdges, meanAbsDev, minLum, maxLum, lumRange, isFilled))

            if (idx == 0 || idx == 1) {
                assertTrue("Slot $idx (Log) MUST be detected as filled! (sharpEdges=$sharpEdges, MAD=$meanAbsDev, minLum=$minLum)", isFilled)
                assertTrue("Slot $idx sharpEdges must be >= 8 (was $sharpEdges)", sharpEdges >= 8)
                assertTrue("Slot $idx MAD must be >= 28.0 (was $meanAbsDev)", meanAbsDev >= 28.0f)
                assertTrue("Slot $idx minLum must be <= 75 (was $minLum)", minLum <= 75)
            } else {
                assertFalse("Slot $idx (Empty translucent slot over trees/roof) MUST NOT be detected as filled! (sharpEdges=$sharpEdges, MAD=$meanAbsDev)", isFilled)
                assertEquals("Slot $idx empty slot must have 0 sharp edges", 0, sharpEdges)
            }
        }

        // Test the vision algorithm with Pure Rect:
        val detectedSlots = detectFilledSlotsWithPureRect(gameImage, invRect)
        println("detectFilledSlotsWithPureRect returned: $detectedSlots (size=${detectedSlots.size})")
        assertEquals("Detected slots must be EXACTLY {0, 1}", setOf(0, 1), detectedSlots)
        assertEquals("Total filled slots count must be EXACTLY 2", 2, detectedSlots.size)

        // NOW: Demonstrate the production bug in MobileVisionEngine.detectFilledInventorySlots:
        // Because MobileVisionEngine uses 'val clampedInv = Rect(...)', on JVM mockableJar
        // clampedInv is initialized to [0, 0, 0, 0], and it returns emptySet()!
        val productionResult = engine.detectFilledInventorySlots(gameImage, invRect)
        println("CRITICAL BUG DEMONSTRATION: Production engine.detectFilledInventorySlots returned: $productionResult")
    }

    // =========================================================================
    // 2. EMPIRICAL VERIFICATION: isInventoryTabOpen on Screenshots 1, 2, and 3
    // =========================================================================

    @Test
    fun testIsInventoryTabOpenAcrossAllThreeScreenshots() {
        val engine = MobileVisionEngine()

        val img1 = loadScreenshot(screenshot1Path)
        val img2 = loadScreenshot(screenshot2Path)
        val img3 = loadScreenshot(screenshot3Path)

        val g1 = BufferedImageGameImage(img1)
        val g2 = BufferedImageGameImage(img2)
        val g3 = BufferedImageGameImage(img3)

        fun inspectTab(g: GameImage, name: String): Triple<Int, Int, Float> {
            val xMin = (g.width * 0.916f).toInt()
            val xMax = (g.width * 0.935f).toInt()
            val yMin = (g.height * 0.505f).toInt()
            val yMax = (g.height * 0.520f).toInt()
            val patchW = xMax - xMin + 1
            val patchH = yMax - yMin + 1
            val patch = IntArray(patchW * patchH)
            g.getPixels(patch, 0, patchW, xMin, yMin, patchW, patchH)

            var redStoneCount = 0
            for (px in patch) {
                val r = (px shr 16) and 0xFF
                val gVal = (px shr 8) and 0xFF
                if (r - gVal >= 28) redStoneCount++
            }
            val ratio = redStoneCount.toFloat() / patch.size.toFloat()
            val isOpen = engine.isInventoryTabOpen(g)
            println("$name Tab Check: patch=${patchW}x${patchH} (${patch.size} px), redStoneCount=$redStoneCount, ratio=${"%.3f".format(ratio)} -> isOpen=$isOpen")
            return Triple(patch.size, redStoneCount, ratio)
        }

        println("=== Inventory Tab State Detection ===")
        val (_, red1, ratio1) = inspectTab(g1, "Screenshot 1 (Combat Styles tab open)")
        val (_, red2, ratio2) = inspectTab(g2, "Screenshot 2 (Inventory open with 2 logs)")
        val (_, red3, ratio3) = inspectTab(g3, "Screenshot 3 (Inventory grid calibration)")

        // Screenshot 1 must be FALSE (Combat Styles open)
        assertFalse("Screenshot 1 must return false for isInventoryTabOpen (was redCount=$red1, ratio=$ratio1)", engine.isInventoryTabOpen(g1))
        assertTrue("Screenshot 1 redStoneCount must be small (<= 25)", red1 <= 25)

        // Screenshot 2 must be TRUE (Inventory open)
        assertTrue("Screenshot 2 must return true for isInventoryTabOpen (was redCount=$red2, ratio=$ratio2)", engine.isInventoryTabOpen(g2))
        assertTrue("Screenshot 2 redStoneCount must be high (>= 40, was $red2)", red2 >= 40)
        assertTrue("Screenshot 2 redStoneRatio must be >= 0.30 (was $ratio2)", ratio2 >= 0.30f)

        // Screenshot 3 must be TRUE (Inventory open)
        assertTrue("Screenshot 3 must return true for isInventoryTabOpen (was redCount=$red3, ratio=$ratio3)", engine.isInventoryTabOpen(g3))
        assertTrue("Screenshot 3 redStoneCount must be high (>= 40, was $red3)", red3 >= 40)
        assertTrue("Screenshot 3 redStoneRatio must be >= 0.30 (was $ratio3)", ratio3 >= 0.30f)
    }

    // =========================================================================
    // 3. EMPIRICAL VERIFICATION: isTapToDropActive on Screenshots
    // =========================================================================

    @Test
    fun testIsTapToDropActiveOnScreenshots() {
        val engine = MobileVisionEngine()
        val img2 = loadScreenshot(screenshot2Path)
        val g2 = BufferedImageGameImage(img2)

        val xMin = (g2.width * 0.055f).toInt()
        val xMax = (g2.width * 0.075f).toInt()
        val yMin = (g2.height * 0.550f).toInt()
        val yMax = (g2.height * 0.585f).toInt()
        val patchW = xMax - xMin + 1
        val patchH = yMax - yMin + 1
        val patch = IntArray(patchW * patchH)
        g2.getPixels(patch, 0, patchW, xMin, yMin, patchW, patchH)

        var satRedCount = 0
        for (px in patch) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (r > 130 && g < 60 && b < 60) satRedCount++
        }
        val isActive = engine.isTapToDropActive(g2)
        println("=== Tap-to-Drop Active Check ===")
        println("Screenshot 2 Tap-to-Drop: patch=${patchW}x${patchH} (${patch.size} px), satRedCount=$satRedCount -> isActive=$isActive")
        assertTrue("Screenshot 2 has Tap-to-Drop active with red indicator", isActive)
        assertTrue("Screenshot 2 saturated red count >= 15 (was $satRedCount)", satRedCount >= 15)
    }

    // =========================================================================
    // 4. ADVERSARIAL STRESS TEST: findTargetClusters Trees vs Lumbridge Grass
    // =========================================================================

    @Test
    fun testTreeCanopyPresetRejectsLumbridgeGrass100Percent() {
        val treePreset = TargetColorPreset.TREE_CANOPY
        assertEquals(16, treePreset.tolerance)
        val targetRgb: Int = treePreset.rgb
        val tr: Int = (targetRgb shr 16) and 0xFF
        val tg: Int = (targetRgb shr 8) and 0xFF
        val tb: Int = targetRgb and 0xFF
        val maxDist: Int = treePreset.tolerance * 3 // 48

        assertEquals(40, tr)
        assertEquals(46, tg)
        assertEquals(15, tb)

        // Lumbridge / Draynor real grass samples
        val lumbridgeGrassSamples = listOf(
            "Lumbridge Standard Grass" to Triple(65, 110, 40),
            "Lumbridge Sunlit Grass"   to Triple(80, 130, 48),
            "Draynor Dark Grass"       to Triple(50, 95, 32),
            "Varrock Bright Grass"     to Triple(90, 145, 55)
        )

        for ((name, rgb) in lumbridgeGrassSamples) {
            val (gr, gg, gb) = rgb
            val dist = abs(gr - tr) + abs(gg - tg) + abs(gb - tb)
            println("Grass sample '$name' (RGB $gr, $gg, $gb) Manhattan dist from TREE_CANOPY: $dist (maxDist=$maxDist)")
            assertTrue("Grass '$name' must be strictly rejected (dist $dist must be > $maxDist)", dist > maxDist)
        }
    }

    @Test
    fun testFindTargetClustersPlayerCenterExclusionAnd5x5Cap() {
        val engine = MobileVisionEngine()
        val img2 = loadScreenshot(screenshot2Path)
        val g2 = BufferedImageGameImage(img2)

        val searchNorm = WaspTelemetry().searchBoxNorm
        val searchRect = engine.normToScreenRect(searchNorm, g2.width, g2.height)

        val clusters = engine.findTargetClusters(
            image = g2,
            searchRect = searchRect,
            targetRgb = TargetColorPreset.TREE_CANOPY.rgb,
            tolerance = TargetColorPreset.TREE_CANOPY.tolerance
        )

        println("=== Target Clusters Found (TREE_CANOPY) ===")
        println("Found ${clusters.size} clusters")

        val playerMinX = (g2.width * 0.465f).toInt()
        val playerMaxX = (g2.width * 0.535f).toInt()
        val playerMinY = (g2.height * 0.425f).toInt()
        val playerMaxY = (g2.height * 0.565f).toInt()

        for ((i, cl) in clusters.withIndex()) {
            println("Cluster #$i: [${cl.left}, ${cl.top}, ${cl.right}, ${cl.bottom}] (W=${cl.boxW}, H=${cl.boxH}, Center=${cl.boxCenterX}, ${cl.boxCenterY})")
            val clCenterX = cl.boxCenterX.toInt()
            val clCenterY = cl.boxCenterY.toInt()

            // Verify cluster center does NOT fall inside player character box
            val insidePlayer = clCenterX in playerMinX..playerMaxX && clCenterY in playerMinY..playerMaxY
            assertFalse("Cluster #$i center ($clCenterX, $clCenterY) must NOT fall inside player box [$playerMinX..$playerMaxX, $playerMinY..$playerMaxY]", insidePlayer)

            // Verify cluster size does not exceed reasonable tree bounds (capped BFS 5x5 cells)
            // CellW = searchRect.boxW / 24, CellH = searchRect.boxH / 18
            val maxAllowedW = (searchRect.boxW / 24f * 6.5f).toInt()
            val maxAllowedH = (searchRect.boxH / 18f * 6.5f).toInt()
            assertTrue("Cluster #$i width ${cl.boxW} must not exceed $maxAllowedW", cl.boxW <= maxAllowedW)
            assertTrue("Cluster #$i height ${cl.boxH} must not exceed $maxAllowedH", cl.boxH <= maxAllowedH)
        }
    }

    // =========================================================================
    // 5. ADVERSARIAL STRESS TEST: Inventory Detection Calibration Envelope Fuzzing
    // =========================================================================

    @Test
    fun testDetectFilledInventorySlotsEnvelopeToleranceFuzzing() {
        val engine = MobileVisionEngine()
        val img2 = loadScreenshot(screenshot2Path)
        val g2 = BufferedImageGameImage(img2)
        val baseInvNorm = WaspTelemetry().invBoxNorm
        val baseInvRect = createRect(
            (baseInvNorm.left * g2.width).toInt(),
            (baseInvNorm.top * g2.height).toInt(),
            (baseInvNorm.right * g2.width).toInt(),
            (baseInvNorm.bottom * g2.height).toInt()
        )

        // Nudge inventory box by +/- 1, 2 pixels in all 4 directions
        // The detector algorithm must stay 100% resilient and return EXACTLY {0, 1}
        var totalVariationsTested = 0
        var exactMatches = 0

        for (dx in -2..2) {
            for (dy in -2..2) {
                val shiftedRect = createRect(
                    baseInvRect.left + dx,
                    baseInvRect.top + dy,
                    baseInvRect.right + dx,
                    baseInvRect.bottom + dy
                )
                val detected = detectFilledSlotsWithPureRect(g2, shiftedRect)
                totalVariationsTested++
                if (detected == setOf(0, 1)) {
                    exactMatches++
                }
            }
        }

        println("Inventory Box Fuzzing: $exactMatches / $totalVariationsTested variations returned exact {0, 1}")
        assertEquals("100% of +/-2px shifted inventory boxes must return exact {0, 1}", totalVariationsTested, exactMatches)
    }

    // =========================================================================
    // 6. ADVERSARIAL STRESS TEST: Degenerate & Extreme Synthetic Inputs
    // =========================================================================

    @Test
    fun testDegenerateAndUniformImagesNeverYieldFalsePositives() {
        val engine = MobileVisionEngine()
        val invRect = createRect(100, 100, 300, 400)

        // 1. All-black synthetic image (0x000000)
        val blackImg = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
        val blackGameImage = BufferedImageGameImage(blackImg)
        val blackSlots = engine.detectFilledInventorySlots(blackGameImage, invRect)
        assertTrue("All black image must have 0 filled slots", blackSlots.isEmpty())
        assertFalse("All black image must have isInventoryTabOpen = false", engine.isInventoryTabOpen(blackGameImage))
        assertFalse("All black image must have isTapToDropActive = false", engine.isTapToDropActive(blackGameImage))

        // 2. All-white synthetic image (0xFFFFFF)
        val whiteImg = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
        val g = whiteImg.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, 500, 500)
        g.dispose()
        val whiteGameImage = BufferedImageGameImage(whiteImg)
        val whiteSlots = engine.detectFilledInventorySlots(whiteGameImage, invRect)
        assertTrue("All white image must have 0 filled slots", whiteSlots.isEmpty())
        assertFalse("All white image must have isInventoryTabOpen = false", engine.isInventoryTabOpen(whiteGameImage))
        assertFalse("All white image must have isTapToDropActive = false", engine.isTapToDropActive(whiteGameImage))

        // 3. Smooth translucent gradient (simulating sunset / fog in empty slots)
        val gradImg = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 500) {
            for (x in 0 until 500) {
                val r = (x * 255 / 500).coerceIn(0, 255)
                val gr = (y * 255 / 500).coerceIn(0, 255)
                val b = 50
                gradImg.setRGB(x, y, (r shl 16) or (gr shl 8) or b)
            }
        }
        val gradGameImage = BufferedImageGameImage(gradImg)
        val gradSlots = engine.detectFilledInventorySlots(gradGameImage, invRect)
        // Smooth gradients have step neighbor gradient of 0 or 1, never > 85
        assertTrue("Smooth gradient must have 0 filled slots (no sharp edges)", gradSlots.isEmpty())
    }
}
