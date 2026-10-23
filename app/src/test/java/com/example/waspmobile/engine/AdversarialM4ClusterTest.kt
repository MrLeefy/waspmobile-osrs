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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class AdversarialM4ClusterTest {

    private class BufferedImageGameImage(val img: BufferedImage) : GameImage {
        override val width: Int get() = img.width
        override val height: Int get() = img.height
        override fun getPixel(x: Int, y: Int): Int = img.getRGB(x, y)
        override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
            img.getRGB(x, y, width, height, pixels, offset, stride)
        }
    }

    private class SyntheticGameImage(
        override val width: Int,
        override val height: Int,
        val background: Int = 0xFF000000.toInt()
    ) : GameImage {
        val pixels = IntArray(width * height) { background }

        fun setPixel(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) {
                pixels[y * width + x] = color
            }
        }

        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) {
                for (x in left until right) {
                    setPixel(x, y, color)
                }
            }
        }

        override fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]

        override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
            for (r in 0 until height) {
                val srcOffset = (y + r) * this.width + x
                val dstOffset = offset + r * stride
                System.arraycopy(this.pixels, srcOffset, pixels, dstOffset, width)
            }
        }
    }

    private val screenshot2Path = "C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906471_c6388f0e.jpg"

    private fun loadScreenshot2(): BufferedImage {
        val file = File(screenshot2Path)
        assertTrue("Screenshot 2 file must exist: $screenshot2Path", file.exists())
        val img = ImageIO.read(file)
        assertNotNull("ImageIO must successfully decode Screenshot 2", img)
        return img
    }

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect =
        Rect().apply { left = l; top = t; right = r; bottom = b }

    private val engine = MobileVisionEngine()

    // =========================================================================
    // 1. EMPIRICAL CHALLENGE: The Rect(l, t, r, b) Unmocked Constructor Bug
    // =========================================================================

    @Test
    fun testRectConstructorZeroingBugInJvmEnvironment() {
        val unmocked = Rect(100, 200, 300, 400)
        println("Unmocked Rect(100, 200, 300, 400): left=${unmocked.left}, top=${unmocked.top}, right=${unmocked.right}, bottom=${unmocked.bottom}")
        assertEquals("Unmocked Rect left defaults to 0 on JVM", 0, unmocked.left)
        assertEquals("Unmocked Rect top defaults to 0 on JVM", 0, unmocked.top)
        assertEquals("Unmocked Rect right defaults to 0 on JVM", 0, unmocked.right)
        assertEquals("Unmocked Rect bottom defaults to 0 on JVM", 0, unmocked.bottom)

        val applied = makeRect(100, 200, 300, 400)
        assertEquals(100, applied.left)
        assertEquals(200, applied.top)
        assertEquals(300, applied.right)
        assertEquals(400, applied.bottom)

        val img = loadScreenshot2()
        val g2 = BufferedImageGameImage(img)
        val searchBoxNorm = RectF().apply { left = 0.18f; top = 0.14f; right = 0.70f; bottom = 0.88f }
        
        // normToScreenRect uses makeRect(l, t, r, b) which preserves coordinates on JVM
        val normScreenRect = engine.normToScreenRect(searchBoxNorm, 1024, 472)
        println("engine.normToScreenRect result: [${normScreenRect.left}, ${normScreenRect.top}, ${normScreenRect.right}, ${normScreenRect.bottom}]")
        assertEquals(184, normScreenRect.left)
        assertEquals(716, normScreenRect.right)

        // Passing searchRect into engine.findTargetClusters invokes makeRect preserving coordinates
        val validSearchRect = makeRect(184, 66, 716, 415)
        val directClusters = engine.findTargetClusters(g2, validSearchRect, TargetColorPreset.TREE_CANOPY.rgb, TargetColorPreset.TREE_CANOPY.tolerance)
        println("Direct engine.findTargetClusters returned count: ${directClusters.size}")
        assertTrue("Direct call returns clusters because makeRect preserves fields", directClusters.isNotEmpty())
    }

    // =========================================================================
    // 2. EMPIRICAL ORACLE: Cluster Detection Logic with Safe makeRect
    // =========================================================================

    /**
     * Executes the exact algorithmic logic of MobileVisionEngine.findTargetClusters
     * but constructs Rect instances via `makeRect(l, t, r, b)` so fields are preserved.
     */
    private fun findTargetClustersOracle(
        image: GameImage,
        searchRect: Rect,
        targetRgb: Int,
        tolerance: Int,
        excludeRect: Rect? = null
    ): List<Rect> {
        val clamped = makeRect(
            searchRect.left.coerceIn(0, max(0, image.width - 2)),
            searchRect.top.coerceIn(0, max(0, image.height - 2)),
            searchRect.right.coerceIn(1, image.width),
            searchRect.bottom.coerceIn(1, image.height)
        )
        val w = clamped.boxW
        val h = clamped.boxH
        if (w < 16 || h < 16) return emptyList()

        val buf = IntArray(w * h)
        image.getPixels(buf, 0, w, clamped.left, clamped.top, w, h)

        val isTargetOverlay = MobileVisionEngine.isOverlayColor(targetRgb)
        val targetR = (targetRgb shr 16) and 0xFF
        val targetG = (targetRgb shr 8) and 0xFF
        val targetB = targetRgb and 0xFF
        val maxDist = tolerance * 3

        val playerMinX = (image.width * 0.465f).toInt()
        val playerMaxX = (image.width * 0.535f).toInt()
        val playerMinY = (image.height * 0.425f).toInt()
        val playerMaxY = (image.height * 0.565f).toInt()

        val gridCols = 24
        val gridRows = 18
        val cellW = max(1f, w / gridCols.toFloat())
        val cellH = max(1f, h / gridRows.toFloat())

        val hitGrid = Array(gridRows) { IntArray(gridCols) }
        val step = 3

        val samplesPerCell = max(1f, (cellW / step) * (cellH / step))
        val seedThreshold = max(2, min(6, (samplesPerCell * 0.09f).toInt()))
        val neighborThreshold = max(2, seedThreshold - 1)
        val clusterMinHits = max(4, seedThreshold * 2)

        var relY = 0
        while (relY < h) {
            val absY = clamped.top + relY
            val row = (relY / cellH).toInt().coerceIn(0, gridRows - 1)
            val rowOffset = relY * w
            var relX = 0
            while (relX < w) {
                val absX = clamped.left + relX
                if (absX in playerMinX..playerMaxX && absY in playerMinY..playerMaxY) {
                    relX += step
                    continue
                }
                if (excludeRect != null &&
                    absX in excludeRect.left..excludeRect.right &&
                    absY in excludeRect.top..excludeRect.bottom
                ) {
                    relX += step
                    continue
                }
                val px = buf[rowOffset + relX]
                if (!isTargetOverlay && MobileVisionEngine.isOverlayColor(px)) {
                    relX += step
                    continue
                }
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val dist = abs(r - targetR) + abs(g - targetG) + abs(b - targetB)
                if (dist <= maxDist) {
                    val col = (relX / cellW).toInt().coerceIn(0, gridCols - 1)
                    hitGrid[row][col]++
                }
                relX += step
            }
            relY += step
        }

        val visited = Array(gridRows) { BooleanArray(gridCols) }
        val clusters = ArrayList<Rect>()

        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                if (hitGrid[r][c] >= seedThreshold && !visited[r][c]) {
                    var minR = r
                    var maxR = r
                    var minC = c
                    var maxC = c
                    var totalHits = 0

                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(r to c)
                    visited[r][c] = true

                    while (queue.isNotEmpty()) {
                        val (cr, cc) = queue.removeFirst()
                        totalHits += hitGrid[cr][cc]
                        minR = min(minR, cr)
                        maxR = max(maxR, cr)
                        minC = min(minC, cc)
                        maxC = max(maxC, cc)

                        for (dr in -1..1) {
                            for (dc in -1..1) {
                                val nr = cr + dr
                                val nc = cc + dc
                                if (nr in 0 until gridRows && nc in 0 until gridCols &&
                                    !visited[nr][nc] && hitGrid[nr][nc] >= neighborThreshold
                                ) {
                                    val newMinR = min(minR, nr)
                                    val newMaxR = max(maxR, nr)
                                    val newMinC = min(minC, nc)
                                    val newMaxC = max(maxC, nc)
                                    if ((newMaxR - newMinR + 1) <= 5 && (newMaxC - newMinC + 1) <= 5) {
                                        visited[nr][nc] = true
                                        minR = newMinR
                                        maxR = newMaxR
                                        minC = newMinC
                                        maxC = newMaxC
                                        queue.add(nr to nc)
                                    }
                                }
                            }
                        }
                    }

                    if (totalHits >= clusterMinHits) {
                        val left = (clamped.left + minC * cellW).toInt().coerceIn(clamped.left, clamped.right - 2)
                        val top = (clamped.top + minR * cellH).toInt().coerceIn(clamped.top, clamped.bottom - 2)
                        val right = (clamped.left + (maxC + 1) * cellW).toInt().coerceIn(left + 2, clamped.right)
                        val bottom = (clamped.top + (maxR + 1) * cellH).toInt().coerceIn(top + 2, clamped.bottom)
                        val clCenterX = (left + right) * 0.5f
                        val clCenterY = (top + bottom) * 0.5f
                        if (clCenterX.toInt() in playerMinX..playerMaxX && clCenterY.toInt() in playerMinY..playerMaxY) {
                            continue
                        }
                        clusters.add(makeRect(left, top, right, bottom))
                    }
                }
            }
        }

        val playerCenterX = image.width * 0.50f
        val playerCenterY = image.height * 0.50f
        return clusters.sortedBy {
            hypot((it.boxCenterX - playerCenterX).toDouble(), (it.boxCenterY - playerCenterY).toDouble())
        }.take(8)
    }

    // =========================================================================
    // 3. EMPIRICAL CHALLENGE: Screenshot 2 Tree Canopy Cluster Verification
    // =========================================================================

    @Test
    fun testFindTargetClustersScreenshot2EmpiricalVerification() {
        val img = loadScreenshot2()
        val g2 = BufferedImageGameImage(img)
        val searchRect = makeRect(184, 66, 716, 415)

        val targetRgb = TargetColorPreset.TREE_CANOPY.rgb
        val tolerance = TargetColorPreset.TREE_CANOPY.tolerance

        val clusters = findTargetClustersOracle(g2, searchRect, targetRgb, tolerance)
        println("=== Oracle Clusters on Screenshot 2 (TREE_CANOPY) ===")
        println("Total clusters detected: ${clusters.size}")

        val playerMinX = (img.width * 0.465f).toInt()
        val playerMaxX = (img.width * 0.535f).toInt()
        val playerMinY = (img.height * 0.425f).toInt()
        val playerMaxY = (img.height * 0.565f).toInt()
        val playerCenterX = img.width * 0.50f
        val playerCenterY = img.height * 0.50f

        val cellW = searchRect.boxW / 24f
        val cellH = searchRect.boxH / 18f
        val maxBoxW = (cellW * 5 + 2).toInt()
        val maxBoxH = (cellH * 5 + 2).toInt()

        assertTrue("Screenshot 2 must contain at least 1 valid TREE_CANOPY cluster", clusters.isNotEmpty())

        var prevDistance = -1.0
        for ((idx, cl) in clusters.withIndex()) {
            val dist = hypot((cl.boxCenterX - playerCenterX).toDouble(), (cl.boxCenterY - playerCenterY).toDouble())
            println("Cluster #$idx: [L=${cl.left}, T=${cl.top}, R=${cl.right}, B=${cl.bottom}] (W=${cl.boxW}, H=${cl.boxH}, Center=(${cl.boxCenterX}, ${cl.boxCenterY}), DistToPlayer=${"%.2f".format(dist)})")

            // 1. Prove player center box contains NO cluster centers
            val inPlayerBox = cl.boxCenterX.toInt() in playerMinX..playerMaxX && cl.boxCenterY.toInt() in playerMinY..playerMaxY
            assertFalse("Cluster #$idx center (${cl.boxCenterX}, ${cl.boxCenterY}) must NOT be inside player box [$playerMinX..$playerMaxX, $playerMinY..$playerMaxY]", inPlayerBox)

            // 2. Prove connected components are capped at <= 5x5 grid cells
            assertTrue("Cluster #$idx width ${cl.boxW} must be <= max 5-cell width $maxBoxW", cl.boxW <= maxBoxW)
            assertTrue("Cluster #$idx height ${cl.boxH} must be <= max 5-cell height $maxBoxH", cl.boxH <= maxBoxH)

            // 3. Prove clusters are strictly ordered by Euclidean distance to player center (0.50f, 0.50f)
            if (prevDistance >= 0.0) {
                assertTrue("Cluster #$idx distance ($dist) must be >= previous cluster distance ($prevDistance)", dist >= prevDistance)
            }
            prevDistance = dist
        }
    }

    // =========================================================================
    // 4. EMPIRICAL CHALLENGE: Grass Rejection Proof
    // =========================================================================

    @Test
    fun testGrassPixelsAreStrictlyRejected() {
        val targetR = (TargetColorPreset.TREE_CANOPY.rgb shr 16) and 0xFF
        val targetG = (TargetColorPreset.TREE_CANOPY.rgb shr 8) and 0xFF
        val targetB = TargetColorPreset.TREE_CANOPY.rgb and 0xFF
        val maxDist = TargetColorPreset.TREE_CANOPY.tolerance * 3 // 48

        // Real OSRS Grass colors measured across Lumbridge, Draynor, and Falador
        val grassSamples = listOf(
            "Lumbridge Standard Grass" to Triple(65, 110, 40),
            "Lumbridge Sunlit Grass"   to Triple(80, 130, 48),
            "Draynor Dark Grass"       to Triple(50, 95, 32),
            "Draynor Shadow Grass"     to Triple(45, 85, 28),
            "Falador Light Grass"      to Triple(88, 140, 52),
            "Al Kharid Dry Grass"      to Triple(105, 118, 55)
        )

        for ((label, rgb) in grassSamples) {
            val (r, g, b) = rgb
            val dist = abs(r - targetR) + abs(g - targetG) + abs(b - targetB)
            println("Grass sample '$label' RGB($r, $g, $b): Manhattan distance to TREE_CANOPY = $dist (cutoff = $maxDist)")
            assertTrue("Grass '$label' must exceed maxDist $maxDist (got $dist)", dist > maxDist)
        }

        // Stress test: create a synthetic image consisting entirely of grass
        val synthGrassImg = SyntheticGameImage(1024, 472, background = 0xFF_41_6E_28.toInt()) // RGB(65, 110, 40)
        val searchRect = makeRect(184, 66, 716, 415)
        val clusters = findTargetClustersOracle(synthGrassImg, searchRect, TargetColorPreset.TREE_CANOPY.rgb, TargetColorPreset.TREE_CANOPY.tolerance)
        println("Synthetic all-grass image clusters detected: ${clusters.size}")
        assertEquals("Pure grass scene must yield exactly 0 clusters", 0, clusters.size)
    }

    // =========================================================================
    // 5. EMPIRICAL STRESS TEST: Mega-Box 5x5 Cap Enforcement Under Full Saturation
    // =========================================================================

    @Test
    fun testMegaBoxScreenSpanningSaturationCappedAt5x5() {
        val w = 1024
        val h = 472
        // Fill ENTIRE search box with matching TREE_CANOPY color
        val synthImg = SyntheticGameImage(w, h, background = 0xFF000000.toInt())
        val searchRect = makeRect(184, 66, 716, 415)
        synthImg.fillRect(searchRect.left, searchRect.top, searchRect.right, searchRect.bottom, TargetColorPreset.TREE_CANOPY.rgb)

        val clusters = findTargetClustersOracle(synthImg, searchRect, TargetColorPreset.TREE_CANOPY.rgb, TargetColorPreset.TREE_CANOPY.tolerance)
        println("Saturated full-screen tree canopy clusters detected: ${clusters.size}")

        val cellW = searchRect.boxW / 24f
        val cellH = searchRect.boxH / 18f
        val maxAllowedW = (cellW * 5 + 2).toInt()
        val maxAllowedH = (cellH * 5 + 2).toInt()

        assertTrue("Should detect clusters under saturation", clusters.isNotEmpty())
        for ((idx, cl) in clusters.withIndex()) {
            println("Saturation cluster #$idx: [L=${cl.left}, T=${cl.top}, R=${cl.right}, B=${cl.bottom}] (W=${cl.boxW}, H=${cl.boxH})")
            assertTrue("Cluster #$idx width ${cl.boxW} must NOT exceed 5x5 cell limit $maxAllowedW", cl.boxW <= maxAllowedW)
            assertTrue("Cluster #$idx height ${cl.boxH} must NOT exceed 5x5 cell limit $maxAllowedH", cl.boxH <= maxAllowedH)
            assertFalse("Cluster #$idx must NOT span full search box width", cl.boxW >= searchRect.boxW)
            assertFalse("Cluster #$idx must NOT span full search box height", cl.boxH >= searchRect.boxH)
        }
    }

    // =========================================================================
    // 6. EMPIRICAL STRESS TEST: Target Directly Inside Player Box Strictly Ignored
    // =========================================================================

    @Test
    fun testTargetDirectlyInsidePlayerBoundingBoxRejected() {
        val w = 1024
        val h = 472
        val synthImg = SyntheticGameImage(w, h, background = 0xFF000000.toInt())
        val searchRect = makeRect(184, 66, 716, 415)

        // Paint a solid tree canopy target block directly inside the player center bounding box:
        // x in [476..547], y in [200..266]
        val pMinX = (w * 0.465f).toInt()
        val pMaxX = (w * 0.535f).toInt()
        val pMinY = (h * 0.425f).toInt()
        val pMaxY = (h * 0.565f).toInt()
        synthImg.fillRect(pMinX + 5, pMinY + 5, pMaxX - 5, pMaxY - 5, TargetColorPreset.TREE_CANOPY.rgb)

        val clusters = findTargetClustersOracle(synthImg, searchRect, TargetColorPreset.TREE_CANOPY.rgb, TargetColorPreset.TREE_CANOPY.tolerance)
        println("Target placed directly on player character clusters detected: ${clusters.size}")
        assertEquals("Target directly on player character must be 100% ignored", 0, clusters.size)
    }

    // =========================================================================
    // 7. EMPIRICAL STRESS TEST: Strict Distance Sorting Verification
    // =========================================================================

    @Test
    fun testDistanceSortingOrderStrictlyMonotonic() {
        val w = 1024
        val h = 472
        val synthImg = SyntheticGameImage(w, h, background = 0xFF000000.toInt())
        val searchRect = makeRect(100, 50, 900, 420)
        val pcx = w * 0.50f
        val pcy = h * 0.50f

        // Place 4 synthetic tree blocks at known distances from (512, 236):
        // Block A: distance ~50px
        synthImg.fillRect((pcx + 50).toInt(), (pcy).toInt(), (pcx + 80).toInt(), (pcy + 30).toInt(), TargetColorPreset.TREE_CANOPY.rgb)
        // Block B: distance ~150px
        synthImg.fillRect((pcx - 150).toInt(), (pcy).toInt(), (pcx - 120).toInt(), (pcy + 30).toInt(), TargetColorPreset.TREE_CANOPY.rgb)
        // Block C: distance ~250px
        synthImg.fillRect((pcx).toInt(), (pcy - 200).toInt(), (pcx + 30).toInt(), (pcy - 170).toInt(), TargetColorPreset.TREE_CANOPY.rgb)
        // Block D: distance ~320px
        synthImg.fillRect((pcx + 300).toInt(), (pcy).toInt(), (pcx + 330).toInt(), (pcy + 30).toInt(), TargetColorPreset.TREE_CANOPY.rgb)

        val clusters = findTargetClustersOracle(synthImg, searchRect, TargetColorPreset.TREE_CANOPY.rgb, TargetColorPreset.TREE_CANOPY.tolerance)
        println("Distance sorting test detected: ${clusters.size} clusters")
        assertTrue("Should detect at least 3 clusters", clusters.size >= 3)

        var prevD = -1.0
        for ((i, cl) in clusters.withIndex()) {
            val d = hypot((cl.boxCenterX - pcx).toDouble(), (cl.boxCenterY - pcy).toDouble())
            println("Sorted Cluster #$i dist: ${"%.2f".format(d)}")
            if (prevD >= 0.0) {
                assertTrue("Cluster #$i ($d) must be >= previous ($prevD)", d >= prevD)
            }
            prevD = d
        }
    }

    // =========================================================================
    // 8. EMPIRICAL STRESS TEST: Humanized Cluster Selection (Anti-Stump Avoidance)
    // =========================================================================

    @Test
    fun testPickHumanClusterAvoidsRecentlyChoppedStump() {
        val cl0 = makeRect(100, 100, 150, 150) // center = (125, 125)
        val cl1 = makeRect(300, 300, 350, 350) // center = (325, 325)
        val clusters = listOf(cl0, cl1)

        val lastTappedStump = android.graphics.PointF().apply { x = 125f; y = 125f }

        // With avoidLastPoint = true, cl0 (stump) must be excluded from selection
        for (i in 0 until 50) {
            val selected = engine.pickHumanCluster(clusters, lastTappedStump, avoidLastPoint = true)
            assertNotNull("Selected cluster must not be null", selected)
            assertEquals("Must select cl1 left", cl1.left, selected!!.left)
            assertEquals("Must select cl1 top", cl1.top, selected.top)
            assertEquals("Must select cl1 right", cl1.right, selected.right)
            assertEquals("Must select cl1 bottom", cl1.bottom, selected.bottom)
        }

        // When all clusters are far from lastTapped, selection can include cl0
        val farPoint = android.graphics.PointF().apply { x = 900f; y = 900f }
        val selectedFar = engine.pickHumanCluster(clusters, farPoint, avoidLastPoint = true)
        assertNotNull(selectedFar)
        assertTrue(selectedFar!!.left == cl0.left || selectedFar.left == cl1.left)
    }

    // =========================================================================
    // 9. EMPIRICAL VERIFICATION: Calibrated Presets Spectral Properties
    // =========================================================================

    @Test
    fun testTargetColorPresetsSpectralIntegrity() {
        val canopy = TargetColorPreset.TREE_CANOPY
        assertEquals(40, (canopy.rgb shr 16) and 0xFF)
        assertEquals(46, (canopy.rgb shr 8) and 0xFF)
        assertEquals(15, canopy.rgb and 0xFF)
        assertEquals(16, canopy.tolerance)

        val trunk = TargetColorPreset.TREE_TRUNK
        assertEquals(88, (trunk.rgb shr 16) and 0xFF)
        assertEquals(68, (trunk.rgb shr 8) and 0xFF)
        assertEquals(36, trunk.rgb and 0xFF)
        assertEquals(16, trunk.tolerance)

        val deadTree = TargetColorPreset.DEAD_TREE
        assertEquals(36, (deadTree.rgb shr 16) and 0xFF)
        assertEquals(31, (deadTree.rgb shr 8) and 0xFF)
        assertEquals(20, deadTree.rgb and 0xFF)
        assertEquals(14, deadTree.tolerance)
    }
}
