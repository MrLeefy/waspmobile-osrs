package com.example.waspmobile.vision

import android.graphics.Rect
import android.graphics.RectF
import com.example.waspmobile.engine.GameImage
import com.example.waspmobile.engine.MobileVisionEngine
import com.example.waspmobile.engine.TargetColorPreset
import com.example.waspmobile.engine.WaspTelemetry
import com.example.waspmobile.engine.boxCenterX
import com.example.waspmobile.engine.boxCenterY
import com.example.waspmobile.engine.boxH
import com.example.waspmobile.engine.boxW
import com.example.waspmobile.engine.makeRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Official Unit Test Suite verifying MobileVisionEngine against real Samsung Galaxy S23
 * OSRS Mobile gameplay screenshots (1024x472 landscape).
 */
class RealScreenshotVisionTest {

    private class BufferedImageGameImage(val img: BufferedImage) : GameImage {
        override val width: Int get() = img.width
        override val height: Int get() = img.height
        override fun getPixel(x: Int, y: Int): Int = img.getRGB(x, y)
        override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
            img.getRGB(x, y, width, height, pixels, offset, stride)
        }
    }

    private val screenshot1File = File("C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906388_c77464df.jpg")
    private val screenshot2File = File("C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906471_c6388f0e.jpg")
    private val screenshot3File = File("C:\\Users\\baseb\\.gemini\\antigravity\\brain\\6200e675-bb4d-440a-a521-47ed3e48c4e8\\.user_uploaded\\media_1792780906545_b4b11f4d.jpg")

    private lateinit var visionEngine: MobileVisionEngine
    private lateinit var gameImage1: GameImage
    private lateinit var gameImage2: GameImage
    private lateinit var gameImage3: GameImage

    @Before
    fun setUp() {
        visionEngine = MobileVisionEngine()

        assertTrue("Screenshot 1 file must exist: ${screenshot1File.absolutePath}", screenshot1File.exists())
        assertTrue("Screenshot 2 file must exist: ${screenshot2File.absolutePath}", screenshot2File.exists())
        assertTrue("Screenshot 3 file must exist: ${screenshot3File.absolutePath}", screenshot3File.exists())

        val img1 = ImageIO.read(screenshot1File)
        val img2 = ImageIO.read(screenshot2File)
        val img3 = ImageIO.read(screenshot3File)

        assertNotNull("ImageIO must load Screenshot 1", img1)
        assertNotNull("ImageIO must load Screenshot 2", img2)
        assertNotNull("ImageIO must load Screenshot 3", img3)

        assertEquals("Screenshot 1 width must be 1024", 1024, img1.width)
        assertEquals("Screenshot 1 height must be 472", 472, img1.height)
        assertEquals("Screenshot 2 width must be 1024", 1024, img2.width)
        assertEquals("Screenshot 2 height must be 472", 472, img2.height)
        assertEquals("Screenshot 3 width must be 1024", 1024, img3.width)
        assertEquals("Screenshot 3 height must be 472", 472, img3.height)

        gameImage1 = BufferedImageGameImage(img1)
        gameImage2 = BufferedImageGameImage(img2)
        gameImage3 = BufferedImageGameImage(img3)
    }

    /**
     * Test 1: detectFilledInventorySlots on Screenshot 2 returns EXACTLY {0, 1} (2 logs)
     * and 0 false positives across all 26 empty translucent slots.
     */
    @Test
    fun testDetectFilledInventorySlots_Screenshot2_ExactTwoLogs() {
        val telemetry = WaspTelemetry()
        val invRect = visionEngine.normToScreenRect(telemetry.invBoxNorm, gameImage2.width, gameImage2.height)

        val filledSlots = visionEngine.detectFilledInventorySlots(gameImage2, invRect)

        // Verify EXACTLY {0, 1}
        assertEquals("detectFilledInventorySlots must return exact set {0, 1}", setOf(0, 1), filledSlots)
        assertEquals("Total filled slots count must be exactly 2", 2, filledSlots.size)

        // Verify zero false positives across all 26 empty translucent slots (2..27)
        for (slotIndex in 2 until 28) {
            assertFalse(
                "Slot $slotIndex is an empty translucent slot over background trees/roofs and must NOT be marked as filled",
                filledSlots.contains(slotIndex)
            )
        }
        assertTrue("Slot 0 (first log) must be filled", filledSlots.contains(0))
        assertTrue("Slot 1 (second log) must be filled", filledSlots.contains(1))
    }

    /**
     * Test 2: isInventoryTabOpen returns true on Screenshot 2 & Screenshot 3, and false on Screenshot 1.
     */
    @Test
    fun testIsInventoryTabOpen_AcrossScreenshots() {
        val tabOpen1 = visionEngine.isInventoryTabOpen(gameImage1)
        val tabOpen2 = visionEngine.isInventoryTabOpen(gameImage2)
        val tabOpen3 = visionEngine.isInventoryTabOpen(gameImage3)

        assertFalse("Screenshot 1 has Combat Styles tab active; isInventoryTabOpen must return false", tabOpen1)
        assertTrue("Screenshot 2 has Inventory tab active; isInventoryTabOpen must return true", tabOpen2)
        assertTrue("Screenshot 3 has Inventory tab active; isInventoryTabOpen must return true", tabOpen3)
    }

    /**
     * Test 3: isTapToDropActive returns true on Screenshot 2.
     */
    @Test
    fun testIsTapToDropActive_Screenshot2() {
        val tapToDropActive2 = visionEngine.isTapToDropActive(gameImage2)
        assertTrue("Screenshot 2 has Tap-to-Drop active; isTapToDropActive must return true", tapToDropActive2)
    }

    /**
     * Test 4: findTargetClusters with TREE_CANOPY on Screenshot 2 detects genuine tree clusters,
     * excludes the player box [0.465..0.535, 0.425..0.565], rejects grass, and caps components at <= 5x5 cells.
     */
    @Test
    fun testFindTargetClusters_TreeCanopy_Screenshot2() {
        val telemetry = WaspTelemetry()
        val searchRect = visionEngine.normToScreenRect(telemetry.searchBoxNorm, gameImage2.width, gameImage2.height)

        val targetPreset = TargetColorPreset.TREE_CANOPY
        val clusters = visionEngine.findTargetClusters(
            gameImage2,
            searchRect,
            targetPreset.rgb,
            targetPreset.tolerance
        )

        // 1. Detects genuine tree clusters
        assertTrue("findTargetClusters on Screenshot 2 must detect at least one genuine tree cluster", clusters.isNotEmpty())

        val playerMinX = (gameImage2.width * 0.465f).toInt()
        val playerMaxX = (gameImage2.width * 0.535f).toInt()
        val playerMinY = (gameImage2.height * 0.425f).toInt()
        val playerMaxY = (gameImage2.height * 0.565f).toInt()

        val cellW = searchRect.boxW / 24f
        val cellH = searchRect.boxH / 18f
        // <= 5x5 cells bounding box limit plus margin
        val maxBoxW = (cellW * 5 + 2).toInt()
        val maxBoxH = (cellH * 5 + 2).toInt()

        for ((idx, cluster) in clusters.withIndex()) {
            // 2. Exclude player center box
            val inPlayerBox = cluster.boxCenterX.toInt() in playerMinX..playerMaxX &&
                    cluster.boxCenterY.toInt() in playerMinY..playerMaxY
            assertFalse(
                "Cluster #$idx center (${cluster.boxCenterX}, ${cluster.boxCenterY}) must NOT fall inside player box [$playerMinX..$playerMaxX, $playerMinY..$playerMaxY]",
                inPlayerBox
            )

            // 3. Caps components at <= 5x5 cells
            assertTrue(
                "Cluster #$idx width (${cluster.boxW}) must be <= 5 cells width ($maxBoxW)",
                cluster.boxW <= maxBoxW
            )
            assertTrue(
                "Cluster #$idx height (${cluster.boxH}) must be <= 5 cells height ($maxBoxH)",
                cluster.boxH <= maxBoxH
            )
        }

        // 4. Grass rejection verification:
        // Real grass RGBs from Lumbridge / Draynor
        val grassSamples = listOf(
            Triple(65, 110, 40), // Standard Grass
            Triple(80, 130, 48), // Sunlit Grass
            Triple(50, 95, 32),  // Dark Grass
            Triple(90, 145, 55)  // Bright Grass
        )
        val tr = (targetPreset.rgb shr 16) and 0xFF
        val tg = (targetPreset.rgb shr 8) and 0xFF
        val tb = targetPreset.rgb and 0xFF
        val maxAllowedDist = targetPreset.tolerance * 3 // 48

        for (grass in grassSamples) {
            val dist = abs(grass.first - tr) + abs(grass.second - tg) + abs(grass.third - tb)
            assertTrue(
                "Grass sample RGB(${grass.first}, ${grass.second}, ${grass.third}) Manhattan distance ($dist) must exceed max tolerance ($maxAllowedDist) to reject grass",
                dist > maxAllowedDist
            )
        }
    }
}
