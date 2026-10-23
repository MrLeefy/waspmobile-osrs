package com.example.waspmobile.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * Port of HumanCore v5 Biometric & Markov Attention Engine for OSRS Mobile Touch Telemetry.
 *
 * Defeats Mobile Auto-Clicker Detection by enforcing:
 * 1. 2D Gaussian Thumb-Pad Distribution (never tapping the exact same pixel twice)
 * 2. Non-Zero Thumb-Roll Micro-Stroke (1.4px..4.2px drift between ACTION_DOWN and ACTION_UP)
 * 3. Log-Normal Hold Duration (38..132ms) with Session Fatigue Drift & Awaited Gesture Completion
 * 4. 3-State Markov Attention Model (FOCUSED, CASUAL, SECOND_MONITOR)
 * 5. Anti-Slot-28 Inventory Limit (25..28) & Randomized Human Drop Patterns
 */
class HumanTouchEngine(
    private val handler: Handler? = null
) {

    private val gestureMutex = Mutex()
    private val mainHandler: Handler? by lazy {
        handler ?: runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    private var sessionStartMs: Long = System.currentTimeMillis()
    private var attentionWindowEndMs: Long = 0L
    private val basePressMinMs: Double = 44.0 + Random.nextDouble(0.0, 18.0)
    private val basePressMaxMs: Double = basePressMinMs + 34.0 + Random.nextDouble(0.0, 28.0)

    fun resetSession() {
        sessionStartMs = System.currentTimeMillis()
        rollAttentionState(force = true)
        rollInventoryLimit()
    }

    private fun nextGaussian(mean: Double, stdDev: Double): Double {
        val u1 = Random.nextDouble(1e-7, 1.0)
        val u2 = Random.nextDouble(1e-7, 1.0)
        val z0 = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * Math.PI * u2)
        return mean + z0 * stdDev
    }

    /**
     * Samples a humanized 2D Gaussian tap coordinate inside [rect], staying comfortably
     * inside the target bounds while varying every tap organically.
     * When [screenBounds] is provided, clamps inside [2f, screenWidth - 2f] and [2f, screenHeight - 2f].
     */
    fun sampleGaussianPoint(rect: Rect, screenBounds: Rect? = null): PointF {
        val rectW = rect.right - rect.left
        val rectH = rect.bottom - rect.top
        val cx = (rect.left + rect.right) / 2.0
        val cy = (rect.top + rect.bottom) / 2.0
        val stdX = max(2.0, rectW * 0.17)
        val stdY = max(2.0, rectH * 0.17)

        val padX = min((rectW * 0.14f).toInt(), max(0, rectW / 2 - 1))
        val padY = min((rectH * 0.14f).toInt(), max(0, rectH / 2 - 1))

        val minX = max(2f, (rect.left + padX).toFloat())
        val maxX = max(minX + 0.5f, (rect.right - padX).toFloat())
        val minY = max(2f, (rect.top + padY).toFloat())
        val maxY = max(minY + 0.5f, (rect.bottom - padY).toFloat())

        var gx = nextGaussian(cx, stdX).toFloat().coerceIn(minX, maxX)
        var gy = nextGaussian(cy, stdY).toFloat().coerceIn(minY, maxY)

        if (screenBounds != null) {
            val sW = screenBounds.right - screenBounds.left
            val sH = screenBounds.bottom - screenBounds.top
            val maxScreenX = (sW - 2).toFloat().coerceAtLeast(2f)
            val maxScreenY = (sH - 2).toFloat().coerceAtLeast(2f)
            gx = gx.coerceIn(2f, maxScreenX)
            gy = gy.coerceIn(2f, maxScreenY)
        }
        return PointF().apply { x = gx; y = gy }
    }

    /**
     * Computes a human thumb-press hold duration (ms) incorporating session fatigue drift.
     */
    fun sampleTapHoldDurationMs(): Long {
        val fatigueHours = (System.currentTimeMillis() - sessionStartMs) / 3_600_000.0
        val fatigueFactor = min(0.16, fatigueHours * 0.04)
        val meanHold = ((basePressMinMs + basePressMaxMs) * 0.5) * (1.0 + fatigueFactor)
        val sampled = nextGaussian(meanHold, 11.5).roundToLong()
        return sampled.coerceIn(38L, 132L)
    }

    /**
     * Computes the biometric micro-stroke end point for a tap, guaranteeing a 1.4..4.2px delta
     * without exceeding physical screen bounds or target bounds.
     * If the delta is < 0.5px, adjusts inward toward the screen center.
     */
    fun computeMicroStrokeEnd(
        startPt: PointF,
        targetRect: Rect,
        screenBounds: Rect,
        rollAngle: Double = Random.nextDouble(0.0, 2.0 * Math.PI),
        rollDist: Float = Random.nextDouble(1.4, 4.2).toFloat()
    ): PointF {
        val sW = screenBounds.right - screenBounds.left
        val sH = screenBounds.bottom - screenBounds.top
        val maxScreenX = (sW - 2).toFloat().coerceAtLeast(2f)
        val maxScreenY = (sH - 2).toFloat().coerceAtLeast(2f)

        val safeMinX = max(2f, targetRect.left.toFloat()).coerceAtMost(maxScreenX)
        val safeMaxX = min(maxScreenX, max(safeMinX, targetRect.right.toFloat()))
        val safeMinY = max(2f, targetRect.top.toFloat()).coerceAtMost(maxScreenY)
        val safeMaxY = min(maxScreenY, max(safeMinY, targetRect.bottom.toFloat()))

        var endX = (startPt.x + (cos(rollAngle) * rollDist).toFloat()).coerceIn(safeMinX, safeMaxX)
        var endY = (startPt.y + (sin(rollAngle) * rollDist).toFloat()).coerceIn(safeMinY, safeMaxY)

        // Micro-stroke adjustment: if delta < 0.5f, adjust inward toward screen center
        if (kotlin.math.abs(endX - startPt.x) < 0.5f && kotlin.math.abs(endY - startPt.y) < 0.5f) {
            val centerX = (screenBounds.left + screenBounds.right) / 2.0f
            val centerY = (screenBounds.top + screenBounds.bottom) / 2.0f
            val driftX = if (startPt.x < centerX) 1.8f else -1.8f
            val driftY = if (startPt.y < centerY) 1.4f else -1.4f
            endX = (startPt.x + driftX).coerceIn(2f, maxScreenX)
            endY = (startPt.y + driftY).coerceIn(2f, maxScreenY)
        }
        return PointF().apply { x = endX; y = endY }
    }

    /**
     * Dispatches a GestureDescription and suspends until Android finishes (or cancels) the stroke.
     * Serialized via [gestureMutex] and followed by an anti-cancellation quiet window
     * (28-48ms) to ensure Android's MotionEventInjector completely completes ACTION_UP before
     * the next gesture can begin.
     */
    internal suspend fun dispatchGestureAndAwait(
        service: AccessibilityService,
        gesture: GestureDescription,
        timeoutMs: Long
    ): Boolean = gestureMutex.withLock {
        val completedSuccessfully = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val accepted = try {
                    service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        mainHandler
                    )
                } catch (e: Exception) {
                    false
                }
                if (!accepted && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false

        // Anti-cancellation quiet window: let Android's MotionEventInjector completely flush ACTION_UP
        delay(Random.nextLong(28L, 49L))
        completedSuccessfully
    }

    /**
     * Dispatches a realistic human thumb tap inside [targetRect] with a 1.4..4.2px
     * thumb-roll micro-stroke so ACTION_DOWN and ACTION_UP never have a bot-flagged 0.0px delta,
     * clamps strictly inside [2f, screenWidth - 2f] and [2f, screenHeight - 2f],
     * and suspends until ACTION_UP completes.
     */
    suspend fun dispatchHumanTap(
        service: AccessibilityService,
        targetRect: Rect,
        screenBounds: Rect? = null
    ): Boolean {
        val sBounds = screenBounds ?: runCatching {
            val dm = service.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }.getOrDefault(Rect(0, 0, 1080, 2400))

        val startPt = sampleGaussianPoint(targetRect, sBounds)
        val endPt = computeMicroStrokeEnd(startPt, targetRect, sBounds)

        val holdMs = sampleTapHoldDurationMs()
        val path = Path().apply {
            moveTo(startPt.x, startPt.y)
            lineTo(endPt.x, endPt.y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        WaspMobileState.update { it.copy(lastTapPoint = startPt) }
        return dispatchGestureAndAwait(service, gesture, holdMs + 400L)
    }

    /**
     * Dispatches a subtle 1-finger camera rotation swipe in the 3D viewport
     * (mobile equivalent of Antiban.SmallCameraRotation), clamps within screen bounds,
     * and awaits completion.
     */
    suspend fun dispatchCameraMicroSwipe(
        service: AccessibilityService,
        viewport: Rect,
        screenBounds: Rect? = null
    ): Boolean {
        val sBounds = screenBounds ?: runCatching {
            val dm = service.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }.getOrDefault(Rect(0, 0, 1080, 2400))

        val sW = sBounds.right - sBounds.left
        val sH = sBounds.bottom - sBounds.top
        val maxScreenX = (sW - 4).toFloat().coerceAtLeast(4f)
        val maxScreenY = (sH - 4).toFloat().coerceAtLeast(4f)

        val startPt = sampleGaussianPoint(viewport, sBounds)
        val deltaX = Random.nextInt(-140, 140).toFloat()
        val deltaY = Random.nextInt(-45, 45).toFloat()

        val vpCenterX = (viewport.left + viewport.right) / 2.0f
        val vpCenterY = (viewport.top + viewport.bottom) / 2.0f
        val minX = min(viewport.left + 20f, vpCenterX).coerceIn(4f, maxScreenX)
        val maxX = max(minX + 1f, viewport.right - 20f).coerceIn(minX + 1f, maxScreenX)
        val minY = min(viewport.top + 20f, vpCenterY).coerceIn(4f, maxScreenY)
        val maxY = max(minY + 1f, viewport.bottom - 20f).coerceIn(minY + 1f, maxScreenY)

        val endX = (startPt.x + deltaX).coerceIn(minX, maxX)
        val endY = (startPt.y + deltaY).coerceIn(minY, maxY)
        val durationMs = Random.nextLong(220L, 480L)

        val path = Path().apply {
            moveTo(startPt.x, startPt.y)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndAwait(service, gesture, durationMs + 450L)
    }

    /**
     * 3-State Markov Attention transition (FOCUSED <-> CASUAL <-> SECOND_MONITOR).
     */
    fun rollAttentionState(force: Boolean = false): AttentionState {
        val now = System.currentTimeMillis()
        val current = WaspMobileState.state.value.attention
        if (!force && now < attentionWindowEndMs) {
            return current
        }

        val roll = Random.nextDouble()
        val next = when (current) {
            AttentionState.FOCUSED -> when {
                roll < 0.50 -> AttentionState.CASUAL
                roll < 0.72 -> AttentionState.SECOND_MONITOR
                else -> AttentionState.FOCUSED
            }
            AttentionState.CASUAL -> when {
                roll < 0.42 -> AttentionState.FOCUSED
                roll < 0.70 -> AttentionState.SECOND_MONITOR
                else -> AttentionState.CASUAL
            }
            AttentionState.SECOND_MONITOR -> when {
                roll < 0.55 -> AttentionState.CASUAL
                roll < 0.88 -> AttentionState.FOCUSED
                else -> AttentionState.SECOND_MONITOR
            }
        }

        val windowMs = when (next) {
            AttentionState.FOCUSED -> Random.nextLong(180_000L, 480_000L)
            AttentionState.CASUAL -> Random.nextLong(210_000L, 600_000L)
            AttentionState.SECOND_MONITOR -> Random.nextLong(120_000L, 330_000L)
        }
        attentionWindowEndMs = now + windowMs
        WaspMobileState.update { it.copy(attention = next) }
        return next
    }

    fun reactDelayMs(fastMin: Long = 210L, fastMax: Long = 620L): Long {
        return when (rollAttentionState()) {
            AttentionState.FOCUSED -> Random.nextLong(fastMin, fastMax)
            AttentionState.CASUAL -> Random.nextLong(fastMax, fastMax * 2 + 380L)
            AttentionState.SECOND_MONITOR -> Random.nextLong(950L, 3100L)
        }
    }

    fun actionFinishDelayMs(): Long {
        return when (rollAttentionState()) {
            AttentionState.FOCUSED -> Random.nextLong(220L, 740L)
            AttentionState.CASUAL -> Random.nextLong(720L, 2600L)
            AttentionState.SECOND_MONITOR -> Random.nextLong(1900L, 6800L)
        }
    }

    /**
     * Defeats the Slot-28 Bot Invariant: 72% of inventories go to 28 items,
     * 28% trigger drop/bank at 25..27 items.
     */
    fun rollInventoryLimit(): Int {
        val limit = if (Random.nextDouble() < 0.72) 28 else Random.nextInt(25, 28)
        WaspMobileState.update { it.copy(currentInventoryLimit = limit) }
        return limit
    }

    /**
     * Generates a humanized 28-slot tap order (0..27) across 4 distinct human thumb patterns,
     * excluding protected slots and occasionally leaving 1 stray item (18% chance).
     */
    fun generateHumanDropOrder(protectedSlots: Set<Int>): List<Int> {
        val rawOrder = when (Random.nextInt(4)) {
            // Pattern 0: Zig-zag left-to-right down rows
            0 -> (0 until 28).toList()
            // Pattern 1: Snake rows (L->R on even rows, R->L on odd rows)
            1 -> buildList {
                for (row in 0 until 7) {
                    val cols = if (row % 2 == 0) (0 until 4) else (3 downTo 0)
                    for (col in cols) add(row * 4 + col)
                }
            }
            // Pattern 2: Columns top-to-bottom
            2 -> buildList {
                for (col in 0 until 4) {
                    for (row in 0 until 7) add(row * 4 + col)
                }
            }
            // Pattern 3: Pairs down columns (0,1, 4,5, 8,9...)
            else -> buildList {
                for (pairCol in listOf(0, 2)) {
                    for (row in 0 until 7) {
                        add(row * 4 + pairCol)
                        add(row * 4 + pairCol + 1)
                    }
                }
            }
        }.filter { it !in protectedSlots }.toMutableList()

        if (rawOrder.size > 8 && Random.nextDouble() < 0.18) {
            rawOrder.removeAt(rawOrder.lastIndex)
        }
        return rawOrder
    }
}
