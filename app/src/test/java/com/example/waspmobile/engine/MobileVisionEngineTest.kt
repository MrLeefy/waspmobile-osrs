package com.example.waspmobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MobileVisionEngineTest {

    @Test
    fun testOverlayColorsIdentifiedAccurately() {
        // Exact overlay paint colors must be identified
        assertTrue("Purple crosshair (#A855F7) must be recognized",
            MobileVisionEngine.isOverlayColor(MobileVisionEngine.OVERLAY_PURPLE))
        assertTrue("Cyan cluster box (#38BDF8) must be recognized",
            MobileVisionEngine.isOverlayColor(MobileVisionEngine.OVERLAY_CYAN))
        assertTrue("Red tap dot (#F43F5E) must be recognized",
            MobileVisionEngine.isOverlayColor(MobileVisionEngine.OVERLAY_RED))
        assertTrue("Emerald search box (#10B981) must be recognized",
            MobileVisionEngine.isOverlayColor(MobileVisionEngine.OVERLAY_GREEN))
        assertTrue("Amber inventory grid (#F59E0B) must be recognized",
            MobileVisionEngine.isOverlayColor(MobileVisionEngine.OVERLAY_ORANGE))
    }

    @Test
    fun testOverlayColorToleranceUnderSlightSurfaceFlingerDithering() {
        // Slight color shifting or compression +/- 5 per channel must still be caught
        val shiftedPurple = MobileVisionEngine.packRgb(168 + 4, 85 - 5, 247 - 3)
        assertTrue("Slightly shifted purple must be recognized as overlay",
            MobileVisionEngine.isOverlayColor(shiftedPurple))

        val shiftedOrange = MobileVisionEngine.packRgb(245 - 3, 158 + 4, 11 + 2)
        assertTrue("Slightly shifted orange must be recognized as overlay",
            MobileVisionEngine.isOverlayColor(shiftedOrange))

        val shiftedCyan = MobileVisionEngine.packRgb(56 + 2, 189 - 3, 248 + 1)
        assertTrue("Slightly shifted cyan must be recognized as overlay",
            MobileVisionEngine.isOverlayColor(shiftedCyan))
    }

    @Test
    fun testGenuineGameColorsNotClassifiedAsOverlay() {
        // In-game colors must NEVER be rejected as overlay
        val willowTreeGreen = MobileVisionEngine.packRgb(42, 115, 38)
        assertFalse("Willow tree green must not be classified as overlay",
            MobileVisionEngine.isOverlayColor(willowTreeGreen))

        val ironOreBrown = MobileVisionEngine.packRgb(61, 39, 29)
        assertFalse("Iron ore brown must not be classified as overlay",
            MobileVisionEngine.isOverlayColor(ironOreBrown))

        val graniteGrey = MobileVisionEngine.packRgb(115, 112, 108)
        assertFalse("Granite rock grey must not be classified as overlay",
            MobileVisionEngine.isOverlayColor(graniteGrey))

        val darkSpriteOutline = MobileVisionEngine.packRgb(12, 12, 12)
        assertFalse("Item sprite outline must not be classified as overlay",
            MobileVisionEngine.isOverlayColor(darkSpriteOutline))

        val inventoryStoneBg = MobileVisionEngine.packRgb(62, 53, 41)
        assertFalse("Inventory stone background must not be classified as overlay",
            MobileVisionEngine.isOverlayColor(inventoryStoneBg))

        val waterBlue = MobileVisionEngine.packRgb(32, 78, 140)
        assertFalse("Water blue must not be classified as overlay",
            MobileVisionEngine.isOverlayColor(waterBlue))
    }

    @Test
    fun testCoordinateScalingCalculations() {
        // Physical screen resolution: 2340 x 1080 (e.g. Samsung S23/S24 landscape)
        val dispW = 2340f
        val dispH = 1080f

        // Scaled screenshot bitmap resolution: 1920 x 886 (downsampled with cutout)
        val bmpW = 1920f
        val bmpH = 886f

        val scaleX = dispW / bmpW
        val scaleY = dispH / bmpH

        assertEquals(1.21875f, scaleX, 0.0001f)
        assertEquals(1.21896f, scaleY, 0.0001f)

        // Given a cluster detected in bitmap coordinates at left=240, top=180, right=320, bottom=260
        val bmpLeft = 240
        val bmpTop = 180
        val bmpRight = 320
        val bmpBottom = 260

        val screenLeft = (bmpLeft * scaleX).toInt()
        val screenTop = (bmpTop * scaleY).toInt()
        val screenRight = (bmpRight * scaleX).toInt()
        val screenBottom = (bmpBottom * scaleY).toInt()

        assertEquals(292, screenLeft)
        assertEquals(219, screenTop)
        assertEquals(390, screenRight)
        assertEquals(316, screenBottom)
    }

    @Test
    fun testPackRgbBitwiseIntegrity() {
        val color = MobileVisionEngine.packRgb(168, 85, 247)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        assertEquals(168, r)
        assertEquals(85, g)
        assertEquals(247, b)
    }
}
