package com.example.waspmobile.engine

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialM2Test {

    private fun rectF(l: Float, t: Float, r: Float, b: Float): RectF =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun pointF(x: Float, y: Float): PointF =
        PointF().apply { this.x = x; this.y = y }

    private fun w(r: RectF): Float = r.right - r.left
    private fun h(r: RectF): Float = r.bottom - r.top

    // =========================================================================
    // 1. ADVERSARIAL STRESS TEST: Box Calibration Anti-Inversion & Reverse Vectors
    // =========================================================================

    @Test
    fun testCornerReverseDiagonalDragVectorsPreventInversion() {
        val initial = rectF(0.25f, 0.25f, 0.75f, 0.75f)
        val minDim = 0.08f
        val minBound = 0.01f
        val maxBound = 0.99f

        // 1. TOP_LEFT dragged diagonally past BOTTOM_RIGHT (+1.5f, +1.5f)
        val tlInvert = CalibrationUXHelper.applyDrag(initial, DragTarget.TOP_LEFT, +1.5f, +1.5f, minDim, minBound, maxBound)
        assertEquals("TOP_LEFT X must be clamped to right - minDim", initial.right - minDim, tlInvert.left, 0.0001f)
        assertEquals("TOP_LEFT Y must be clamped to bottom - minDim", initial.bottom - minDim, tlInvert.top, 0.0001f)
        assertEquals("Right must remain unchanged", initial.right, tlInvert.right, 0.0001f)
        assertEquals("Bottom must remain unchanged", initial.bottom, tlInvert.bottom, 0.0001f)
        assertTrue("Width must be >= minDim", w(tlInvert) >= minDim - 0.0001f)
        assertTrue("Height must be >= minDim", h(tlInvert) >= minDim - 0.0001f)

        // 2. TOP_RIGHT dragged diagonally past BOTTOM_LEFT (-1.5f, +1.5f)
        val trInvert = CalibrationUXHelper.applyDrag(initial, DragTarget.TOP_RIGHT, -1.5f, +1.5f, minDim, minBound, maxBound)
        assertEquals("Left must remain unchanged", initial.left, trInvert.left, 0.0001f)
        assertEquals("TOP_RIGHT X must be clamped to left + minDim", initial.left + minDim, trInvert.right, 0.0001f)
        assertEquals("TOP_RIGHT Y must be clamped to bottom - minDim", initial.bottom - minDim, trInvert.top, 0.0001f)
        assertEquals("Bottom must remain unchanged", initial.bottom, trInvert.bottom, 0.0001f)
        assertTrue("Width must be >= minDim", w(trInvert) >= minDim - 0.0001f)
        assertTrue("Height must be >= minDim", h(trInvert) >= minDim - 0.0001f)

        // 3. BOTTOM_LEFT dragged diagonally past TOP_RIGHT (+1.5f, -1.5f)
        val blInvert = CalibrationUXHelper.applyDrag(initial, DragTarget.BOTTOM_LEFT, +1.5f, -1.5f, minDim, minBound, maxBound)
        assertEquals("BOTTOM_LEFT X must be clamped to right - minDim", initial.right - minDim, blInvert.left, 0.0001f)
        assertEquals("Top must remain unchanged", initial.top, blInvert.top, 0.0001f)
        assertEquals("Right must remain unchanged", initial.right, blInvert.right, 0.0001f)
        assertEquals("BOTTOM_LEFT Y must be clamped to top + minDim", initial.top + minDim, blInvert.bottom, 0.0001f)
        assertTrue("Width must be >= minDim", w(blInvert) >= minDim - 0.0001f)
        assertTrue("Height must be >= minDim", h(blInvert) >= minDim - 0.0001f)

        // 4. BOTTOM_RIGHT dragged diagonally past TOP_LEFT (-1.5f, -1.5f)
        val brInvert = CalibrationUXHelper.applyDrag(initial, DragTarget.BOTTOM_RIGHT, -1.5f, -1.5f, minDim, minBound, maxBound)
        assertEquals("Left must remain unchanged", initial.left, brInvert.left, 0.0001f)
        assertEquals("Top must remain unchanged", initial.top, brInvert.top, 0.0001f)
        assertEquals("BOTTOM_RIGHT X must be clamped to left + minDim", initial.left + minDim, brInvert.right, 0.0001f)
        assertEquals("BOTTOM_RIGHT Y must be clamped to top + minDim", initial.top + minDim, brInvert.bottom, 0.0001f)
        assertTrue("Width must be >= minDim", w(brInvert) >= minDim - 0.0001f)
        assertTrue("Height must be >= minDim", h(brInvert) >= minDim - 0.0001f)
    }

    @Test
    fun testEdgeReverseDragVectorsPreventInversion() {
        val initial = rectF(0.30f, 0.30f, 0.70f, 0.70f)
        val minDim = 0.08f

        // EDGE_LEFT dragged far right (+2.0f)
        val edgeLeft = CalibrationUXHelper.applyDrag(initial, DragTarget.EDGE_LEFT, +2.0f, 0f, minDim)
        assertEquals(initial.right - minDim, edgeLeft.left, 0.0001f)
        assertEquals(initial.right, edgeLeft.right, 0.0001f)
        assertTrue(w(edgeLeft) >= minDim - 0.0001f)

        // EDGE_RIGHT dragged far left (-2.0f)
        val edgeRight = CalibrationUXHelper.applyDrag(initial, DragTarget.EDGE_RIGHT, -2.0f, 0f, minDim)
        assertEquals(initial.left + minDim, edgeRight.right, 0.0001f)
        assertEquals(initial.left, edgeRight.left, 0.0001f)
        assertTrue(w(edgeRight) >= minDim - 0.0001f)

        // EDGE_TOP dragged far down (+2.0f)
        val edgeTop = CalibrationUXHelper.applyDrag(initial, DragTarget.EDGE_TOP, 0f, +2.0f, minDim)
        assertEquals(initial.bottom - minDim, edgeTop.top, 0.0001f)
        assertEquals(initial.bottom, edgeTop.bottom, 0.0001f)
        assertTrue(h(edgeTop) >= minDim - 0.0001f)

        // EDGE_BOTTOM dragged far up (-2.0f)
        val edgeBottom = CalibrationUXHelper.applyDrag(initial, DragTarget.EDGE_BOTTOM, 0f, -2.0f, minDim)
        assertEquals(initial.top + minDim, edgeBottom.bottom, 0.0001f)
        assertEquals(initial.top, edgeBottom.top, 0.0001f)
        assertTrue(h(edgeBottom) >= minDim - 0.0001f)
    }

    @Test
    fun testRandomized10000ChainedDragsFuzzer() {
        val minDim = 0.08f
        val minBound = 0.01f
        val maxBound = 0.99f
        var currentBox = rectF(0.14f, 0.12f, 0.68f, 0.84f)
        val rng = Random(1337)
        val allTargets = DragTarget.entries

        for (iter in 0 until 10000) {
            val target = allTargets[rng.nextInt(allTargets.size)]
            // Generate extreme deltas from -2.5f to +2.5f
            val dX = (rng.nextFloat() * 5.0f) - 2.5f
            val dY = (rng.nextFloat() * 5.0f) - 2.5f

            val newBox = CalibrationUXHelper.applyDrag(
                initialBoxNorm = currentBox,
                target = target,
                dNormX = dX,
                dNormY = dY,
                minDim = minDim,
                minBound = minBound,
                maxBound = maxBound
            )

            // Invariant assertions on EVERY single step:
            assertTrue("Step $iter: Left must be >= minBound ($minBound), was ${newBox.left}", newBox.left >= minBound - 0.0001f)
            assertTrue("Step $iter: Right must be <= maxBound ($maxBound), was ${newBox.right}", newBox.right <= maxBound + 0.0001f)
            assertTrue("Step $iter: Top must be >= minBound ($minBound), was ${newBox.top}", newBox.top >= minBound - 0.0001f)
            assertTrue("Step $iter: Bottom must be <= maxBound ($maxBound), was ${newBox.bottom}", newBox.bottom <= maxBound + 0.0001f)

            val width = w(newBox)
            val height = h(newBox)
            assertTrue("Step $iter ($target): Width must be >= minDim ($minDim), was $width", width >= minDim - 0.0001f)
            assertTrue("Step $iter ($target): Height must be >= minDim ($minDim), was $height", height >= minDim - 0.0001f)

            currentBox = newBox
        }
    }

    @Test
    fun testHitTestExhaustiveGeometryMatrix() {
        val box = rectF(200f, 300f, 800f, 900f)
        val cornerRadius = 36f
        val edgeThresh = 24f

        // Corners
        assertEquals(DragTarget.TOP_LEFT, CalibrationUXHelper.hitTest(200f, 300f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.TOP_RIGHT, CalibrationUXHelper.hitTest(800f, 300f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.BOTTOM_LEFT, CalibrationUXHelper.hitTest(200f, 900f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.BOTTOM_RIGHT, CalibrationUXHelper.hitTest(800f, 900f, box, cornerRadius, edgeThresh))

        // Edges (mid-point of each edge)
        assertEquals(DragTarget.EDGE_TOP, CalibrationUXHelper.hitTest(500f, 300f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.EDGE_BOTTOM, CalibrationUXHelper.hitTest(500f, 900f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.EDGE_LEFT, CalibrationUXHelper.hitTest(200f, 600f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.EDGE_RIGHT, CalibrationUXHelper.hitTest(800f, 600f, box, cornerRadius, edgeThresh))

        // Exterior points within edge threshold (finger slightly outside screen rectangle)
        assertEquals(DragTarget.EDGE_TOP, CalibrationUXHelper.hitTest(500f, 300f - 18f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.EDGE_BOTTOM, CalibrationUXHelper.hitTest(500f, 900f + 18f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.EDGE_LEFT, CalibrationUXHelper.hitTest(200f - 18f, 600f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.EDGE_RIGHT, CalibrationUXHelper.hitTest(800f + 18f, 600f, box, cornerRadius, edgeThresh))

        // Points just outside corner radius and outside edge threshold -> NONE
        assertEquals(DragTarget.NONE, CalibrationUXHelper.hitTest(200f - 40f, 300f - 40f, box, cornerRadius, edgeThresh))
        assertEquals(DragTarget.NONE, CalibrationUXHelper.hitTest(500f, 300f - 30f, box, cornerRadius, edgeThresh))

        // Inside center -> BODY_PAN
        assertEquals(DragTarget.BODY_PAN, CalibrationUXHelper.hitTest(500f, 600f, box, cornerRadius, edgeThresh))
    }

    // =========================================================================
    // 2. ADVERSARIAL STRESS TEST: High-Frequency Concurrency Barrage
    // =========================================================================

    @Test
    fun testHighFrequencyConcurrentStateMutationsAndReaderInvariants() = runTest {
        val workerCount = 16
        val iterationsPerWorker = 300
        val observedWidthErrors = AtomicInteger(0)
        val observedHeightErrors = AtomicInteger(0)

        val jobs = List(workerCount) { workerId ->
            launch(Dispatchers.Default) {
                val rng = Random(workerId * 100)
                for (i in 0 until iterationsPerWorker) {
                    when (workerId % 8) {
                        0 -> WaspMobileState.cycleScriptMode()
                        1 -> WaspMobileState.cycleCalibrationMode()
                        2 -> WaspMobileState.log("Worker $workerId log at $i")
                        3 -> {
                            val tol = rng.nextInt(10, 49)
                            WaspMobileState.update { it.copy(colorTolerance = tol) }
                        }
                        4 -> {
                            val newLeft = rng.nextFloat() * 0.4f
                            val newTop = rng.nextFloat() * 0.4f
                            val newRight = newLeft + 0.08f + (rng.nextFloat() * 0.3f)
                            val newBottom = newTop + 0.08f + (rng.nextFloat() * 0.3f)
                            WaspMobileState.update {
                                it.copy(searchBoxNorm = rectF(newLeft, newTop, newRight, newBottom))
                            }
                        }
                        5 -> {
                            val crossX = 0.02f + rng.nextFloat() * 0.96f
                            val crossY = 0.02f + rng.nextFloat() * 0.96f
                            WaspMobileState.update {
                                it.copy(crosshairNorm = pointF(crossX, crossY))
                            }
                        }
                        6 -> {
                            WaspMobileState.update {
                                it.copy(
                                    actionsCount = it.actionsCount + 1,
                                    filledSlots = rng.nextInt(0, 29),
                                    statusText = "Bot worker $workerId step $i"
                                )
                            }
                        }
                        7 -> {
                            // Concurrent reader verifying invariants on live state snapshot
                            val snap = WaspMobileState.state.value
                            if (w(snap.searchBoxNorm) < 0.079f) observedWidthErrors.incrementAndGet()
                            if (h(snap.searchBoxNorm) < 0.079f) observedHeightErrors.incrementAndGet()
                        }
                    }
                }
            }
        }
        jobs.joinAll()

        assertEquals("SearchBox width must never collapse under concurrent mutations", 0, observedWidthErrors.get())
        assertEquals("SearchBox height must never collapse under concurrent mutations", 0, observedHeightErrors.get())

        val finalState = WaspMobileState.state.value
        assertTrue("Final actionsCount must be > 0", finalState.actionsCount > 0)
        assertTrue("Logs size must be strictly capped at <= 40", finalState.logs.size <= 40)
        assertTrue("Final searchBoxNorm width must be >= 0.08f", w(finalState.searchBoxNorm) >= 0.0799f)
    }

    // =========================================================================
    // 3. ADVERSARIAL STRESS TEST: DateTimeFormatter Thread Safety & Boundary Values
    // =========================================================================

    @Test
    fun testDateTimeFormatterExtremeLocalTimes() {
        // Midnight
        val t1 = LocalTime.of(0, 0, 0)
        assertEquals("00:00:00", WaspMobileState.formatTimestamp(t1))

        // Noon
        val t2 = LocalTime.of(12, 0, 0)
        assertEquals("12:00:00", WaspMobileState.formatTimestamp(t2))

        // 23:59:59.999999999
        val t3 = LocalTime.of(23, 59, 59, 999999999)
        assertEquals("23:59:59", WaspMobileState.formatTimestamp(t3))

        // Single digit minutes and seconds padding
        val t4 = LocalTime.of(5, 7, 9)
        assertEquals("05:07:09", WaspMobileState.formatTimestamp(t4))
    }

    @Test
    fun testMassiveMultiThreadedLogFlooding() = runTest {
        val totalThreads = 50
        val logsPerThread = 40
        val regex = Regex("""^\[\d{2}:\d{2}:\d{2}\] Thread #\d+ log \d+$""")

        val jobs = List(totalThreads) { threadIdx ->
            launch(Dispatchers.Default) {
                for (iter in 0 until logsPerThread) {
                    WaspMobileState.log("Thread #$threadIdx log $iter")
                }
            }
        }
        jobs.joinAll()

        val logs = WaspMobileState.state.value.logs
        assertEquals("Logs buffer must be full (40 entries)", 40, logs.size)
        for (entry in logs) {
            assertTrue("Log entry must match thread-safe format: '$entry'", entry.matches(regex))
        }
    }

    // =========================================================================
    // 4. ADVERSARIAL STRESS TEST: SharedPreferences Persistence & Recovery
    // =========================================================================

    private class InMemorySharedPreferences : SharedPreferences {
        val map = ConcurrentHashMap<String, Any>()

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class EditorImpl(private val parent: InMemorySharedPreferences) : SharedPreferences.Editor {
            private val temp = HashMap<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { temp[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { temp[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { temp[key] = this }
            override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) parent.map.clear()
                temp.forEach { (k, v) ->
                    if (v === this) parent.map.remove(k)
                    else if (v != null) parent.map[k] = v
                }
            }
        }
    }

    private class FakeContextImpl : android.content.ContextWrapper(null) {
        @JvmField var testPrefs: SharedPreferences? = null
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = testPrefs!!
        override fun getApplicationContext(): Context = this
    }

    private fun createFakeContext(sharedPreferences: SharedPreferences): Context {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val ctx = unsafe.allocateInstance(FakeContextImpl::class.java) as FakeContextImpl
        ctx.testPrefs = sharedPreferences
        return ctx
    }

    @Test
    fun testSharedPreferencesFullRoundTripPersistence() {
        val inMemoryPrefs = InMemorySharedPreferences()
        val fakeContext = createFakeContext(inMemoryPrefs)

        // 1. Establish custom settings
        val customMode = WaspScriptMode.POWER_MINER
        val customPreset = TargetColorPreset.IRON_ROCK
        val customRgb = 0xFF704534.toInt()
        val customTol = 34
        val customProt1 = false
        val customProt2 = true
        val customSearch = rectF(0.18f, 0.15f, 0.72f, 0.88f)
        val customInv = rectF(0.70f, 0.20f, 0.95f, 0.90f)
        val customCross = pointF(0.50f, 0.60f)

        WaspMobileState.update {
            it.copy(
                scriptMode = customMode,
                colorPreset = customPreset,
                targetColorRgb = customRgb,
                colorTolerance = customTol,
                protectSlot1 = customProt1,
                protectSlot2 = customProt2,
                searchBoxNorm = customSearch,
                invBoxNorm = customInv,
                crosshairNorm = customCross
            )
        }

        // 2. Save preferences
        WaspMobileState.savePrefs(fakeContext)

        // Verify keys exist in raw SharedPreferences storage
        assertEquals(customMode.name, inMemoryPrefs.getString("script_mode", null))
        assertEquals(customPreset.name, inMemoryPrefs.getString("color_preset", null))
        assertEquals(customRgb, inMemoryPrefs.getInt("target_rgb", 0))
        assertEquals(customTol, inMemoryPrefs.getInt("color_tol", 0))
        assertEquals(customProt1, inMemoryPrefs.getBoolean("protect_slot1", true))
        assertEquals(customProt2, inMemoryPrefs.getBoolean("protect_slot2", false))
        assertEquals(customSearch.left, inMemoryPrefs.getFloat("search_l", 0f), 0.0001f)
        assertEquals(customSearch.top, inMemoryPrefs.getFloat("search_t", 0f), 0.0001f)
        assertEquals(customSearch.right, inMemoryPrefs.getFloat("search_r", 0f), 0.0001f)
        assertEquals(customSearch.bottom, inMemoryPrefs.getFloat("search_b", 0f), 0.0001f)
        assertEquals(customInv.left, inMemoryPrefs.getFloat("inv_l", 0f), 0.0001f)
        assertEquals(customInv.top, inMemoryPrefs.getFloat("inv_t", 0f), 0.0001f)
        assertEquals(customInv.right, inMemoryPrefs.getFloat("inv_r", 0f), 0.0001f)
        assertEquals(customInv.bottom, inMemoryPrefs.getFloat("inv_b", 0f), 0.0001f)
        assertEquals(customCross.x, inMemoryPrefs.getFloat("cross_x", 0f), 0.0001f)
        assertEquals(customCross.y, inMemoryPrefs.getFloat("cross_y", 0f), 0.0001f)

        // 3. Clear in-memory state to defaults
        WaspMobileState.update { WaspTelemetry() }
        assertFalse(WaspMobileState.state.value.scriptMode == customMode)

        // 4. Force reload from SharedPreferences
        WaspMobileState.loadPrefs(fakeContext, force = true)

        // 5. Verify 100% round-trip fidelity
        val reloaded = WaspMobileState.state.value
        assertEquals(customMode, reloaded.scriptMode)
        assertEquals(customPreset, reloaded.colorPreset)
        assertEquals(customRgb, reloaded.targetColorRgb)
        assertEquals(customTol, reloaded.colorTolerance)
        assertEquals(customProt1, reloaded.protectSlot1)
        assertEquals(customProt2, reloaded.protectSlot2)
        assertEquals(customSearch.left, reloaded.searchBoxNorm.left, 0.0001f)
        assertEquals(customSearch.top, reloaded.searchBoxNorm.top, 0.0001f)
        assertEquals(customSearch.right, reloaded.searchBoxNorm.right, 0.0001f)
        assertEquals(customSearch.bottom, reloaded.searchBoxNorm.bottom, 0.0001f)
        assertEquals(customInv.left, reloaded.invBoxNorm.left, 0.0001f)
        assertEquals(customInv.top, reloaded.invBoxNorm.top, 0.0001f)
        assertEquals(customInv.right, reloaded.invBoxNorm.right, 0.0001f)
        assertEquals(customInv.bottom, reloaded.invBoxNorm.bottom, 0.0001f)
        assertEquals(customCross.x, reloaded.crosshairNorm.x, 0.0001f)
        assertEquals(customCross.y, reloaded.crosshairNorm.y, 0.0001f)
    }

    @Test
    fun testCorruptedSharedPreferencesRecoveryAndDefensiveHealing() {
        val inMemoryPrefs = InMemorySharedPreferences()
        val fakeContext = createFakeContext(inMemoryPrefs)

        // Seed adversarial / corrupted data in SharedPreferences
        inMemoryPrefs.edit()
            .putString("script_mode", "CORRUPTED_NON_EXISTENT_MODE")
            .putString("color_preset", "HACKED_COLOR_PRESET")
            .putInt("color_tol", 99999) // Way outside [10..48]
            .putFloat("search_l", 0.90f) // Search Left set to 0.90f
            .putFloat("search_r", 0.10f) // Search Right set to 0.10f (inverted!)
            .putFloat("inv_t", 0.95f)    // Inv Top set to 0.95f
            .putFloat("inv_b", 0.05f)    // Inv Bottom set to 0.05f (inverted!)
            .putFloat("cross_x", -10.0f) // Off-screen X
            .putFloat("cross_y", 50.0f)  // Off-screen Y
            .apply()

        // Force load prefs
        WaspMobileState.loadPrefs(fakeContext, force = true)
        val healed = WaspMobileState.state.value

        // Assert graceful fallback for enums
        assertEquals("Corrupted script_mode must safely fallback to WOODCUTTER_DROP", WaspScriptMode.WOODCUTTER_DROP, healed.scriptMode)
        assertEquals("Corrupted color_preset must safely fallback to TREE_CANOPY", TargetColorPreset.TREE_CANOPY, healed.colorPreset)

        // Assert tolerance clamping
        assertEquals("Tolerance must clamp to max 48", 48, healed.colorTolerance)

        // Assert Search Box defensive healing against inverted input:
        // sLeft clamped to 0.85f, sRight coerced to >= sLeft + 0.08f (0.93f)
        assertTrue("Healed search left must be <= 0.85f", healed.searchBoxNorm.left <= 0.8501f)
        assertTrue("Healed search right must be >= search left + 0.08f", healed.searchBoxNorm.right >= healed.searchBoxNorm.left + 0.0799f)
        assertTrue("Healed search right must be <= 0.99f", healed.searchBoxNorm.right <= 0.9901f)
        assertTrue("Healed search width must be >= 0.08f", w(healed.searchBoxNorm) >= 0.0799f)

        // Assert Inventory Box defensive healing
        assertTrue("Healed inv bottom must be >= inv top + 0.08f", healed.invBoxNorm.bottom >= healed.invBoxNorm.top + 0.0799f)
        assertTrue("Healed inv width must be >= 0.06f", w(healed.invBoxNorm) >= 0.0599f)

        // Assert Crosshair coordinate clamping
        assertEquals(0.02f, healed.crosshairNorm.x, 0.0001f)
        assertEquals(0.98f, healed.crosshairNorm.y, 0.0001f)
    }

    @Test
    fun testSimulatedServiceTeardownAndRebootLifecycle() {
        val inMemoryPrefs = InMemorySharedPreferences()
        val fakeContext = createFakeContext(inMemoryPrefs)

        // Phase 1: Service boots, loads prefs (currently empty)
        WaspMobileState.loadPrefs(fakeContext, force = true)

        // Phase 2: User changes script mode and protects slot 1 & 2
        WaspMobileState.cycleScriptMode() // changes to POWER_MINER
        val activeMode = WaspMobileState.state.value.scriptMode
        WaspMobileState.update { it.copy(protectSlot1 = true, protectSlot2 = true) }

        // Phase 3: Service teardown (onDestroy saves preferences)
        WaspMobileState.savePrefs(fakeContext)

        // Phase 4: App process killed or recreated in memory (simulate state reset)
        WaspMobileState.update { WaspTelemetry(scriptMode = WaspScriptMode.WOODCUTTER_DROP, protectSlot2 = false) }

        // Phase 5: Service restarts and reconnects
        WaspMobileState.loadPrefs(fakeContext, force = true)

        // Phase 6: Verify user configuration recovered
        val recovered = WaspMobileState.state.value
        assertEquals("Script mode must survive teardown", activeMode, recovered.scriptMode)
        assertTrue("protectSlot1 must survive teardown", recovered.protectSlot1)
        assertTrue("protectSlot2 must survive teardown", recovered.protectSlot2)
    }
}
