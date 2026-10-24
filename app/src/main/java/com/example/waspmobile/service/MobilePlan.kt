package com.example.waspmobile.service

import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

/** Restricted data, never Kotlin/Pascal/JavaScript evaluation. Each plan needs local approval. */
internal data class PlanRegion(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class ColorGuard(val region: PlanRegion, val rgb: List<Int>, val tolerance: Int, val minPixels: Int)
internal data class MobilePlanStep(val op: String, val label: String, val ms: Long = 0, val guard: ColorGuard? = null)
internal data class MobilePlan(val title: String, val purpose: String, val maxRuntimeMs: Long, val steps: List<MobilePlanStep>) {
    val maxTaps: Int get() = steps.count { it.op == "tapColor" }
    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun JSONObject.exact(vararg fields: String) {
            require(keys().asSequence().toSet() == fields.toSet()) { "Unexpected or missing plan fields" }
        }
        private fun JSONObject.number(name: String, min: Double, max: Double): Double {
            val n = (get(name) as? Number)?.toDouble() ?: error("$name must be numeric")
            require(n.isFinite() && n in min..max) { "Invalid $name" }
            return n
        }
        private fun JSONObject.integer(name: String, min: Int, max: Int): Int {
            val n = number(name, min.toDouble(), max.toDouble())
            require(n % 1.0 == 0.0) { "$name must be an integer" }
            return n.toInt()
        }
        private fun JSONObject.text(name: String, max: Int): String {
            val value = get(name) as? String ?: error("$name must be text")
            require(value.isNotBlank() && value.length <= max) { "Invalid $name" }
            return value.trim()
        }
        fun parse(json: String): MobilePlan {
            require(json.toByteArray(Charsets.UTF_8).size <= 12000) { "Plan too large" }
            val root = JSONObject(json)
            root.exact("version", "title", "purpose", "maxRuntimeMs", "steps")
            require(root.integer("version", 1, 1) == 1)
            val rawSteps = root.getJSONArray("steps")
            require(rawSteps.length() in 1..24) { "Plan must contain 1..24 steps" }
            val steps = (0 until rawSteps.length()).map { index ->
                val item = rawSteps.getJSONObject(index)
                val op = item.text("op", 16)
                val label = item.text("label", 80)
                when (op) {
                    "observe" -> { item.exact("op", "label"); MobilePlanStep(op, label) }
                    "wait" -> { item.exact("op", "label", "ms"); MobilePlanStep(op, label, item.integer("ms", 100, 5000).toLong()) }
                    "assertColor", "tapColor" -> {
                        item.exact("op", "label", "region", "rgb", "tolerance", "minPixels")
                        val r = item.getJSONObject("region")
                        r.exact("left", "top", "right", "bottom")
                        val region = PlanRegion(r.number("left", .02, .98), r.number("top", .02, .98),
                            r.number("right", .02, .98), r.number("bottom", .02, .98))
                        require(region.right - region.left >= .01 && region.bottom - region.top >= .01) { "Invalid region" }
                        val rgb = item.getJSONArray("rgb")
                        require(rgb.length() == 3)
                        val channels = (0..2).map { channel ->
                            val n = (rgb.get(channel) as? Number)?.toDouble() ?: error("RGB channel must be numeric")
                            require(n.isFinite() && n in 0.0..255.0 && n % 1.0 == 0.0)
                            n.toInt()
                        }
                        MobilePlanStep(op, label, guard = ColorGuard(region, channels,
                            item.integer("tolerance", 0, 40), item.integer("minPixels", 4, 400)))
                    }
                    else -> error("Unsupported operation: $op")
                }
            }
            val plan = MobilePlan(root.text("title", 80), root.text("purpose", 400),
                root.integer("maxRuntimeMs", 1000, 120000).toLong(), steps)
            require(plan.maxTaps <= 12) { "At most 12 taps per plan" }
            require(steps.sumOf { it.ms } < plan.maxRuntimeMs) { "Wait budget exceeds runtime" }
            return plan
        }
    }
}

internal data class PlanTarget(val x: Double, val y: Double, val matchingPixels: Int)

/** Raw RGB matcher on a <=512px image. Ambiguous or background-dominated matches fail closed. */
internal object PlanColorMatcher {
    fun find(width: Int, height: Int, pixels: IntArray, guard: ColorGuard): PlanTarget? {
        require(width in 1..512 && height in 1..512 && pixels.size == width * height)
        val l = (guard.region.left * width).toInt().coerceIn(0, width - 1)
        val t = (guard.region.top * height).toInt().coerceIn(0, height - 1)
        val r = (guard.region.right * width).toInt().coerceIn(l + 1, width)
        val b = (guard.region.bottom * height).toInt().coerceIn(t + 1, height)
        val mask = BooleanArray(width * height)
        var total = 0
        for (y in t until b) for (x in l until r) {
            val color = pixels[y * width + x]
            if (abs(((color shr 16) and 255) - guard.rgb[0]) <= guard.tolerance &&
                abs(((color shr 8) and 255) - guard.rgb[1]) <= guard.tolerance &&
                abs((color and 255) - guard.rgb[2]) <= guard.tolerance) {
                mask[y * width + x] = true; total++
            }
        }
        if (total < guard.minPixels || total > (r - l) * (b - t) * .35) return null
        val queue = IntArray(total)
        var largest = IntArray(0)
        var secondSize = 0
        for (y in t until b) for (x in l until r) {
            val first = y * width + x
            if (!mask[first]) continue
            var head = 0; var tail = 1; queue[0] = first; mask[first] = false
            while (head < tail) {
                val index = queue[head++]; val px = index % width; val py = index / width
                fun visit(nx: Int, ny: Int) {
                    if (nx in l until r && ny in t until b) {
                        val n = ny * width + nx
                        if (mask[n]) { mask[n] = false; queue[tail++] = n }
                    }
                }
                visit(px - 1, py); visit(px + 1, py); visit(px, py - 1); visit(px, py + 1)
            }
            if (tail > largest.size) { secondSize = largest.size; largest = queue.copyOf(tail) }
            else if (tail > secondSize) secondSize = tail
        }
        if (largest.size < guard.minPixels || secondSize >= largest.size * .85) return null
        val cx = largest.sumOf { (it % width).toDouble() } / largest.size
        val cy = largest.sumOf { (it / width).toDouble() } / largest.size
        val point = largest.minBy { val dx = it % width - cx; val dy = it / width - cy; dx * dx + dy * dy }
        return PlanTarget(((point % width) + .5) / width, ((point / width) + .5) / height, largest.size)
    }
}
