package com.example.waspmobile.service

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MobilePlanTest {
    private val region = PlanRegion(.1, .1, .9, .9)
    private val guard = ColorGuard(region, listOf(255, 0, 0), 0, 4)
    private fun image() = IntArray(64 * 64) { 0xFFFFFF }
    private fun square(pixels: IntArray, x: Int, y: Int, size: Int = 4) {
        for (py in y until y + size) for (px in x until x + size) pixels[py * 64 + px] = 0xFF0000
    }
    @Test fun sharedContractsHaveIdenticalNodeAndKotlinAcceptance() {
        val file = File(System.getProperty("wasp.repoRoot"), "contracts/mobile-plan-v1-cases.json")
        assertTrue("Shared contract fixtures must exist", file.isFile)
        val cases = JSONArray(file.readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val accepted = runCatching { MobilePlan.parse(c.getJSONObject("plan").toString()) }.isSuccess
            assertEquals(c.getString("name"), c.getBoolean("valid"), accepted)
        }
    }
    @Test fun uniqueComponentReturnsAnActualMatchingPixel() {
        val pixels = image(); square(pixels, 20, 20)
        val match = PlanColorMatcher.find(64, 64, pixels, guard)
        assertNotNull(match)
        val x = (match!!.x * 64).toInt(); val y = (match.y * 64).toInt()
        assertEquals(0xFF0000, pixels[y * 64 + x])
        assertEquals(16, match.matchingPixels)
    }
    @Test fun equallyStrongTargetsAreRejectedAsAmbiguous() {
        val pixels = image(); square(pixels, 15, 15); square(pixels, 35, 35)
        assertNull(PlanColorMatcher.find(64, 64, pixels, guard))
    }
    @Test fun backgroundDominatedRegionDoesNotProduceATarget() {
        assertNull(PlanColorMatcher.find(64, 64, IntArray(4096) { 0xFF0000 }, guard))
    }
    @Test fun emptyAndInsufficientMatchesAreRejected() {
        assertNull(PlanColorMatcher.find(64, 64, image(), guard))
        val pixels = image(); pixels[20 * 64 + 20] = 0xFF0000
        assertNull(PlanColorMatcher.find(64, 64, pixels, guard))
    }
    @Test fun pixelsOutsideRegionNeverBecomeTargets() {
        val pixels = image(); square(pixels, 0, 0)
        assertNull(PlanColorMatcher.find(64, 64, pixels, guard))
    }
    @Test fun malformedImageBuffersAreRejected() {
        assertTrue(runCatching { PlanColorMatcher.find(64, 64, IntArray(1), guard) }.isFailure)
        assertTrue(runCatching { PlanColorMatcher.find(513, 1, IntArray(513), guard) }.isFailure)
    }
    @Test fun digestChangesWithAnyPlanByteChange() {
        val a = MobilePlan.sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", a)
        assertNotEquals(a, MobilePlan.sha256("abc "))
    }
}
