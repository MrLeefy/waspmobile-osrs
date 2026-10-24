package com.example.waspmobile.engine

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class WaspScriptMode(val title: String, val shortTag: String, val description: String) {
    WOODCUTTER_DROP(
        "🌲 Woodcutter & Tap-Dropper",
        "WC+Drop",
        "Finds tree color clusters in 3D box, taps with Gaussian thumb-roll, waits for chop completion, and tap-drops at 25..28 slots."
    ),
    POWER_MINER(
        "⛏️ Power-Miner (Anti-Same-Rock)",
        "Miner",
        "Finds rock veins (Iron/Copper/Coal/Mithril), excludes just-depleted vein to hop to adjacent rock immediately, and tap-drops at 25..28 slots."
    ),
    SMART_TAP_DROPPER(
        "🎒 Smart 28-Slot Tap-Dropper",
        "TapDrop",
        "Clears filled inventory slots using randomized human zig-zag/snake patterns and biometric thumb-hold durations."
    ),
    BANKSTAND_COMBINER(
        "✨ Bankstander / Combiner / Alcher",
        "Combine",
        "Combines Slot 1 + Slot 2 (e.g. Knife + Logs or Chisel + Gems), taps Mobile Make-All, and waits with Markov attention breaks."
    ),
    DUAL_SPLIT_SCREEN(
        "📱 Dual Split-Screen (Win A ↔ Win B)",
        "Dual2x",
        "Alternates humanized target taps between Left OSRS Mobile and Right OSRS Mobile (Samsung Secure Folder / Multi-Window)."
    )
}

/** Scope the first release to one OSRS window/character, retaining the old enum for preference compatibility. */
internal object SingleCharacterPolicy {
    val selectableModes: List<WaspScriptMode> =
        WaspScriptMode.entries.filter { it != WaspScriptMode.DUAL_SPLIT_SCREEN }

    fun normalize(mode: WaspScriptMode): WaspScriptMode =
        if (mode == WaspScriptMode.DUAL_SPLIT_SCREEN) WaspScriptMode.WOODCUTTER_DROP else mode

    fun next(current: WaspScriptMode): WaspScriptMode {
        val index = selectableModes.indexOf(normalize(current))
        return selectableModes[(index + 1) % selectableModes.size]
    }
}

private fun packColor(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b

enum class TargetColorPreset(
    val label: String,
    val rgb: Int,
    val tolerance: Int
) {
    IRON_ROCK("Iron Rocks (Rust Brown)", packColor(112, 69, 52), 26),
    COPPER_ROCK("Copper Rocks (Orange-Bronze)", packColor(158, 102, 58), 28),
    TIN_ROCK("Tin Rocks (Warm Grey)", packColor(132, 126, 122), 24),
    COAL_ROCK("Coal Rocks (Dark Charcoal)", packColor(54, 54, 50), 22),
    MITHRIL_ROCK("Mithril Rocks (Indigo Blue)", packColor(66, 74, 118), 26),
    TREE_CANOPY("Oak / Willow / Yew Canopy (Leaf Green)", packColor(40, 46, 15), 16),
    TREE_TRUNK("Tree Trunk (Bark Brown)", packColor(88, 68, 36), 16),
    DEAD_TREE("Dead Tree (Charcoal Brown)", packColor(36, 31, 20), 14),
    FISHING_SPOT("Fishing Bubbles (Cyan-White)", packColor(155, 202, 218), 28),
    CUSTOM_LOCKED("🎯 Custom Eyedropper Color", packColor(112, 69, 52), 26);

    val packedRgb: Int get() = rgb
    val defaultTolerance: Float get() = tolerance.toFloat()
}

enum class AttentionState(val badge: String) {
    FOCUSED("FOCUSED"),
    CASUAL("CASUAL"),
    SECOND_MONITOR("AFK_2ND_MON")
}

enum class CalibrationMode {
    NONE,
    PICK_TARGET_COLOR,
    ADJUST_SEARCH_BOX,
    ADJUST_INVENTORY_GRID
}

internal fun makeRectF(l: Float, t: Float, r: Float, b: Float): RectF =
    RectF().apply { left = l; top = t; right = r; bottom = b }

internal fun makePointF(xVal: Float, yVal: Float): PointF =
    PointF().apply { x = xVal; y = yVal }

data class WaspTelemetry(
    val serviceConnected: Boolean = false,
    val isRunning: Boolean = false,
    val scriptMode: WaspScriptMode = WaspScriptMode.WOODCUTTER_DROP,
    val colorPreset: TargetColorPreset = TargetColorPreset.TREE_CANOPY,
    val targetColorRgb: Int = TargetColorPreset.TREE_CANOPY.rgb,
    val colorTolerance: Int = TargetColorPreset.TREE_CANOPY.tolerance,
    val attention: AttentionState = AttentionState.FOCUSED,
    val statusText: String = "IDLE (Ready)",
    val actionsCount: Int = 0,
    val filledSlots: Int = 0,
    val currentInventoryLimit: Int = 27,
    val protectSlot1: Boolean = true,
    val protectSlot2: Boolean = false,
    val calibrationMode: CalibrationMode = CalibrationMode.NONE,
    // Normalized 0..1 screen coordinates (adapts automatically to any phone resolution)
    val searchBoxNorm: RectF = makeRectF(0.18f, 0.14f, 0.70f, 0.88f),
    val invBoxNorm: RectF = makeRectF(0.731f, 0.468f, 0.901f, 0.975f),
    val crosshairNorm: PointF = makePointF(0.42f, 0.48f),
    val detectedClusters: List<Rect> = emptyList(),
    val lastTapPoint: PointF? = null,
    val logs: List<String> = listOf("WaspMobile Engine v5 initialized. Enable Accessibility Overlay to start.")
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaspTelemetry) return false
        if (serviceConnected != other.serviceConnected) return false
        if (isRunning != other.isRunning) return false
        if (scriptMode != other.scriptMode) return false
        if (colorPreset != other.colorPreset) return false
        if (targetColorRgb != other.targetColorRgb) return false
        if (colorTolerance != other.colorTolerance) return false
        if (attention != other.attention) return false
        if (statusText != other.statusText) return false
        if (actionsCount != other.actionsCount) return false
        if (filledSlots != other.filledSlots) return false
        if (currentInventoryLimit != other.currentInventoryLimit) return false
        if (protectSlot1 != other.protectSlot1) return false
        if (protectSlot2 != other.protectSlot2) return false
        if (calibrationMode != other.calibrationMode) return false
        if (searchBoxNorm.left != other.searchBoxNorm.left ||
            searchBoxNorm.top != other.searchBoxNorm.top ||
            searchBoxNorm.right != other.searchBoxNorm.right ||
            searchBoxNorm.bottom != other.searchBoxNorm.bottom) return false
        if (invBoxNorm.left != other.invBoxNorm.left ||
            invBoxNorm.top != other.invBoxNorm.top ||
            invBoxNorm.right != other.invBoxNorm.right ||
            invBoxNorm.bottom != other.invBoxNorm.bottom) return false
        if (crosshairNorm.x != other.crosshairNorm.x ||
            crosshairNorm.y != other.crosshairNorm.y) return false
        if (detectedClusters.size != other.detectedClusters.size) return false
        for (i in detectedClusters.indices) {
            val c1 = detectedClusters[i]
            val c2 = other.detectedClusters[i]
            if (c1.left != c2.left || c1.top != c2.top || c1.right != c2.right || c1.bottom != c2.bottom) return false
        }
        val p1 = lastTapPoint
        val p2 = other.lastTapPoint
        if (p1 == null && p2 != null) return false
        if (p1 != null && p2 == null) return false
        if (p1 != null && p2 != null && (p1.x != p2.x || p1.y != p2.y)) return false
        if (logs != other.logs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = serviceConnected.hashCode()
        result = 31 * result + isRunning.hashCode()
        result = 31 * result + scriptMode.hashCode()
        result = 31 * result + colorPreset.hashCode()
        result = 31 * result + targetColorRgb
        result = 31 * result + colorTolerance
        result = 31 * result + attention.hashCode()
        result = 31 * result + statusText.hashCode()
        result = 31 * result + actionsCount
        result = 31 * result + filledSlots
        result = 31 * result + currentInventoryLimit
        result = 31 * result + protectSlot1.hashCode()
        result = 31 * result + protectSlot2.hashCode()
        result = 31 * result + calibrationMode.hashCode()
        result = 31 * result + searchBoxNorm.left.hashCode()
        result = 31 * result + searchBoxNorm.top.hashCode()
        result = 31 * result + searchBoxNorm.right.hashCode()
        result = 31 * result + searchBoxNorm.bottom.hashCode()
        result = 31 * result + invBoxNorm.left.hashCode()
        result = 31 * result + invBoxNorm.top.hashCode()
        result = 31 * result + invBoxNorm.right.hashCode()
        result = 31 * result + invBoxNorm.bottom.hashCode()
        result = 31 * result + crosshairNorm.x.hashCode()
        result = 31 * result + crosshairNorm.y.hashCode()
        result = 31 * result + detectedClusters.size
        result = 31 * result + (lastTapPoint?.x?.hashCode() ?: 0)
        result = 31 * result + (lastTapPoint?.y?.hashCode() ?: 0)
        result = 31 * result + logs.hashCode()
        return result
    }
}

object WaspMobileState {
    const val PREFS_NAME = "waspmobile_prefs"
    private val _state = MutableStateFlow(WaspTelemetry())
    val state: StateFlow<WaspTelemetry> = _state.asStateFlow()

    @Volatile
    private var prefsLoaded = false
    @Volatile
    private var appContext: Context? = null

    val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    fun formatTimestamp(time: LocalTime = LocalTime.now()): String = time.format(timeFormatter)

    @Synchronized
    fun loadPrefs(context: Context, force: Boolean = false) {
        appContext = context.applicationContext
        if (prefsLoaded && !force) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedModeName = prefs.getString("script_mode", WaspScriptMode.WOODCUTTER_DROP.name)
        val rawSavedMode = runCatching {
            WaspScriptMode.valueOf(savedModeName ?: WaspScriptMode.WOODCUTTER_DROP.name)
        }.getOrDefault(WaspScriptMode.WOODCUTTER_DROP)
        val savedMode = SingleCharacterPolicy.normalize(rawSavedMode)

        val savedPresetName = prefs.getString("color_preset", TargetColorPreset.TREE_CANOPY.name)
        val savedPreset = runCatching {
            TargetColorPreset.valueOf(savedPresetName ?: TargetColorPreset.TREE_CANOPY.name)
        }.getOrDefault(TargetColorPreset.TREE_CANOPY)

        val savedRgb = prefs.getInt("target_rgb", savedPreset.rgb) or -0x1000000
        val savedTol = prefs.getInt("color_tol", savedPreset.tolerance).coerceIn(10, 48)
        val savedProt1 = prefs.getBoolean("protect_slot1", true)
        val savedProt2 = prefs.getBoolean("protect_slot2", false)

        var sLeft = prefs.getFloat("search_l", 0.18f).coerceIn(0.01f, 0.85f)
        var sTop = prefs.getFloat("search_t", 0.14f).coerceIn(0.01f, 0.85f)
        var sRight = prefs.getFloat("search_r", 0.70f).coerceIn(sLeft + 0.08f, 0.99f)
        var sBottom = prefs.getFloat("search_b", 0.88f).coerceIn(sTop + 0.08f, 0.99f)

        var iLeft = prefs.getFloat("inv_l", 0.731f).coerceIn(0.01f, 0.88f)
        var iTop = prefs.getFloat("inv_t", 0.468f).coerceIn(0.01f, 0.82f)
        var iRight = prefs.getFloat("inv_r", 0.901f).coerceIn(iLeft + 0.06f, 0.99f)
        var iBottom = prefs.getFloat("inv_b", 0.975f).coerceIn(iTop + 0.08f, 0.99f)

        var migrated = rawSavedMode != savedMode
        if (abs(iTop - 0.24f) < 0.005f) {
            iLeft = 0.731f
            iTop = 0.468f
            iRight = 0.901f
            iBottom = 0.975f
            migrated = true
        }
        if (abs(sLeft - 0.14f) < 0.005f && abs(sTop - 0.12f) < 0.005f) {
            sLeft = 0.18f
            sTop = 0.14f
            sRight = 0.70f
            sBottom = 0.88f
            migrated = true
        }

        val crossX = prefs.getFloat("cross_x", 0.42f).coerceIn(0.02f, 0.98f)
        val crossY = prefs.getFloat("cross_y", 0.48f).coerceIn(0.02f, 0.98f)

        _state.update { current ->
            current.copy(
                scriptMode = savedMode,
                colorPreset = savedPreset,
                targetColorRgb = savedRgb,
                colorTolerance = savedTol,
                protectSlot1 = savedProt1,
                protectSlot2 = savedProt2,
                searchBoxNorm = makeRectF(sLeft, sTop, sRight, sBottom),
                invBoxNorm = makeRectF(iLeft, iTop, iRight, iBottom),
                crosshairNorm = makePointF(crossX, crossY)
            )
        }
        prefsLoaded = true
        if (migrated) {
            savePrefs(context)
        }
    }

    fun resetBoxesToDefaults() {
        _state.update { current ->
            current.copy(
                searchBoxNorm = makeRectF(0.18f, 0.14f, 0.70f, 0.88f),
                invBoxNorm = makeRectF(0.731f, 0.468f, 0.901f, 0.975f)
            )
        }
        savePrefs()
    }

    @Synchronized
    fun savePrefs(context: Context? = null) {
        val targetContext = context?.applicationContext ?: appContext ?: return
        val s = _state.value
        targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("script_mode", s.scriptMode.name)
            .putString("color_preset", s.colorPreset.name)
            .putInt("target_rgb", s.targetColorRgb or -0x1000000)
            .putInt("color_tol", s.colorTolerance)
            .putBoolean("protect_slot1", s.protectSlot1)
            .putBoolean("protect_slot2", s.protectSlot2)
            .putFloat("search_l", s.searchBoxNorm.left)
            .putFloat("search_t", s.searchBoxNorm.top)
            .putFloat("search_r", s.searchBoxNorm.right)
            .putFloat("search_b", s.searchBoxNorm.bottom)
            .putFloat("inv_l", s.invBoxNorm.left)
            .putFloat("inv_t", s.invBoxNorm.top)
            .putFloat("inv_r", s.invBoxNorm.right)
            .putFloat("inv_b", s.invBoxNorm.bottom)
            .putFloat("cross_x", s.crosshairNorm.x)
            .putFloat("cross_y", s.crosshairNorm.y)
            .apply()
    }

    fun update(transform: (WaspTelemetry) -> WaspTelemetry) {
        _state.update(transform)
    }

    fun cycleScriptMode(): WaspScriptMode {
        var newMode = WaspScriptMode.WOODCUTTER_DROP
        _state.update { current ->
            newMode = SingleCharacterPolicy.next(current.scriptMode)
            current.copy(scriptMode = newMode)
        }
        savePrefs()
        return newMode
    }

    fun cycleCalibrationMode(): CalibrationMode {
        var newMode = CalibrationMode.NONE
        _state.update { current ->
            newMode = when (current.calibrationMode) {
                CalibrationMode.NONE -> CalibrationMode.ADJUST_SEARCH_BOX
                CalibrationMode.ADJUST_SEARCH_BOX -> CalibrationMode.ADJUST_INVENTORY_GRID
                else -> CalibrationMode.NONE
            }
            val status = when (newMode) {
                CalibrationMode.ADJUST_SEARCH_BOX -> "Drag Green 3D Search Box corners/edges or center"
                CalibrationMode.ADJUST_INVENTORY_GRID -> "Drag Orange 28-Slot Inventory Grid"
                else -> "Calibration Saved!"
            }
            current.copy(calibrationMode = newMode, statusText = status)
        }
        savePrefs()
        return newMode
    }

    fun log(msg: String) {
        val stamp = formatTimestamp()
        val line = "[$stamp] $msg"
        _state.update { current ->
            current.copy(logs = (current.logs + line).takeLast(40))
        }
    }
}
