package com.example.waspmobile.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Abstraction over pixel sources enabling JVM unit testing without Android Bitmap mocking issues.
 */
interface GameImage {
    val width: Int
    val height: Int
    fun getPixel(x: Int, y: Int): Int
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        for (row in 0 until height) {
            val rowOffset = offset + row * stride
            for (col in 0 until width) {
                pixels[rowOffset + col] = getPixel(x + col, y + row)
            }
        }
    }
}

class BitmapGameImage(val bitmap: Bitmap) : GameImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun getPixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
    override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        bitmap.getPixels(pixels, offset, stride, x, y, width, height)
    }
}
typealias AndroidBitmapImage = BitmapGameImage

fun Bitmap.toGameImage(): GameImage = BitmapGameImage(this)

// Field-math extensions on android.graphics.Rect so unit tests on host JVM never invoke unmocked methods
inline val Rect.boxW: Int get() = right - left
inline val Rect.boxH: Int get() = bottom - top
inline val Rect.boxCenterX: Float get() = (left + right) * 0.5f
inline val Rect.boxCenterY: Float get() = (top + bottom) * 0.5f

fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect = Rect().apply {
    left = l
    top = t
    right = r
    bottom = b
}

/**
 * WaspMobile Vision Engine (v5 Hardened):
 * - Uses bulk `Bitmap.getPixels(...)` into reusable arrays (eliminating 110,000+ JNI calls/frame)
 * - Samples target RGB colors under the interactive calibration crosshair
 * - Finds 3D world color clusters (Trees, Rocks, Fishing spots, Bank booths) inside the Search Box
 *   with resolution-adaptive hit thresholds
 * - Excludes recently depleted rock/tree coordinates (Anti-Same-Rock)
 * - Computes all 28 OSRS Mobile inventory slot rectangles (4 columns x 7 rows) and detects filled
 *   slots using both 1px item outline detection (`#010101`) and intra-slot RGB contrast (immune to
 *   in-game brightness slider changes and dark brown Iron Ore / Logs / Coal)
 * - Detects character animation / activity around the center of the 3D screen
 */
class MobileVisionEngine {

    private var previousCenterPatch: IntArray? = null
    private var previousScreenCenterPatch: IntArray? = null
    private var prevCenterX: Int = -1
    private var prevCenterY: Int = -1
    private var prevScreenCenterX: Int = -1
    private var prevScreenCenterY: Int = -1
    private var pixelBuffer = IntArray(0)

    fun reset() {
        previousCenterPatch = null
        previousScreenCenterPatch = null
        prevCenterX = -1
        prevCenterY = -1
        prevScreenCenterX = -1
        prevScreenCenterY = -1
    }

    companion object {
        // Saturated overlay paint colors to reject during vision analysis
        const val OVERLAY_PURPLE = 0xFF_A8_55_F7.toInt()  // #A855F7 Crosshair
        const val OVERLAY_CYAN   = 0xFF_38_BD_F8.toInt()  // #38BDF8 Clusters
        const val OVERLAY_RED    = 0xFF_F4_3F_5E.toInt()  // #F43F5E Tap Dot
        const val OVERLAY_GREEN  = 0xFF_10_B9_81.toInt()  // #10B981 Search Box
        const val OVERLAY_ORANGE = 0xFF_F5_9E_0B.toInt()  // #F59E0B Inventory Grid

        /**
         * Checks whether [rgb] falls within [tolerance] Manhattan distance of any overlay UI color.
         */
        fun isOverlayColor(rgb: Int, tolerance: Int = 40): Boolean {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF

            fun dist(target: Int): Int {
                val tr = (target shr 16) and 0xFF
                val tg = (target shr 8) and 0xFF
                val tb = target and 0xFF
                return abs(r - tr) + abs(g - tg) + abs(b - tb)
            }

            return dist(OVERLAY_PURPLE) <= tolerance ||
                    dist(OVERLAY_CYAN) <= tolerance ||
                    dist(OVERLAY_RED) <= tolerance ||
                    dist(OVERLAY_GREEN) <= tolerance ||
                    dist(OVERLAY_ORANGE) <= tolerance
        }

        fun packRgb(r: Int, g: Int, b: Int): Int {
            return (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        }
    }

    private fun ensureBuffer(size: Int): IntArray {
        if (pixelBuffer.size < size) {
            pixelBuffer = IntArray(size)
        }
        return pixelBuffer
    }

    fun normToScreenRect(norm: RectF, screenW: Int, screenH: Int): Rect {
        val w = max(2, screenW)
        val h = max(2, screenH)
        val l = (norm.left * w).toInt().coerceIn(0, w - 2)
        val t = (norm.top * h).toInt().coerceIn(0, h - 2)
        val r = (norm.right * w).toInt().coerceIn(l + 1, w)
        val b = (norm.bottom * h).toInt().coerceIn(t + 1, h)
        return makeRect(l, t, r, b)
    }

    /**
     * Samples the average RGB color in a 7x7 patch around [normPt] on [image],
     * filtering out overlay UI colors so the eyedropper never locks onto crosshairs or boxes.
     */
    fun sampleColorAt(image: GameImage, normPt: PointF): Int {
        if (image.width < 7 || image.height < 7) return Color.BLACK
        val nx = normPt.x.coerceIn(0f, 1f)
        val ny = normPt.y.coerceIn(0f, 1f)
        val cx = (nx * image.width).toInt().coerceIn(3, image.width - 4)
        val cy = (ny * image.height).toInt().coerceIn(3, image.height - 4)

        val patch = IntArray(49)
        image.getPixels(patch, 0, 7, cx - 3, cy - 3, 7, 7)

        var rSum = 0
        var gSum = 0
        var bSum = 0
        var validCount = 0
        for (px in patch) {
            if (!isOverlayColor(px)) {
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
                validCount++
            }
        }
        if (validCount == 0) {
            val centerPx = image.getPixel(cx, cy)
            return centerPx
        }
        return packRgb(rSum / validCount, gSum / validCount, bSum / validCount)
    }

    fun sampleColorAt(bitmap: Bitmap, normPt: PointF): Int =
        sampleColorAt(BitmapGameImage(bitmap), normPt)

    /**
     * Checks if the active red stone state (R - G >= 28) on the OSRS Mobile Backpack tab
     * icon at normalized x = 0.916..0.935, y = 0.505..0.520 is present.
     */
    fun isInventoryTabOpen(image: GameImage): Boolean {
        if (image.width < 100 || image.height < 100) return false
        val xMin = (image.width * 0.916f).toInt()
        val xMax = (image.width * 0.935f).toInt()
        val yMin = (image.height * 0.505f).toInt()
        val yMax = (image.height * 0.520f).toInt()
        val patchW = xMax - xMin + 1
        val patchH = yMax - yMin + 1
        if (patchW <= 0 || patchH <= 0) return false

        val patch = IntArray(patchW * patchH)
        image.getPixels(patch, 0, patchW, xMin, yMin, patchW, patchH)

        var redStoneCount = 0
        for (px in patch) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            if (r - g >= 28) {
                redStoneCount++
            }
        }
        val ratio = redStoneCount.toFloat() / patch.size.toFloat()
        return (ratio >= 0.30f && redStoneCount >= 10) || redStoneCount >= 40
    }

    fun isInventoryTabOpen(bitmap: Bitmap): Boolean =
        isInventoryTabOpen(BitmapGameImage(bitmap))

    /**
     * Checks if the left-sidebar Tap-to-Drop hotkey button at normalized
     * x = 0.055..0.075, y = 0.550..0.585 contains active saturated red pixels.
     */
    fun isTapToDropActive(image: GameImage): Boolean {
        if (image.width < 100 || image.height < 100) return false
        val xMin = (image.width * 0.055f).toInt()
        val xMax = (image.width * 0.075f).toInt()
        val yMin = (image.height * 0.550f).toInt()
        val yMax = (image.height * 0.585f).toInt()
        val patchW = xMax - xMin + 1
        val patchH = yMax - yMin + 1
        if (patchW <= 0 || patchH <= 0) return false

        val patch = IntArray(patchW * patchH)
        image.getPixels(patch, 0, patchW, xMin, yMin, patchW, patchH)

        var satRedCount = 0
        for (px in patch) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (r > 130 && g < 60 && b < 60) {
                satRedCount++
            }
        }
        return satRedCount >= 15
    }

    fun isTapToDropActive(bitmap: Bitmap): Boolean =
        isTapToDropActive(BitmapGameImage(bitmap))

    /**
     * Computes the 28 individual slot bounding boxes (4 columns x 7 rows) inside [invRect].
     */
    fun getInventorySlotRects(invRect: Rect): List<Rect> {
        val cols = 4
        val rows = 7
        val cellW = invRect.boxW / cols.toFloat()
        val cellH = invRect.boxH / rows.toFloat()
        val padX = (cellW * 0.12f).toInt()
        val padY = (cellH * 0.10f).toInt()

        val slots = ArrayList<Rect>(28)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = (invRect.left + c * cellW + padX).toInt().coerceIn(invRect.left, max(invRect.left, invRect.right - 2))
                val top = (invRect.top + r * cellH + padY).toInt().coerceIn(invRect.top, max(invRect.top, invRect.bottom - 2))
                val right = (invRect.left + (c + 1) * cellW - padX).toInt().coerceIn(left + 1, max(left + 1, invRect.right))
                val bottom = (invRect.top + (r + 1) * cellH - padY).toInt().coerceIn(top + 1, max(top + 1, invRect.bottom))
                slots.add(makeRect(left, top, right, bottom))
            }
        }
        return slots
    }

    /**
     * Counts how many of the 28 inventory slots contain an item and returns the indices (0..27)
     * of filled slots.
     *
     * Uses sharp 1px/2px opaque sprite edge gradients (|R1-R2| + |G1-G2| + |B1-B2| > 85),
     * intra-slot contrast (meanAbsDev >= 28.0f), and item outline/luminance check
     * (minLum <= 75 || lumRange >= 165), completely rejecting translucent empty slots over
     * background 3D trees and roofs.
     */
    @Synchronized
    fun detectFilledInventorySlots(image: GameImage, invRect: Rect): Set<Int> {
        if (image.width < 16 || image.height < 28) return emptySet()
        val clampedInv = makeRect(
            invRect.left.coerceIn(0, max(0, image.width - 2)),
            invRect.top.coerceIn(0, max(0, image.height - 2)),
            invRect.right.coerceIn(1, image.width),
            invRect.bottom.coerceIn(1, image.height)
        )
        val invW = clampedInv.boxW
        val invH = clampedInv.boxH
        if (invW < 16 || invH < 28) return emptySet()

        val buf = ensureBuffer(invW * invH)
        image.getPixels(buf, 0, invW, clampedInv.left, clampedInv.top, invW, invH)

        val slotRects = getInventorySlotRects(clampedInv)
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
                    if (isOverlayColor(px)) continue

                    val r1 = (px shr 16) and 0xFF
                    val g1 = (px shr 8) and 0xFF
                    val b1 = px and 0xFF

                    val lum = (299 * r1 + 587 * g1 + 114 * b1) / 1000
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum

                    // 1px horizontal neighbor gradient
                    if (x + 1 < relRight) {
                        val pxRight = buf[rowOffset + x + 1]
                        if (!isOverlayColor(pxRight)) {
                            val r2 = (pxRight shr 16) and 0xFF
                            val g2 = (pxRight shr 8) and 0xFF
                            val b2 = pxRight and 0xFF
                            if (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 85) {
                                sharpEdges++
                            }
                        }
                    }
                    // 1px vertical neighbor gradient
                    if (y + 1 < relBottom) {
                        val pxDown = buf[(y + 1) * invW + x]
                        if (!isOverlayColor(pxDown)) {
                            val r2 = (pxDown shr 16) and 0xFF
                            val g2 = (pxDown shr 8) and 0xFF
                            val b2 = pxDown and 0xFF
                            if (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) > 85) {
                                sharpEdges++
                            }
                        }
                    }

                    if ((y - relTop) % 2 == 0 && (x - relLeft) % 2 == 0) {
                        rSum += r1
                        gSum += g1
                        bSum += b1
                        sampleCount++
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
                    if (!isOverlayColor(px)) {
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        devSum += abs(r - meanR) + abs(g - meanG) + abs(b - meanB)
                    }
                }
            }

            val meanAbsDev = devSum.toFloat() / sampleCount.toFloat()
            val lumRange = maxLum - minLum

            // Contrast check: require (minLum <= 75 || lumRange >= 165) alongside sharpEdges >= 8 and meanAbsDev >= 28.0f
            if (sharpEdges >= 8 && meanAbsDev >= 28.0f && (minLum <= 75 || lumRange >= 165)) {
                filled.add(idx)
            }
        }
        return filled
    }

    fun detectFilledInventorySlots(bitmap: Bitmap, invRect: Rect): Set<Int> =
        detectFilledInventorySlots(BitmapGameImage(bitmap), invRect)

    /**
     * Scans [searchRect] on [image] using bulk `getPixels` for pixels matching [targetRgb]
     * within [tolerance], excluding the player center bounding box and any pixel inside [excludeRect]
     * (such as the floating HUD pill), aggregates matching cells into bounding-box clusters
     * limited to <= 5x5 cells, and returns up to 8 valid clusters sorted by true distance to player center.
     */
    @Synchronized
    fun findTargetClusters(
        image: GameImage,
        searchRect: Rect,
        targetRgb: Int,
        tolerance: Int,
        excludeRect: Rect? = null
    ): List<Rect> {
        if (image.width < 16 || image.height < 16) return emptyList()
        val clamped = makeRect(
            searchRect.left.coerceIn(0, max(0, image.width - 2)),
            searchRect.top.coerceIn(0, max(0, image.height - 2)),
            searchRect.right.coerceIn(1, image.width),
            searchRect.bottom.coerceIn(1, image.height)
        )
        val w = clamped.boxW
        val h = clamped.boxH
        if (w < 16 || h < 16) return emptyList()

        val buf = ensureBuffer(w * h)
        image.getPixels(buf, 0, w, clamped.left, clamped.top, w, h)

        val isTargetOverlay = isOverlayColor(targetRgb)
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

        // Scale minimum cell hits with cell area so both 720p split-screen and 1440p full-screen work reliably
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
                // Exclude player center normalized bounding box [0.465f..0.535f, 0.425f..0.565f]
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
                if (!isTargetOverlay && isOverlayColor(px)) {
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

        // Connected-components clustering over active grid cells (limit BFS growth to <= 5x5 grid cells)
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

    fun findTargetClusters(
        bitmap: Bitmap,
        searchRect: Rect,
        targetRgb: Int,
        tolerance: Int,
        excludeRect: Rect? = null
    ): List<Rect> = findTargetClusters(BitmapGameImage(bitmap), searchRect, targetRgb, tolerance, excludeRect)

    /**
     * Non-deterministic cluster selection that avoids re-clicking [lastTappedPoint]
     * when multiple rock/tree clusters exist (defeating deterministic index-0 clustering).
     */
    fun pickHumanCluster(clusters: List<Rect>, lastTappedPoint: PointF?, avoidLastPoint: Boolean): Rect? {
        if (clusters.isEmpty()) return null
        if (clusters.size == 1) return clusters[0]

        val filtered = if (avoidLastPoint && lastTappedPoint != null) {
            val nonRecent = clusters.filter {
                hypot(
                    (it.boxCenterX - lastTappedPoint.x).toDouble(),
                    (it.boxCenterY - lastTappedPoint.y).toDouble()
                ) > max(32f, it.boxW * 0.65f)
            }
            nonRecent.ifEmpty { clusters }
        } else {
            clusters
        }

        val poolSize = min(3, filtered.size)
        return if (poolSize > 1 && Random.nextDouble() < 0.38) {
            filtered[Random.nextInt(0, poolSize)]
        } else {
            filtered[0]
        }
    }

    /**
     * Checks if the player character around the center of [searchRect] OR the center of the 3D
     * screen (`image.width / 2`, `image.height / 2`) is actively animating (chopping/mining)
     * by comparing center pixels against the previous frame.
     */
    @Synchronized
    fun isPlayerAnimating(image: GameImage, searchRect: Rect): Boolean {
        if (image.width < 64 || image.height < 64) return false

        fun sample41x41(cx: Int, cy: Int): IntArray {
            val patchSize = 41
            val rawPatch = IntArray(patchSize * patchSize)
            image.getPixels(rawPatch, 0, patchSize, cx - 20, cy - 20, patchSize, patchSize)
            val sampled = IntArray(121)
            var idx = 0
            for (py in 0 until patchSize step 4) {
                val rowOffset = py * patchSize
                for (px in 0 until patchSize step 4) {
                    sampled[idx++] = rawPatch[rowOffset + px]
                }
            }
            return sampled
        }

        fun countChanged(pA: IntArray, pB: IntArray): Int {
            var changed = 0
            for (i in pA.indices) {
                val p1 = pA[i]
                val p2 = pB[i]
                val diff = abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)) +
                        abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)) +
                        abs((p1 and 0xFF) - (p2 and 0xFF))
                if (diff > 36) changed++
            }
            return changed
        }

        val cx = searchRect.boxCenterX.toInt().coerceIn(24, image.width - 25)
        val cy = searchRect.boxCenterY.toInt().coerceIn(24, image.height - 25)
        val sampledPatch = sample41x41(cx, cy)

        val scx = (image.width / 2).coerceIn(24, image.width - 25)
        val scy = (image.height / 2).coerceIn(24, image.height - 25)
        val sampledScreenPatch = sample41x41(scx, scy)

        val prev = previousCenterPatch
        val prevScreen = previousScreenCenterPatch
        val sameCenter = (cx == prevCenterX && cy == prevCenterY)
        val sameScreenCenter = (scx == prevScreenCenterX && scy == prevScreenCenterY)

        previousCenterPatch = sampledPatch
        previousScreenCenterPatch = sampledScreenPatch
        prevCenterX = cx
        prevCenterY = cy
        prevScreenCenterX = scx
        prevScreenCenterY = scy

        val searchCenterAnim = (prev != null && sameCenter && countChanged(sampledPatch, prev) >= 10)
        val screenCenterAnim = (prevScreen != null && sameScreenCenter && countChanged(sampledScreenPatch, prevScreen) >= 10)
        return searchCenterAnim || screenCenterAnim
    }

    fun isPlayerAnimating(bitmap: Bitmap, searchRect: Rect): Boolean =
        isPlayerAnimating(BitmapGameImage(bitmap), searchRect)
}
