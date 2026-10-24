package com.example.waspmobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.waspmobile.engine.CalibrationMode
import com.example.waspmobile.engine.HumanTouchEngine
import com.example.waspmobile.engine.MobileVisionEngine
import com.example.waspmobile.engine.VerifiedInventoryDrop
import com.example.waspmobile.engine.TargetColorPreset
import com.example.waspmobile.engine.WaspMobileState
import com.example.waspmobile.engine.WaspScriptMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class WaspAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WaspAccessibilityService? = null
            private set

        fun closeOverlayAndDisableService() {
            instance?.shutdownAndDisable()
        }

        /** User-initiated only. Never automatically enables remote control on reboot. */
        fun setMcpRemoteEnabled(enabled: Boolean): Boolean {
            val svc = instance ?: return false
            return if (enabled) {
                svc.stopBotLoop()
                svc.userInputHold = false
                svc.phoneMcpBridge?.start() == true
            } else {
                svc.phoneMcpBridge?.stop()
                true
            }
        }

        fun isMcpRemoteEnabled(): Boolean = instance?.phoneMcpBridge?.enabled == true
    }

    private lateinit var windowManager: WindowManager
    private var canvasOverlayView: VisualOverlayCanvas? = null
    private var canvasParams: WindowManager.LayoutParams? = null
    private var pillRootView: LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val screenshotMutex = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var botLoopJob: Job? = null
    private var manualActionJob: Job? = null

    private val touchEngine = HumanTouchEngine()
    private val visionEngine = MobileVisionEngine()
    private var phoneMcpBridge: PhoneMcpBridge? = null
    internal val remoteGate = RemoteSessionGate { SystemClock.elapsedRealtime() }
    private var remoteGeometryRevision = 0L
    internal val agentDesk = AgentDeskState { SystemClock.elapsedRealtime() }
    private var agentPanel: AgentPanel? = null
    private var mobilePlanJob: Job? = null
    private var userInputHold = false
    @Volatile private var lastRelayContactMs = 0L

    @Volatile
    internal var currentForegroundPackage: String = ""
        private set

    internal fun remoteScreenWidth(): Int = getPhysicalScreenBounds().width()
    internal fun remoteScreenHeight(): Int = getPhysicalScreenBounds().height()

    @Volatile
    private var suppressCanvasDrawing = false
    private var lastScreenshotMs = 0L
    private val isSamplingColor = AtomicBoolean(false)

    private var isCollapsed = false
    private var dualWindowToggle = false
    private var lastTapWinA: PointF? = null
    private var lastTapWinB: PointF? = null
    private var lastGatherClickMs = 0L
    private var lastFilledSlotsCount = 0

    // UI references inside the floating pill
    private var statusTextView: TextView? = null
    private var subStatusTextView: TextView? = null
    private var startStopBtn: Button? = null
    private var modeBtn: Button? = null
    private var gridBtnRef: Button? = null
    private var colorSwatchView: View? = null
    private var expandedBodyLayout: LinearLayout? = null
    private var calibrationToolbarLayout: LinearLayout? = null
    private val modeChips = mutableMapOf<String, TextView>()
    private val presetChips = mutableMapOf<TargetColorPreset, TextView>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        WaspMobileState.loadPrefs(this)
        WaspMobileState.update { it.copy(serviceConnected = true, statusText = "READY (Overlay Active)") }
        WaspMobileState.log("Accessibility Overlay connected over OSRS Mobile.")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupVisualCanvasOverlay()
        setupFloatingControlPill()
        setupDisplayListener()

        phoneMcpBridge = PhoneMcpBridge(this, serviceScope, ::executeRemoteMcpCommand)
        agentPanel = AgentPanel(this, windowManager, agentDesk, ::takeManualControl, {
            userInputHold = false
            remoteGate.invalidateFrame()
        }, ::approveMobilePlan, ::shareAgentRequest)
        serviceScope.launch {
            agentDesk.state.collect { snapshot ->
                agentPanel?.render(snapshot, PhoneMcpBridge.status.value)
                refreshPrimaryButton()
                snapshot.progress?.let { subStatusTextView?.text = "${it.status}: ${it.step}/${it.steps} ${it.title}" }
            }
        }
        serviceScope.launch {
            PhoneMcpBridge.status.collect { status -> agentPanel?.render(agentDesk.state.value, status); refreshPrimaryButton() }
        }

        serviceScope.launch {
            WaspMobileState.state.collect { state ->
                updateOverlayFromState(state.isRunning, state.calibrationMode)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        agentPanel?.close()
        cancelMobilePlan("Display configuration changed")
        super.onConfigurationChanged(newConfig)
        remoteGeometryRevision++
        remoteGate.invalidateFrame()
        mainHandler.post {
            val pill = pillRootView
            val params = pillParams
            if (pill != null && params != null) {
                clampPillToScreen(params, pill)
                runCatching { windowManager.updateViewLayout(pill, params) }
            }
            canvasOverlayView?.invalidate()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            remoteGate.invalidateFrame()
            currentForegroundPackage = event.packageName?.toString() ?: ""
        }
    }

    override fun onInterrupt() {
        phoneMcpBridge?.stop()
        stopBotLoop()
    }

    override fun onDestroy() {
        agentPanel?.close()
        agentPanel = null
        phoneMcpBridge?.stop()
        phoneMcpBridge = null
        stopBotLoop()
        instance = null
        displayListener?.let {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
        WaspMobileState.savePrefs(this)
        WaspMobileState.update { it.copy(serviceConnected = false, isRunning = false) }
        canvasOverlayView?.let { runCatching { windowManager.removeView(it) } }
        canvasOverlayView = null
        pillRootView?.let { runCatching { windowManager.removeView(it) } }
        pillRootView = null
        serviceScope.cancel()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun shutdownAndDisable() {
        agentPanel?.close()
        agentPanel = null
        phoneMcpBridge?.stop()
        phoneMcpBridge = null
        stopBotLoop()
        displayListener?.let {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
        mainHandler.post {
            canvasOverlayView?.let { runCatching { windowManager.removeView(it) } }
            canvasOverlayView = null
            pillRootView?.let { runCatching { windowManager.removeView(it) } }
            pillRootView = null
        }
        WaspMobileState.savePrefs(this)
        WaspMobileState.update {
            it.copy(
                serviceConnected = false,
                isRunning = false,
                statusText = "DISABLED (Closed)"
            )
        }
        WaspMobileState.log("Overlay closed via [✕]. AccessibilityService disabled.")
        disableSelf()
    }

    private fun setupDisplayListener() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    remoteGeometryRevision++
                    remoteGate.invalidateFrame()
                    mainHandler.post {
                        val pill = pillRootView
                        val params = pillParams
                        if (pill != null && params != null) {
                            clampPillToScreen(params, pill)
                            runCatching { windowManager.updateViewLayout(pill, params) }
                        }
                        canvasOverlayView?.invalidate()
                    }
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun getPhysicalScreenBounds(): Rect {
        return runCatching {
            windowManager.maximumWindowMetrics.bounds
        }.getOrElse {
            runCatching {
                windowManager.currentWindowMetrics.bounds
            }.getOrElse {
                val dm = resources.displayMetrics
                Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }
        }
    }

    private fun clampPillToScreen(params: WindowManager.LayoutParams, view: View) {
        val bounds = getPhysicalScreenBounds()
        val viewW = view.width.coerceAtLeast(dp(140))
        val viewH = view.height.coerceAtLeast(dp(44))
        val maxX = max(0, bounds.width() - viewW)
        val maxY = max(0, bounds.height() - viewH)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun setupVisualCanvasOverlay() {
        val view = VisualOverlayCanvas(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        canvasOverlayView = view
        canvasParams = params
        runCatching { windowManager.addView(view, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControlPill() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(232, 11, 17, 30))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(56, 189, 248))
            }
            background = bg
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // Top Header Bar (Draggable + Collapse button)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val swatch = View(this).apply {
            val sBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(WaspMobileState.state.value.targetColorRgb)
                setStroke(dp(1), Color.WHITE)
            }
            background = sBg
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                rightMargin = dp(6)
            }
        }
        colorSwatchView = swatch

        val titleTv = TextView(this).apply {
            text = "🐝 WASP MOBILE"
            setTextColor(Color.rgb(56, 189, 248))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val collapseBtn = TextView(this).apply {
            text = " [–] "
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                isCollapsed = !isCollapsed
                expandedBodyLayout?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
                text = if (isCollapsed) " [+] " else " [–] "
            }
        }

        val closeBtn = TextView(this).apply {
            text = " [✕] "
            setTextColor(Color.rgb(239, 68, 68))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener {
                shutdownAndDisable()
            }
        }

        header.addView(swatch)
        header.addView(titleTv)
        header.addView(makePillButton("AI", Color.rgb(3, 105, 161)) {
            if (WaspMobileState.state.value.isRunning || manualActionJob?.isActive == true) takeManualControl()
            runCatching { agentPanel?.show() }.onFailure { WaspMobileState.log("Agent panel could not open: ${it.javaClass.simpleName}") }
        })
        header.addView(collapseBtn)
        header.addView(closeBtn)
        root.addView(header)

        // Expanded Body
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        expandedBodyLayout = body

        val statusTv = TextView(this).apply {
            text = "IDLE (Ready)"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        statusTextView = statusTv
        body.addView(statusTv)

        val subTv = TextView(this).apply {
            text = "Inv: 0/27 |FOCUSED| Act: 0"
            setTextColor(Color.rgb(16, 185, 129))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        subStatusTextView = subTv
        body.addView(subTv)

        // 1-Tap Script Mode Chips Bar (Horizontal Scroll)
        val modeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
        }
        val modeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modeChips.clear()
        val modeItems = listOf(
            "🌲 Woodcut" to {
                WaspMobileState.update {
                    it.copy(
                        scriptMode = WaspScriptMode.WOODCUTTER_DROP,
                        colorPreset = TargetColorPreset.TREE_CANOPY,
                        targetColorRgb = TargetColorPreset.TREE_CANOPY.rgb,
                        colorTolerance = TargetColorPreset.TREE_CANOPY.tolerance,
                        statusText = "Mode: 🌲 Woodcut (Leafy Tree)"
                    )
                }
                WaspMobileState.savePrefs(this@WaspAccessibilityService)
                WaspMobileState.log("Switched mode to 🌲 Woodcut (Tree Canopy)")
            },
            "⛏️ Miner" to {
                WaspMobileState.update {
                    it.copy(
                        scriptMode = WaspScriptMode.POWER_MINER,
                        colorPreset = TargetColorPreset.IRON_ROCK,
                        targetColorRgb = TargetColorPreset.IRON_ROCK.rgb,
                        colorTolerance = TargetColorPreset.IRON_ROCK.tolerance,
                        statusText = "Mode: ⛏️ Miner (Iron)"
                    )
                }
                WaspMobileState.savePrefs(this@WaspAccessibilityService)
                WaspMobileState.log("Switched mode to ⛏️ Miner (Iron Rock)")
            },
            "🎣 Fisher" to {
                WaspMobileState.update {
                    it.copy(
                        colorPreset = TargetColorPreset.FISHING_SPOT,
                        targetColorRgb = TargetColorPreset.FISHING_SPOT.rgb,
                        colorTolerance = TargetColorPreset.FISHING_SPOT.tolerance,
                        statusText = "Mode: 🎣 Fisher (Bubbles)"
                    )
                }
                WaspMobileState.savePrefs(this@WaspAccessibilityService)
                WaspMobileState.log("Switched mode to 🎣 Fisher (Fishing Bubbles)")
            },
            "✨ Combine" to {
                WaspMobileState.update {
                    it.copy(
                        scriptMode = WaspScriptMode.BANKSTAND_COMBINER,
                        statusText = "Mode: ✨ Combine"
                    )
                }
                WaspMobileState.savePrefs(this@WaspAccessibilityService)
                WaspMobileState.log("Switched mode to ✨ Combine")
            },
            "📱 Split 2x" to {
                WaspMobileState.update {
                    it.copy(
                        scriptMode = WaspScriptMode.DUAL_SPLIT_SCREEN,
                        statusText = "Mode: 📱 Split 2x"
                    )
                }
                WaspMobileState.savePrefs(this@WaspAccessibilityService)
                WaspMobileState.log("Switched mode to 📱 Split 2x")
            }
        )
        // One-device pilot: retain the legacy split mode in source for compatibility,
        // but do not expose it as a selectable runtime action.
        for ((tag, clickAction) in modeItems.filterNot { it.first == "📱 Split 2x" }) {
            val chip = makeChip(tag, false) { clickAction() }
            modeChips[tag] = chip
            modeLayout.addView(chip)
        }
        modeScroll.addView(modeLayout)
        body.addView(modeScroll)

        // Quick Target Preset Bar (Horizontal Scroll)
        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(4) }
        }
        val presetLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        presetChips.clear()
        val presets = listOf(
            TargetColorPreset.TREE_CANOPY,
            TargetColorPreset.TREE_TRUNK,
            TargetColorPreset.DEAD_TREE,
            TargetColorPreset.IRON_ROCK,
            TargetColorPreset.COPPER_ROCK,
            TargetColorPreset.TIN_ROCK,
            TargetColorPreset.COAL_ROCK,
            TargetColorPreset.FISHING_SPOT
        )
        for (preset in presets) {
            val chip = makeChip(preset.label, false) {
                WaspMobileState.update {
                    it.copy(
                        colorPreset = preset,
                        targetColorRgb = preset.rgb,
                        colorTolerance = preset.tolerance,
                        statusText = "Preset: ${preset.label}"
                    )
                }
                WaspMobileState.savePrefs(this@WaspAccessibilityService)
                WaspMobileState.log("Selected target preset: ${preset.label}")
            }
            presetChips[preset] = chip
            presetLayout.addView(chip)
        }
        presetScroll.addView(presetLayout)
        body.addView(presetScroll)

        // Action Buttons Row: Start/Pause + Eyedropper Color Lock + Grid Calibrator + Instant Drop + Reset
        val rowAction = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        val runBtn = makePillButton("▶ START", Color.rgb(16, 185, 129)) {
            when (primaryAction()) {
                AgentPrimaryAction.STOP_PLAN, AgentPrimaryAction.PAUSE_REMOTE -> takeManualControl()
                AgentPrimaryAction.ALLOW_REMOTE -> { userInputHold = false; remoteGate.invalidateFrame(); refreshPrimaryButton() }
                AgentPrimaryAction.STOP_LOCAL -> stopBotLoop()
                AgentPrimaryAction.START_LOCAL -> startBotLoop()
            }
        }
        startStopBtn = runBtn

        val pickColorBtn = makePillButton("🎯 Color", Color.rgb(124, 58, 237)) {
            val cur = WaspMobileState.state.value.calibrationMode
            if (cur == CalibrationMode.PICK_TARGET_COLOR) {
                sampleAndLockCrosshairColor()
            } else {
                stopBotLoop()
                WaspMobileState.update {
                    it.copy(
                        calibrationMode = CalibrationMode.PICK_TARGET_COLOR,
                        statusText = "Tap or drag-release on game screen to lock color"
                    )
                }
            }
        }

        val gridBtn = makePillButton("📐 Grid", Color.rgb(71, 85, 105)) {
            val cur = WaspMobileState.state.value.calibrationMode
            if (cur == CalibrationMode.ADJUST_SEARCH_BOX || cur == CalibrationMode.ADJUST_INVENTORY_GRID) {
                WaspMobileState.update {
                    it.copy(calibrationMode = CalibrationMode.NONE, statusText = "✅ Calibration Saved!")
                }
                WaspMobileState.savePrefs(this)
                WaspMobileState.log("Saved Search & 28-Slot Inventory calibration.")
            } else {
                stopBotLoop()
                val next = WaspMobileState.cycleCalibrationMode()
                if (next == CalibrationMode.NONE) {
                    WaspMobileState.log("Saved Search & 28-Slot Inventory calibration.")
                }
            }
        }
        gridBtnRef = gridBtn

        val dropNowBtn = makePillButton("🎒 Drop", Color.rgb(217, 119, 6)) {
            if (agentPanel?.isEditing == true) {
                WaspMobileState.log("Leave the agent editor before using the local dropper.")
                return@makePillButton
            }
            agentPanel?.close()
            if (botLoopJob?.isActive == true || manualActionJob?.isActive == true) {
                stopBotLoop()
            }
            phoneMcpBridge?.stop()
            manualActionJob = serviceScope.launch {
                WaspMobileState.update { it.copy(isRunning = true, statusText = "SMART TAP-DROPPING...") }
                try {
                    executeSmartInventoryDrop()
                } finally {
                    WaspMobileState.update { it.copy(isRunning = false, lastTapPoint = null) }
                    mainHandler.post { canvasOverlayView?.invalidate() }
                }
            }
        }

        val resetBoxesBtn = makePillButton("🔄 Reset", Color.rgb(71, 85, 105)) {
            WaspMobileState.resetBoxesToDefaults()
            WaspMobileState.savePrefs(this)
            WaspMobileState.update { it.copy(statusText = "Reset boxes to S23 OSRS defaults!") }
            WaspMobileState.log("Reset Search Box and Inventory Grid to S23 OSRS Mobile defaults.")
            mainHandler.post { canvasOverlayView?.invalidate() }
        }

        rowAction.addView(runBtn)
        rowAction.addView(pickColorBtn)
        rowAction.addView(gridBtn)
        rowAction.addView(dropNowBtn)
        rowAction.addView(resetBoxesBtn)
        body.addView(rowAction)

        // Calibration Sub-Bar (Active when adjusting boxes)
        val calToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val searchBoxChip = makeChip("3D Search", false) {
            WaspMobileState.update {
                it.copy(
                    calibrationMode = CalibrationMode.ADJUST_SEARCH_BOX,
                    statusText = "Drag Green 3D Search Box corners/edges or center"
                )
            }
        }
        val invGridChip = makeChip("Inv Grid", false) {
            WaspMobileState.update {
                it.copy(
                    calibrationMode = CalibrationMode.ADJUST_INVENTORY_GRID,
                    statusText = "Drag Orange 28-Slot Inventory Grid"
                )
            }
        }
        val resetBoxChip = makeChip("🔄 Reset Boxes", false) {
            WaspMobileState.resetBoxesToDefaults()
            WaspMobileState.savePrefs(this@WaspAccessibilityService)
            WaspMobileState.update { it.copy(statusText = "Reset boxes to S23 OSRS defaults!") }
            mainHandler.post { canvasOverlayView?.invalidate() }
        }
        val doneCalChip = makeChip("✅ Done", true) {
            WaspMobileState.update {
                it.copy(calibrationMode = CalibrationMode.NONE, statusText = "✅ Calibration Saved!")
            }
            WaspMobileState.savePrefs(this@WaspAccessibilityService)
            WaspMobileState.log("Saved Search & 28-Slot Inventory calibration.")
        }
        calToolbar.addView(searchBoxChip)
        calToolbar.addView(invGridChip)
        calToolbar.addView(resetBoxChip)
        calToolbar.addView(doneCalChip)
        calibrationToolbarLayout = calToolbar
        body.addView(calToolbar)

        root.addView(body)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(24)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Allow dragging the pill by its header while clamping to current physical screen bounds
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (ev.rawX - touchX).toInt()
                    params.y = initialY + (ev.rawY - touchY).toInt()
                    clampPillToScreen(params, root)
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }

        pillRootView = root
        pillParams = params
        runCatching { windowManager.addView(root, params) }
    }

    private fun makeChip(text: String, isSelected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            applyChipStyle(this, isSelected)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(4) }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun applyChipStyle(view: TextView, isSelected: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            if (isSelected) {
                setColor(Color.rgb(2, 132, 199))
                setStroke(dp(1), Color.rgb(56, 189, 248))
            } else {
                setColor(Color.rgb(30, 41, 59))
                setStroke(dp(1), Color.rgb(51, 65, 85))
            }
        }
        view.background = bg
    }

    private fun makePillButton(label: String, bgColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(5), dp(8), dp(5))
            val bg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    private fun updateOverlayFromState(isRunning: Boolean, calMode: CalibrationMode) {
        mainHandler.post {
            val s = WaspMobileState.state.value
            statusTextView?.text = s.statusText
            subStatusTextView?.text =
                "Inv:${s.filledSlots}/${s.currentInventoryLimit} | ${s.attention.badge} | Act:${s.actionsCount}"
            modeBtn?.text = s.scriptMode.shortTag

            (colorSwatchView?.background as? GradientDrawable)?.setColor(s.targetColorRgb)

            refreshPrimaryButton()

            val isCalibratingBox = calMode == CalibrationMode.ADJUST_SEARCH_BOX || calMode == CalibrationMode.ADJUST_INVENTORY_GRID
            gridBtnRef?.apply {
                text = if (isCalibratingBox) "✅ Done" else "📐 Grid"
                (background as? GradientDrawable)?.setColor(
                    if (isCalibratingBox) Color.rgb(16, 185, 129) else Color.rgb(71, 85, 105)
                )
            }

            calibrationToolbarLayout?.visibility = if (calMode != CalibrationMode.NONE) View.VISIBLE else View.GONE

            val activeModeTag = when {
                s.colorPreset == TargetColorPreset.FISHING_SPOT -> "🎣 Fisher"
                s.scriptMode == WaspScriptMode.WOODCUTTER_DROP -> "🌲 Woodcut"
                s.scriptMode == WaspScriptMode.POWER_MINER -> "⛏️ Miner"
                s.scriptMode == WaspScriptMode.BANKSTAND_COMBINER -> "✨ Combine"
                s.scriptMode == WaspScriptMode.DUAL_SPLIT_SCREEN -> "📱 Split 2x"
                else -> s.scriptMode.shortTag
            }
            for ((tag, chip) in modeChips) {
                applyChipStyle(chip, tag == activeModeTag)
            }

            for ((preset, chip) in presetChips) {
                applyChipStyle(chip, preset == s.colorPreset)
            }

            // Toggle canvas touchability ONLY when calibrating crosshair or boxes
            val params = canvasParams
            val view = canvasOverlayView
            if (params != null && view != null) {
                val wantTouchable = calMode != CalibrationMode.NONE
                val currentlyTouchable =
                    (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
                if (wantTouchable != currentlyTouchable) {
                    params.flags = if (wantTouchable) {
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    } else {
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    }
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
                view.invalidate()
            }
        }
    }

    private fun getPillBitmapExclusionRect(bmpW: Int, bmpH: Int, dispW: Float, dispH: Float): Rect? {
        val pill = pillRootView ?: return null
        val params = pillParams ?: return null
        val pW = pill.width.coerceAtLeast(dp(140))
        val pH = pill.height.coerceAtLeast(dp(44))
        val invScaleX = if (dispW > 0f) bmpW.toFloat() / dispW else 1f
        val invScaleY = if (dispH > 0f) bmpH.toFloat() / dispH else 1f
        val pad = dp(6)
        return Rect(
            ((params.x - pad) * invScaleX).toInt().coerceAtLeast(0),
            ((params.y - pad) * invScaleY).toInt().coerceAtLeast(0),
            ((params.x + pW + pad) * invScaleX).toInt().coerceAtMost(bmpW),
            ((params.y + pH + pad) * invScaleY).toInt().coerceAtMost(bmpH)
        )
    }

    private fun sampleAndLockCrosshairColor() {
        if (!isSamplingColor.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                WaspMobileState.update { it.copy(statusText = "Sampling pixel under 🎯...") }
                val bmp = captureScreenBitmap()
                if (bmp != null) {
                    val cross = WaspMobileState.state.value.crosshairNorm
                    val sampledRgb = try {
                        withContext(Dispatchers.Default) {
                            visionEngine.sampleColorAt(bmp, cross)
                        }
                    } finally {
                        bmp.recycle()
                    }
                    WaspMobileState.update {
                        it.copy(
                            colorPreset = TargetColorPreset.CUSTOM_LOCKED,
                            targetColorRgb = sampledRgb,
                            calibrationMode = CalibrationMode.NONE,
                            statusText = "✅ Color Locked! Tap ▶ START"
                        )
                    }
                    WaspMobileState.savePrefs(this@WaspAccessibilityService)
                    WaspMobileState.log(
                        String.format("Eyedropper locked target color: #%06X", 0xFFFFFF and sampledRgb)
                    )
                } else {
                    WaspMobileState.update {
                        it.copy(
                            calibrationMode = CalibrationMode.NONE,
                            statusText = "Screenshot busy; try again"
                        )
                    }
                }
            } finally {
                isSamplingColor.set(false)
            }
        }
    }

    fun startBotLoop() {
        if (WaspMobileState.state.value.scriptMode == WaspScriptMode.DUAL_SPLIT_SCREEN) {
            WaspMobileState.update { it.copy(
                scriptMode = WaspScriptMode.WOODCUTTER_DROP,
                statusText = "SINGLE-DEVICE MODE: Split 2x unavailable"
            ) }
            WaspMobileState.savePrefs(this)
            WaspMobileState.log("Split-screen blocked: one OSRS window/character only.")
            return
        }
        if (agentPanel?.isEditing == true) {
            WaspMobileState.log("Leave the agent editor before starting a local script.")
            return
        }
        agentPanel?.close()
        phoneMcpBridge?.stop()
        if (botLoopJob?.isActive == true) return
        manualActionJob?.cancel()
        manualActionJob = null
        if (WaspMobileState.state.value.calibrationMode != CalibrationMode.NONE) {
            WaspMobileState.savePrefs(this)
        }
        touchEngine.resetSession()
        visionEngine.reset()
        lastTapWinA = null
        lastTapWinB = null
        lastFilledSlotsCount = 0
        WaspMobileState.update {
            it.copy(
                isRunning = true,
                calibrationMode = CalibrationMode.NONE,
                lastTapPoint = null,
                statusText = "STARTING (${it.scriptMode.shortTag})"
            )
        }
        WaspMobileState.log("Started ${WaspMobileState.state.value.scriptMode.title}")

        botLoopJob = serviceScope.launch {
            while (isActive && WaspMobileState.state.value.isRunning) {
                try {
                    runSingleBotIteration()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WaspMobileState.log("Loop warning: ${e.message}")
                }
                delay(Random.nextLong(720L, 1180L))
            }
        }
    }

    fun stopBotLoop(stopRemote: Boolean = true) {
        cancelMobilePlan("Automation paused")
        if (stopRemote) phoneMcpBridge?.stop()
        botLoopJob?.cancel()
        botLoopJob = null
        manualActionJob?.cancel()
        manualActionJob = null
        visionEngine.reset()
        WaspMobileState.update {
            it.copy(
                isRunning = false,
                detectedClusters = emptyList(),
                lastTapPoint = null,
                statusText = "PAUSED"
            )
        }
        mainHandler.post { canvasOverlayView?.invalidate() }
    }

    private fun primaryAction() = AgentPrimaryAction.resolve(
        planRunning = agentDesk.state.value.progress?.status == "RUNNING",
        remoteEnabled = phoneMcpBridge?.enabled == true,
        manualHold = userInputHold,
        localRunning = WaspMobileState.state.value.isRunning || manualActionJob?.isActive == true
    )
    private fun refreshPrimaryButton() {
        val action = primaryAction()
        startStopBtn?.text = action.label
        (startStopBtn?.background as? GradientDrawable)?.setColor(
            if (action.stopsInput) Color.rgb(239, 68, 68) else Color.rgb(16, 185, 129))
    }

    internal fun recordRelayContact() { lastRelayContactMs = SystemClock.elapsedRealtime() }

    internal fun acceptDeskPacket(session: String, packet: JSONObject) {
        if (agentDesk.receive(session, packet)) cancelMobilePlan("Cancelled or invalidated by bridge")
    }

    internal fun onRemoteDisconnected() {
        cancelMobilePlan("Remote session disconnected")
        agentDesk.disconnect()
        lastRelayContactMs = 0L
    }

    private fun cancelMobilePlan(reason: String) {
        mobilePlanJob?.cancel()
        mobilePlanJob = null
        agentDesk.cancel(reason)
        remoteGate.invalidateFrame()
    }

    private fun takeManualControl() {
        userInputHold = true
        cancelMobilePlan("User took control or started typing")
        botLoopJob?.cancel(); botLoopJob = null
        manualActionJob?.cancel(); manualActionJob = null
        WaspMobileState.update { it.copy(isRunning = false, statusText = "MANUAL CONTROL") }
    }

    private fun shareAgentRequest(typed: String) {
        takeManualControl()
        val state = agentDesk.state.value
        val request = typed.trim().ifBlank { state.messages.lastOrNull { it.role == "user" }?.text.orEmpty() }
        val content = "For this OSRS/WaspMobile conversation only: use the connected WaspMobile MCP tools. Device: ${state.deviceId}. " +
            "Read wasp_inbox, reply with wasp_reply, and propose any mobile script with wasp_offer_plan for my on-phone approval. " +
            "Keep this request in this OSRS conversation; do not route it to another project or agent. " +
            "Do not assume the bridge is installed or connected until the tool call succeeds. My request: $request"
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, content) }
        runCatching { startActivity(Intent.createChooser(send, "Choose ChatGPT, then this OSRS conversation")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { WaspMobileState.log("Share sheet unavailable; open ChatGPT and read the MCP inbox.") }
    }

    private fun planInputReady(session: String, windowKey: String): Boolean =
        remoteGate.isSession(session) && phoneMcpBridge?.enabled == true && !userInputHold &&
            agentPanel?.isEditing != true && !WaspMobileState.state.value.isRunning && manualActionJob?.isActive != true &&
            WaspMobileState.state.value.calibrationMode == CalibrationMode.NONE &&
            SystemClock.elapsedRealtime() - lastRelayContactMs in 0L..6000L && currentRemoteWindow()?.key == windowKey

    private suspend fun observePlanFrame(windowKey: String): PlanFrame? {
        val window = currentRemoteWindow()?.takeIf { it.key == windowKey } ?: return null
        val bitmap = captureRemoteWindow(window) ?: return null
        try {
            if (currentRemoteWindow()?.key != windowKey || bitmap.width != window.bounds.width() ||
                bitmap.height != window.bounds.height()) return null
            return withContext(Dispatchers.Default) {
                val scale = minOf(1f, 512f / bitmap.width, 512f / bitmap.height)
                val sample = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), false) else bitmap
                try {
                    val pixels = IntArray(sample.width * sample.height)
                    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                    PlanFrame(windowKey, sample.width, sample.height, pixels)
                } finally { if (sample !== bitmap) sample.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun tapPlanTarget(target: PlanTarget, session: String, windowKey: String): Boolean {
        val window = currentRemoteWindow()?.takeIf { it.key == windowKey } ?: return false
        val x = window.bounds.left + (target.x * window.bounds.width()).toInt()
        val y = window.bounds.top + (target.y * window.bounds.height()).toInt()
        fun guard(): Boolean {
            if (!planInputReady(session, windowKey) || agentPanel?.contains(x, y) == true) return false
            val current = currentRemoteWindow() ?: return false
            if (current.blocked.any { it.contains(x, y) }) return false
            val pill = pillRootView
            if (pill != null) {
                val pos = IntArray(2); pill.getLocationOnScreen(pos)
                if (Rect(pos[0], pos[1], pos[0] + pill.width, pos[1] + pill.height).contains(x, y)) return false
            }
            return true
        }
        if (!guard()) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build()
        return touchEngine.dispatchGestureAndAwait(this, gesture, 600L, ::guard)
    }

    private fun approveMobilePlan(id: String, sha256: String) {
        if (mobilePlanJob?.isActive == true || phoneMcpBridge?.enabled != true || Build.VERSION.SDK_INT < 34) return
        val offer = agentDesk.approve(id, sha256) ?: return
        val session = agentDesk.state.value.session
        agentPanel?.watch()
        userInputHold = false
        remoteGate.invalidateFrame()
        mobilePlanJob = serviceScope.launch {
            val ownJob = coroutineContext[Job]
            try {
                delay(650L) // Allow keyboard/focus to settle; actual window checks still decide readiness.
                val windowKey = currentRemoteWindow()?.key ?: error("Bring OSRS to a single unlocked foreground window")
                if (!planInputReady(session, windowKey)) error("Connection lease or input state is not ready")
                val result = withTimeout(offer.plan.maxRuntimeMs) {
                    MobilePlanRunner(object : MobilePlanDriver {
                        override fun now() = SystemClock.elapsedRealtime()
                        override fun ready(windowKey: String) = planInputReady(session, windowKey)
                        override suspend fun observe() = observePlanFrame(windowKey)
                        override suspend fun tap(target: PlanTarget, windowKey: String) = tapPlanTarget(target, session, windowKey)
                        override fun progress(step: Int, detail: String) = agentDesk.progress(id, step, detail)
                    }).run(offer.plan, windowKey)
                }
                agentDesk.finish(id, if (result.completed) "COMPLETED" else "FAILED", result.detail)
            } catch (_: TimeoutCancellationException) {
                agentDesk.finish(id, "FAILED", "Runtime budget expired; no automatic resume")
            } catch (e: CancellationException) {
                agentDesk.finish(id, "CANCELLED", "Local plan cancelled; already delivered input cannot be undone")
                throw e
            } catch (e: Exception) {
                agentDesk.finish(id, "FAILED", e.message?.take(160) ?: "Plan stopped")
            } finally {
                if (mobilePlanJob === ownJob) mobilePlanJob = null
                remoteGate.invalidateFrame()
            }
        }
    }

    private data class RemoteWindow(val id: Int, val bounds: Rect, val key: String, val blocked: List<Rect>)

    /** Query actual interactive windows; a stale package event is not authorization. */
    @Suppress("DEPRECATION")
    private fun currentRemoteWindow(): RemoteWindow? {
        val power = getSystemService(PowerManager::class.java)
        val lock = getSystemService(KeyguardManager::class.java)
        if (power?.isInteractive != true || lock?.isKeyguardLocked != false) return null
        val all = runCatching { windows }.getOrNull() ?: return null
        try {
            val applications = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            // Multi-window/Secure Folder selection is deliberately unsupported, not guessed.
            if (applications.size != 1) return null
            val game = applications.single()
            if (!game.isActive || !game.isFocused || game.displayId != Display.DEFAULT_DISPLAY) return null
            val root = game.root ?: return null
            val owner = try { root.packageName?.toString() } finally { root.recycle() }
            if (owner != "com.jagex.oldscape.android") return null
            if (all.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isActive || it.isFocused)) }) return null
            val bounds = Rect().also { game.getBoundsInScreen(it) }
            val screen = getPhysicalScreenBounds()
            if (bounds.width() < 100 || bounds.height() < 100 || !screen.contains(bounds)) return null
            val rotation = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return null
            val blocked = all.filter { it.id != game.id && it.layer > game.layer &&
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .map { window -> Rect().also { window.getBoundsInScreen(it) } }
            val key = "${game.id}:${bounds.left}:${bounds.top}:${bounds.width()}:${bounds.height()}:$rotation:$remoteGeometryRevision"
            return RemoteWindow(game.id, bounds, key, blocked)
        } finally { all.forEach { it.recycle() } }
    }

    internal fun remoteDeviceStatus(): JSONObject {
        val window = if (Build.VERSION.SDK_INT >= 34) currentRemoteWindow() else null
        return JSONObject().put("inputAllowed", !userInputHold && agentPanel?.isEditing != true && mobilePlanJob?.isActive != true)
            .put("foreground", if (window != null) "com.jagex.oldscape.android" else "")
            .put("windowKey", window?.key ?: "").put("width", window?.bounds?.width() ?: 0)
            .put("height", window?.bounds?.height() ?: 0)
            .put("status", if (window == null) "Game window unverified, locked, obscured or unsupported" else "GAME_WINDOW_READY")
    }

    /** API 34+ window-only capture: never export the full phone display to an LLM. */
    private suspend fun captureRemoteWindow(window: RemoteWindow): Bitmap? = screenshotMutex.withLock {
        if (Build.VERSION.SDK_INT < 34 || currentRemoteWindow()?.key != window.key) return@withLock null
        val elapsed = SystemClock.elapsedRealtime() - lastScreenshotMs
        if (elapsed < 450L) delay(450L - elapsed)
        coroutineContext.ensureActive()
        if (currentRemoteWindow()?.key != window.key) return@withLock null
        withTimeoutOrNull(2500L) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                val delivered = AtomicBoolean(false)
                try {
                    lastScreenshotMs = SystemClock.elapsedRealtime()
                    takeScreenshotOfWindow(window.id, screenshotExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = try {
                                screenshot.hardwareBuffer.use { buffer ->
                                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    try { hardware?.copy(Bitmap.Config.ARGB_8888, false) }
                                    finally { hardware?.recycle() }
                                }
                            } catch (_: Exception) { null }
                            if (delivered.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(bitmap, onCancellation = { _, value, _ -> value?.recycle() })
                            } else bitmap?.recycle()
                        }
                        override fun onFailure(errorCode: Int) {
                            if (delivered.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                        }
                    })
                } catch (_: Exception) {
                    if (delivered.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private suspend fun executeRemoteMcpCommand(
        id: String, type: String, params: JSONObject, session: String, deadline: Long
    ): JSONObject {
        fun rejected(message: String) = JSONObject().put("ok", false).put("error", message).put("mayHaveExecuted", false)
        fun sessionReady() = remoteGate.isSession(session) && SystemClock.elapsedRealtime() < deadline
        coroutineContext.ensureActive()
        if (!sessionReady()) return rejected("Remote session expired or disconnected")
        if (type == "PAUSE") {
            userInputHold = true
            stopBotLoop(stopRemote = false)
            remoteGate.invalidateFrame()
            return JSONObject().put("ok", true).put("status", "LOCAL_AUTOMATION_PAUSED")
        }
        if (userInputHold || agentPanel?.isEditing == true || mobilePlanJob?.isActive == true) {
            return rejected("User typing, manual control, or approved local plan owns input")
        }
        if (Build.VERSION.SDK_INT < 34) return rejected("Remote capture requires Android 14 or later")
        val state = WaspMobileState.state.value
        if (state.isRunning || manualActionJob?.isActive == true || state.calibrationMode != CalibrationMode.NONE) {
            return rejected("Local automation or calibration owns input")
        }
        val window = currentRemoteWindow() ?: return rejected("No single, unlocked foreground OSRS window")
        if (params.optString("windowKey") != window.key) return rejected("Window changed; observe again")
        return when (type) {
            "OBSERVE" -> {
                remoteGate.invalidateFrame()
                val capturedAt = SystemClock.elapsedRealtime()
                val bitmap = captureRemoteWindow(window) ?: return rejected("Window screenshot unavailable")
                try {
                    if (!sessionReady() || currentRemoteWindow()?.key != window.key ||
                        bitmap.width != window.bounds.width() || bitmap.height != window.bounds.height()) {
                        return rejected("Window changed during capture or unexpected capture dimensions")
                    }
                    val encoded = withContext(Dispatchers.Default) {
                        val scale = minOf(1f, 1024f / bitmap.width, 1024f / bitmap.height)
                        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                        try {
                            val bytes = ByteArrayOutputStream()
                            check(resized.compress(Bitmap.CompressFormat.JPEG, 68, bytes))
                            check(bytes.size() <= 650_000)
                            Triple(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP), resized.width, resized.height)
                        } finally { if (resized !== bitmap) resized.recycle() }
                    }
                    coroutineContext.ensureActive()
                    if (!sessionReady() || currentRemoteWindow()?.key != window.key ||
                        !remoteGate.recordFrame(id, window.key, capturedAt, session)) return rejected("Observation invalidated")
                    JSONObject().put("ok", true).put("status", "GAME_WINDOW_CAPTURED").put("frame", encoded.first)
                        .put("imageWidth", encoded.second).put("imageHeight", encoded.third)
                        .put("windowKey", window.key).put("width", window.bounds.width()).put("height", window.bounds.height())
                } finally { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            "TAP" -> {
                val x = (params.opt("x") as? Number)?.toDouble() ?: return rejected("Invalid x")
                val y = (params.opt("y") as? Number)?.toDouble() ?: return rejected("Invalid y")
                if (!x.isFinite() || !y.isFinite() || x !in 0.02..0.98 || y !in 0.02..0.98) return rejected("Invalid point")
                if (!remoteGate.consumeFrame(params.optString("frameId"), window.key, session)) return rejected("Fresh frame required")
                val px = window.bounds.left + (x * window.bounds.width()).toInt()
                val py = window.bounds.top + (y * window.bounds.height()).toInt()
                fun inputReady(): Boolean {
                    if (!sessionReady() || userInputHold || agentPanel?.isEditing == true ||
                        agentPanel?.contains(px, py) == true || mobilePlanJob?.isActive == true || WaspMobileState.state.value.isRunning ||
                        WaspMobileState.state.value.calibrationMode != CalibrationMode.NONE) return false
                    val current = currentRemoteWindow() ?: return false
                    if (current.key != window.key || current.blocked.any { it.contains(px, py) }) return false
                    val pill = pillRootView
                    if (pill != null && pill.visibility == View.VISIBLE) {
                        val position = IntArray(2); pill.getLocationOnScreen(position)
                        if (Rect(position[0], position[1], position[0] + pill.width, position[1] + pill.height).contains(px, py)) return false
                    }
                    return true
                }
                if (!inputReady()) return rejected("Target obscured or input ownership changed")
                val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build()
                val completed = touchEngine.dispatchGestureAndAwait(this, gesture, 600L, ::inputReady)
                JSONObject().put("ok", completed).put("mayHaveExecuted", true)
                    .put("status", if (completed) "GESTURE_COMPLETED_VERIFY_GAME" else "GESTURE_OUTCOME_UNKNOWN")
                    .put("error", if (completed) "" else "No confirmed completion; observe before further input")
            }
            else -> rejected("Unsupported operation")
        }
    }

    /**
     * Captures a clean, overlay-free ARGB_8888 Bitmap of the display:
     * 1. Temporarily suppresses `VisualOverlayCanvas` drawing with a multi-frame delay (55ms)
     *    and Mutex serialization so cluster boxes, tap dots, and crosshair lines never pollute pixel detection.
     * 2. Wraps capture in a 2500ms coroutine timeout to guard against dropped accessibility IPC callbacks.
     * 3. Respects Android `takeScreenshot` rate limits and retries up to 3 times with progressive
     *    backoff (450ms, 700ms, 1000ms) if `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` occurs.
     * 4. Safely closes the native `HardwareBuffer` and handles nulls, closed buffers, and recycling cleanly.
     */
    private suspend fun captureScreenBitmap(): Bitmap? = screenshotMutex.withLock {
        suppressCanvasDrawing = true
        mainHandler.post { canvasOverlayView?.invalidate() }
        try {
            // Wait 55ms ensures at least 2-3 display refresh cycles at 60Hz (50ms) or 6+ at 120Hz,
            // best-effort settling only; this is not a compositor synchronization guarantee
            delay(55L)

            val backoffs = longArrayOf(450L, 700L, 1000L)
            for (attempt in 0..3) {
                val elapsed = SystemClock.elapsedRealtime() - lastScreenshotMs
                if (elapsed in 0..420L) {
                    delay(430L - elapsed)
                }

                val errorReason = AtomicInteger(0)
                val bmp = withTimeoutOrNull(2500L) {
                    suspendCancellableCoroutine<Bitmap?> { cont ->
                        try {
                            lastScreenshotMs = SystemClock.elapsedRealtime()
                            takeScreenshot(
                                Display.DEFAULT_DISPLAY,
                                screenshotExecutor,
                                object : TakeScreenshotCallback {
                                    override fun onSuccess(screenshot: ScreenshotResult) {
                                        val swBitmap = try {
                                            val hwBuffer = screenshot.hardwareBuffer
                                            if (hwBuffer.isClosed) {
                                                null
                                            } else {
                                                hwBuffer.use { buf ->
                                                    val hwBitmap = Bitmap.wrapHardwareBuffer(
                                                        buf,
                                                        screenshot.colorSpace
                                                    )
                                                    try {
                                                        hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                                    } finally {
                                                        hwBitmap?.recycle()
                                                    }
                                                }
                                            }
                                        } catch (_: Throwable) {
                                            null
                                        }
                                        if (cont.isActive) {
                                            cont.resume(swBitmap, onCancellation = { _, value, _ -> value?.recycle() })
                                        } else {
                                            swBitmap?.recycle()
                                        }
                                    }

                                    override fun onFailure(errorCode: Int) {
                                        errorReason.set(errorCode)
                                        if (cont.isActive) cont.resume(null)
                                    }
                                }
                            )
                        } catch (_: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }

                if (bmp != null) return@withLock bmp
                if (errorReason.get() == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < backoffs.size) {
                    delay(backoffs[attempt])
                } else {
                    break
                }
            }
            return@withLock null
        } finally {
            suppressCanvasDrawing = false
            mainHandler.post { canvasOverlayView?.invalidate() }
        }
    }

    private suspend fun runSingleBotIteration() {
        val state = WaspMobileState.state.value
        if (state.scriptMode == WaspScriptMode.SMART_TAP_DROPPER) {
            executeSmartInventoryDrop()
            stopBotLoop()
            return
        }

        val bmp = captureScreenBitmap() ?: return
        try {
            val w = bmp.width
            val h = bmp.height
            val screenBounds = getPhysicalScreenBounds()
            val dispW = screenBounds.width().toFloat()
            val dispH = screenBounds.height().toFloat()
            val scaleX = if (w > 0) dispW / w.toFloat() else 1.0f
            val scaleY = if (h > 0) dispH / h.toFloat() else 1.0f

            val searchRect = visionEngine.normToScreenRect(state.searchBoxNorm, w, h)
            val invRect = visionEngine.normToScreenRect(state.invBoxNorm, w, h)
            val pillBitmapRect = getPillBitmapExclusionRect(w, h, dispW, dispH)

            val filledSlots = withContext(Dispatchers.Default) {
                visionEngine.detectFilledInventorySlots(bmp, invRect)
            }
            val gainedItemSinceLastTick = filledSlots.size > lastFilledSlotsCount && lastFilledSlotsCount > 0
            lastFilledSlotsCount = filledSlots.size
            WaspMobileState.update { it.copy(filledSlots = filledSlots.size) }

            // 1. Check if inventory has reached our Anti-Slot-28 threshold (25..28)
            if (filledSlots.size >= state.currentInventoryLimit &&
                (state.scriptMode == WaspScriptMode.WOODCUTTER_DROP ||
                        state.scriptMode == WaspScriptMode.POWER_MINER)
            ) {
                bmp.recycle()
                WaspMobileState.update { it.copy(statusText = "INV FULL (${filledSlots.size}) -> TAP-DROPPING") }
                delay(touchEngine.reactDelayMs(240L, 680L))
                executeSmartInventoryDrop(filledSlots, screenBounds)
                touchEngine.rollInventoryLimit()
                delay(touchEngine.actionFinishDelayMs())
                return
            }

            // 2. Bankstander / 2-Item Combiner Mode (Slot 1 + Next Filled Slot -> Make All)
            if (state.scriptMode == WaspScriptMode.BANKSTAND_COMBINER) {
                bmp.recycle()
                val materialSlotIdx = filledSlots.filter { it > 0 }.minOrNull()
                if (0 !in filledSlots || materialSlotIdx == null) {
                    WaspMobileState.update {
                        it.copy(statusText = "OUT OF MATERIALS (Need Slot 1 + >=1 item)")
                    }
                    return
                }
                val screenInvRect = visionEngine.normToScreenRect(state.invBoxNorm, screenBounds.width(), screenBounds.height())
                val slotRects = visionEngine.getInventorySlotRects(screenInvRect)
                if (materialSlotIdx in slotRects.indices) {
                    WaspMobileState.update { it.copy(statusText = "COMBINING SLOT 1 + SLOT ${materialSlotIdx + 1}") }
                    touchEngine.dispatchHumanTap(this, slotRects[0], screenBounds)
                    delay(touchEngine.reactDelayMs(220L, 580L))
                    touchEngine.dispatchHumanTap(this, slotRects[materialSlotIdx], screenBounds)
                    delay(Random.nextLong(950L, 1650L))
                    // Tap central Make-All prompt in OSRS Mobile (scaled to physical display bounds)
                    val makeBox = Rect(
                        (screenBounds.width() * 0.35f).toInt(),
                        (screenBounds.height() * 0.62f).toInt(),
                        (screenBounds.width() * 0.65f).toInt(),
                        (screenBounds.height() * 0.82f).toInt()
                    )
                    touchEngine.dispatchHumanTap(this, makeBox, screenBounds)
                    WaspMobileState.update {
                        it.copy(
                            actionsCount = it.actionsCount + 1,
                            statusText = "PROCESSING INVENTORY..."
                        )
                    }
                    delay(Random.nextLong(14_000L, 22_000L))
                    delay(touchEngine.actionFinishDelayMs())
                }
                return
            }

            // 3. Dual Split-Screen Mode (Alternates Left Window A and Right Window B)
            if (state.scriptMode == WaspScriptMode.DUAL_SPLIT_SCREEN) {
                val isWinA = !dualWindowToggle
                dualWindowToggle = !dualWindowToggle
                val halfRect = if (isWinA) {
                    Rect((w * 0.08f).toInt(), (h * 0.16f).toInt(), (w * 0.44f).toInt(), (h * 0.84f).toInt())
                } else {
                    Rect((w * 0.56f).toInt(), (h * 0.16f).toInt(), (w * 0.92f).toInt(), (h * 0.84f).toInt())
                }
                val rawClusters = withContext(Dispatchers.Default) {
                    visionEngine.findTargetClusters(bmp, halfRect, state.targetColorRgb, state.colorTolerance, pillBitmapRect)
                }
                bmp.recycle()

                val screenClusters = rawClusters.map { c ->
                    val sLeft = (c.left * scaleX).toInt().coerceIn(0, max(0, screenBounds.width() - 4))
                    val sTop = (c.top * scaleY).toInt().coerceIn(0, max(0, screenBounds.height() - 4))
                    val sRight = (c.right * scaleX).toInt().coerceIn(sLeft + 2, max(sLeft + 2, screenBounds.width()))
                    val sBottom = (c.bottom * scaleY).toInt().coerceIn(sTop + 2, max(sTop + 2, screenBounds.height()))
                    Rect(sLeft, sTop, sRight, sBottom)
                }

                WaspMobileState.update { it.copy(detectedClusters = screenClusters) }
                mainHandler.post { canvasOverlayView?.invalidate() }

                val prevWinTap = if (isWinA) lastTapWinA else lastTapWinB
                val chosen = visionEngine.pickHumanCluster(screenClusters, prevWinTap, true)
                if (chosen != null) {
                    val winLabel = if (isWinA) "WIN A" else "WIN B"
                    WaspMobileState.update { it.copy(statusText = "TAP $winLabel TARGET") }
                    delay(touchEngine.reactDelayMs(220L, 640L))
                    touchEngine.dispatchHumanTap(this, chosen, screenBounds)
                    val tappedPt = WaspMobileState.state.value.lastTapPoint
                    if (isWinA) lastTapWinA = tappedPt else lastTapWinB = tappedPt
                    mainHandler.post { canvasOverlayView?.invalidate() }
                    WaspMobileState.update { it.copy(actionsCount = it.actionsCount + 1) }
                    delay(Random.nextLong(2200L, 4800L))
                } else {
                    WaspMobileState.update { it.copy(statusText = "SCANNING DUAL WINDOWS...") }
                }
                return
            }

            // 4. Woodcutter & Power-Miner: Check if already chopping/mining
            val animating = withContext(Dispatchers.Default) {
                visionEngine.isPlayerAnimating(bmp, searchRect)
            } || gainedItemSinceLastTick
            val sinceLastClick = System.currentTimeMillis() - lastGatherClickMs
            val maxWaitWindow = if (state.scriptMode == WaspScriptMode.WOODCUTTER_DROP) 11_500L else 4_800L

            if (animating && sinceLastClick < maxWaitWindow) {
                bmp.recycle()
                WaspMobileState.update {
                    it.copy(
                        statusText = if (it.scriptMode == WaspScriptMode.WOODCUTTER_DROP) "CHOPPING TREE..." else "MINING VEIN..."
                    )
                }
                // 6% chance during active gather to do a subtle camera micro-swipe (in physical screen coordinates)
                if (Random.nextDouble() < 0.06) {
                    val screenSearchRect = visionEngine.normToScreenRect(state.searchBoxNorm, screenBounds.width(), screenBounds.height())
                    touchEngine.dispatchCameraMicroSwipe(this, screenSearchRect, screenBounds)
                }
                return
            }

            // 5. Find target color clusters inside the 3D Search Box (excluding floating pill)
            val rawClusters = withContext(Dispatchers.Default) {
                visionEngine.findTargetClusters(
                    bmp,
                    searchRect,
                    state.targetColorRgb,
                    state.colorTolerance,
                    pillBitmapRect
                )
            }
            bmp.recycle()

            val screenClusters = rawClusters.map { c ->
                val sLeft = (c.left * scaleX).toInt().coerceIn(0, max(0, screenBounds.width() - 4))
                val sTop = (c.top * scaleY).toInt().coerceIn(0, max(0, screenBounds.height() - 4))
                val sRight = (c.right * scaleX).toInt().coerceIn(sLeft + 2, max(sLeft + 2, screenBounds.width()))
                val sBottom = (c.bottom * scaleY).toInt().coerceIn(sTop + 2, max(sTop + 2, screenBounds.height()))
                Rect(sLeft, sTop, sRight, sBottom)
            }

            WaspMobileState.update { it.copy(detectedClusters = screenClusters) }
            mainHandler.post { canvasOverlayView?.invalidate() }

            val avoidLast = state.scriptMode == WaspScriptMode.POWER_MINER ||
                    state.scriptMode == WaspScriptMode.WOODCUTTER_DROP
            val targetCluster = visionEngine.pickHumanCluster(screenClusters, state.lastTapPoint, avoidLast)

            if (targetCluster != null) {
                delay(touchEngine.reactDelayMs(190L, 580L))
                WaspMobileState.update {
                    it.copy(
                        statusText = "TAPPING TARGET (${screenClusters.size} found)",
                        actionsCount = it.actionsCount + 1
                    )
                }
                touchEngine.dispatchHumanTap(this, targetCluster, screenBounds)
                lastGatherClickMs = System.currentTimeMillis()
                mainHandler.post { canvasOverlayView?.invalidate() }

                // Wait initial gather recognition + Markov attention delay
                val settleMs = if (state.scriptMode == WaspScriptMode.WOODCUTTER_DROP) {
                    Random.nextLong(2400L, 4600L)
                } else {
                    Random.nextLong(1600L, 3200L)
                }
                delay(settleMs)
                delay(touchEngine.actionFinishDelayMs())
            } else {
                WaspMobileState.update { it.copy(statusText = "NO TARGET COLOR IN 3D BOX") }
            }
        } finally {
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    private suspend fun executeSmartInventoryDrop(
        knownFilledSlots: Set<Int>? = null,
        knownScreenBounds: Rect? = null
    ) {
        val state = WaspMobileState.state.value
        val screenBounds = getPhysicalScreenBounds()
        if (currentRemoteWindow() == null) return
        var activeFilledSlots: Set<Int>? = null

        // Capture a clean screenshot to inspect Backpack tab, Tap-to-Drop hotkey, and slot occupancy
        val checkBmp = captureScreenBitmap() ?: run {
            WaspMobileState.log("Inventory screenshot unavailable; drop cancelled.")
            WaspMobileState.update { it.copy(statusText = "DROP PAUSED: NO SCREENSHOT") }
            return
        }
        var tabWasClosed = false
        var dropWasInactive = false
        try {
                // 1. Check if Backpack tab is open; if not, tap Backpack tab (0.925f, 0.495f) and wait 300ms
                val isTabOpen = withContext(Dispatchers.Default) {
                    visionEngine.isInventoryTabOpen(checkBmp)
                }
                if (!isTabOpen) {
                    tabWasClosed = true
                    WaspMobileState.update { it.copy(statusText = "OPENING BACKPACK TAB...") }
                    val tabBox = Rect(
                        (screenBounds.width() * (0.925f - 0.010f)).toInt(),
                        (screenBounds.height() * (0.495f - 0.010f)).toInt(),
                        (screenBounds.width() * (0.925f + 0.010f)).toInt(),
                        (screenBounds.height() * (0.495f + 0.010f)).toInt()
                    )
                    if (!touchEngine.dispatchHumanTap(this, tabBox, screenBounds)) {
                        WaspMobileState.log("Backpack tab tap did not complete; drop cancelled.")
                        return
                    }
                    delay(300L)
                }

                // 2. Check if Tap-to-Drop hotkey is active; if not, tap hotkey (0.064f, 0.566f) and wait 250ms
                val isDropActive = withContext(Dispatchers.Default) {
                    visionEngine.isTapToDropActive(checkBmp)
                }
                if (!isDropActive) {
                    dropWasInactive = true
                    WaspMobileState.update { it.copy(statusText = "ACTIVATING TAP-TO-DROP...") }
                    val hotkeyBox = Rect(
                        (screenBounds.width() * (0.064f - 0.010f)).toInt(),
                        (screenBounds.height() * (0.566f - 0.010f)).toInt(),
                        (screenBounds.width() * (0.064f + 0.010f)).toInt(),
                        (screenBounds.height() * (0.566f + 0.010f)).toInt()
                    )
                    if (!touchEngine.dispatchHumanTap(this, hotkeyBox, screenBounds)) {
                        WaspMobileState.log("Tap-to-Drop toggle did not complete; drop cancelled.")
                        return
                    }
                    delay(250L)
                }

                if (activeFilledSlots == null && !tabWasClosed) {
                    val bmpInvRect = visionEngine.normToScreenRect(
                        state.invBoxNorm,
                        checkBmp.width,
                        checkBmp.height
                    )
                    activeFilledSlots = withContext(Dispatchers.Default) {
                        visionEngine.detectFilledInventorySlots(checkBmp, bmpInvRect)
                    }
                }
        } finally {
            if (!checkBmp.isRecycled) checkBmp.recycle()
        }

        // Any UI change invalidates previous slot coordinates and detection.
        // Confirm both controls and refresh occupancy before a destructive action.
        run { // Always re-observe, even when the caller supplied an older scan.
            val freshBmp = captureScreenBitmap() ?: run {
                WaspMobileState.log("Unable to verify inventory after UI change; drop cancelled.")
                WaspMobileState.update { it.copy(statusText = "DROP PAUSED: VERIFY SCREENSHOT") }
                return
            }
            try {
                val controlsReady = withContext(Dispatchers.Default) {
                    visionEngine.isInventoryTabOpen(freshBmp) &&
                        visionEngine.isTapToDropActive(freshBmp)
                }
                if (!controlsReady) {
                    WaspMobileState.log("Backpack or Tap-to-Drop state unverified; drop cancelled.")
                    WaspMobileState.update { it.copy(statusText = "DROP PAUSED: CONTROLS UNKNOWN") }
                    return
                }
                val bmpInvRect = visionEngine.normToScreenRect(
                    state.invBoxNorm, freshBmp.width, freshBmp.height
                )
                activeFilledSlots = withContext(Dispatchers.Default) {
                    visionEngine.detectFilledInventorySlots(freshBmp, bmpInvRect)
                }
            } finally {
                if (!freshBmp.isRecycled) freshBmp.recycle()
            }
        }

        val dropWindow = currentRemoteWindow() ?: return
        val screenInvRect = visionEngine.normToScreenRect(
            state.invBoxNorm,
            screenBounds.width(),
            screenBounds.height()
        )
        val slotRects = visionEngine.getInventorySlotRects(screenInvRect)

        val protectedSlots = mutableSetOf<Int>()
        if (state.protectSlot1) protectedSlots.add(0)
        if (state.protectSlot2) protectedSlots.add(1)

        val dropOrder = touchEngine.generateHumanDropOrder(protectedSlots)
        val slotsToTap = VerifiedInventoryDrop.plan(activeFilledSlots, dropOrder, protectedSlots)
        if (slotsToTap == null) {
            WaspMobileState.log("Occupied inventory slots are unknown or invalid; drop cancelled.")
            WaspMobileState.update { it.copy(statusText = "DROP PAUSED: INVENTORY UNKNOWN") }
            return
        }

        if (slotsToTap.isEmpty()) {
            WaspMobileState.log("Inventory already clear (0 droppable slots).")
            WaspMobileState.update {
                it.copy(
                    statusText = "INV CLEAR (Nothing to drop)",
                    filledSlots = activeFilledSlots?.size ?: 0
                )
            }
            return
        }

        WaspMobileState.log("Dropping inventory (${slotsToTap.size} slots) in humanized pattern...")

        for (slotIdx in slotsToTap) {
            if (!coroutineContext.isActive || !WaspMobileState.state.value.isRunning ||
                currentRemoteWindow()?.key != dropWindow.key) {
                return
            }
            if (slotIdx in slotRects.indices) {
                if (!touchEngine.dispatchHumanTap(this, slotRects[slotIdx], screenBounds)) {
                    WaspMobileState.log("Slot ${slotIdx + 1} gesture cancelled or timed out; remaining drops skipped.")
                    WaspMobileState.update { it.copy(statusText = "DROP PAUSED: GESTURE UNCERTAIN") }
                    return
                }
                mainHandler.post { canvasOverlayView?.invalidate() }
                delay(Random.nextLong(65L, 175L))
            }
        }
        // Dispatch completion is not proof that the game removed those items.
        // Next capture must establish the observed occupancy.
        lastFilledSlotsCount = -1
        WaspMobileState.update { it.copy(statusText = "DROP INPUT SENT: VERIFY NEXT SCAN") }
    }

    /**
     * Full-screen overlay canvas that renders:
     * - 3D Target Search Region (green dashed rectangle) with 4 draggable corner & 4 edge handles
     * - 28-Slot OSRS Mobile Inventory Grid (orange 4x7 slots) with interactive adjustment handles
     * - Live detected target cluster bounding boxes
     * - Interactive 🎯 Eyedropper crosshair with hollow center aperture (tap directly on screen to sample)
     */
    inner class VisualOverlayCanvas(context: Context) : View(context) {

        private val screenOffset = IntArray(2)
        private var activeDrag: DragTarget = DragTarget.NONE
        private var downRawX = 0f
        private var downRawY = 0f
        private var initialBoxNorm = RectF()

        private val searchBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(180, 16, 185, 129)
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }

        private val invBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(170, 245, 158, 11)
            strokeWidth = 2.5f
        }

        private val clusterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(230, 56, 189, 248)
            strokeWidth = 4f
        }

        private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(190, 244, 63, 94)
        }

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(168, 85, 247)
            strokeWidth = 5f
        }

        private val handleCornerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f
        }

        private val activeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(56, 189, 248)
            strokeWidth = 5f
        }

        private val panHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(35, 56, 189, 248)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (suppressCanvasDrawing) return

            val s = WaspMobileState.state.value
            val physBounds = getPhysicalScreenBounds()
            val w = physBounds.width()
            val h = physBounds.height()
            if (w <= 0 || h <= 0) return

            getLocationOnScreen(screenOffset)
            canvas.save()
            canvas.translate(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())

            // Draw 3D Search Box & Inventory Grid when running or calibrating
            if (s.isRunning || s.calibrationMode != CalibrationMode.NONE) {
                val sRect = visionEngine.normToScreenRect(s.searchBoxNorm, w, h)
                canvas.drawRect(sRect, searchBoxPaint)

                val iRect = visionEngine.normToScreenRect(s.invBoxNorm, w, h)
                canvas.drawRect(iRect, invBoxPaint)

                if (s.calibrationMode == CalibrationMode.ADJUST_INVENTORY_GRID) {
                    for (slot in visionEngine.getInventorySlotRects(iRect)) {
                        canvas.drawRect(slot, invBoxPaint)
                    }
                }

                // Render solid touch handles on the actively calibrated box
                if (s.calibrationMode == CalibrationMode.ADJUST_SEARCH_BOX ||
                    s.calibrationMode == CalibrationMode.ADJUST_INVENTORY_GRID
                ) {
                    val activeRect = if (s.calibrationMode == CalibrationMode.ADJUST_SEARCH_BOX) sRect else iRect
                    val accentColor = if (s.calibrationMode == CalibrationMode.ADJUST_SEARCH_BOX) {
                        Color.rgb(16, 185, 129)
                    } else {
                        Color.rgb(245, 158, 11)
                    }
                    handleCornerFillPaint.color = accentColor

                    if (activeDrag == DragTarget.BODY_PAN) {
                        canvas.drawRect(activeRect, panHighlightPaint)
                    }

                    val handleR = dp(8).toFloat()

                    // 4 Corner handles
                    val corners = listOf(
                        Pair(activeRect.left.toFloat(), activeRect.top.toFloat()) to DragTarget.TOP_LEFT,
                        Pair(activeRect.right.toFloat(), activeRect.top.toFloat()) to DragTarget.TOP_RIGHT,
                        Pair(activeRect.left.toFloat(), activeRect.bottom.toFloat()) to DragTarget.BOTTOM_LEFT,
                        Pair(activeRect.right.toFloat(), activeRect.bottom.toFloat()) to DragTarget.BOTTOM_RIGHT
                    )

                    for ((pt, target) in corners) {
                        canvas.drawCircle(pt.first, pt.second, handleR, handleCornerFillPaint)
                        canvas.drawCircle(pt.first, pt.second, handleR, handleStrokePaint)
                        if (activeDrag == target) {
                            canvas.drawCircle(pt.first, pt.second, handleR + dp(4).toFloat(), activeHandlePaint)
                        }
                    }

                    // Mid-edge notch markers
                    val midX = activeRect.exactCenterX()
                    val midY = activeRect.exactCenterY()
                    val notchHalf = dp(8).toFloat()

                    // Top edge notch
                    canvas.drawLine(midX - notchHalf, activeRect.top.toFloat(), midX + notchHalf, activeRect.top.toFloat(), handleStrokePaint)
                    if (activeDrag == DragTarget.EDGE_TOP) {
                        canvas.drawLine(activeRect.left.toFloat(), activeRect.top.toFloat(), activeRect.right.toFloat(), activeRect.top.toFloat(), activeHandlePaint)
                    }

                    // Bottom edge notch
                    canvas.drawLine(midX - notchHalf, activeRect.bottom.toFloat(), midX + notchHalf, activeRect.bottom.toFloat(), handleStrokePaint)
                    if (activeDrag == DragTarget.EDGE_BOTTOM) {
                        canvas.drawLine(activeRect.left.toFloat(), activeRect.bottom.toFloat(), activeRect.right.toFloat(), activeRect.bottom.toFloat(), activeHandlePaint)
                    }

                    // Left edge notch
                    canvas.drawLine(activeRect.left.toFloat(), midY - notchHalf, activeRect.left.toFloat(), midY + notchHalf, handleStrokePaint)
                    if (activeDrag == DragTarget.EDGE_LEFT) {
                        canvas.drawLine(activeRect.left.toFloat(), activeRect.top.toFloat(), activeRect.left.toFloat(), activeRect.bottom.toFloat(), activeHandlePaint)
                    }

                    // Right edge notch
                    canvas.drawLine(activeRect.right.toFloat(), midY - notchHalf, activeRect.right.toFloat(), midY + notchHalf, handleStrokePaint)
                    if (activeDrag == DragTarget.EDGE_RIGHT) {
                        canvas.drawLine(activeRect.right.toFloat(), activeRect.top.toFloat(), activeRect.right.toFloat(), activeRect.bottom.toFloat(), activeHandlePaint)
                    }
                }
            }

            // Draw detected 3D target clusters
            for (cluster in s.detectedClusters) {
                canvas.drawRect(cluster, clusterPaint)
            }

            // Draw last Gaussian tap indicator
            s.lastTapPoint?.let { pt ->
                canvas.drawCircle(pt.x, pt.y, 11f, tapPaint)
            }

            // Draw interactive Eyedropper Crosshair with hollow center aperture (so game pixels are unpolluted)
            if (s.calibrationMode == CalibrationMode.PICK_TARGET_COLOR) {
                val cx = s.crosshairNorm.x * w
                val cy = s.crosshairNorm.y * h
                canvas.drawCircle(cx, cy, 34f, crosshairPaint)
                canvas.drawLine(cx - 54f, cy, cx - 12f, cy, crosshairPaint)
                canvas.drawLine(cx + 12f, cy, cx + 54f, cy, crosshairPaint)
                canvas.drawLine(cx, cy - 54f, cx, cy - 12f, crosshairPaint)
                canvas.drawLine(cx, cy + 12f, cx, cy + 54f, crosshairPaint)
            }

            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val s = WaspMobileState.state.value
            val physBounds = getPhysicalScreenBounds()
            val w = physBounds.width().toFloat().coerceAtLeast(1f)
            val h = physBounds.height().toFloat().coerceAtLeast(1f)

            return when (s.calibrationMode) {
                CalibrationMode.NONE -> false
                CalibrationMode.PICK_TARGET_COLOR -> {
                    val nx = (event.rawX / w).coerceIn(0.02f, 0.98f)
                    val ny = (event.rawY / h).coerceIn(0.02f, 0.98f)
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            WaspMobileState.update { it.copy(crosshairNorm = PointF(nx, ny)) }
                            invalidate()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            WaspMobileState.update { it.copy(crosshairNorm = PointF(nx, ny)) }
                            invalidate()
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            WaspMobileState.update { it.copy(crosshairNorm = PointF(nx, ny)) }
                            invalidate()
                            sampleAndLockCrosshairColor()
                            true
                        }
                        else -> true
                    }
                }
                CalibrationMode.ADJUST_SEARCH_BOX, CalibrationMode.ADJUST_INVENTORY_GRID -> {
                    val isSearch = s.calibrationMode == CalibrationMode.ADJUST_SEARCH_BOX
                    val curNorm = if (isSearch) s.searchBoxNorm else s.invBoxNorm

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            initialBoxNorm = RectF(curNorm)
                            val boxPx = RectF(
                                curNorm.left * w,
                                curNorm.top * h,
                                curNorm.right * w,
                                curNorm.bottom * h
                            )
                            val cornerRadius = dp(36).toFloat()
                            val edgeThreshold = dp(24).toFloat()
                            activeDrag = CalibrationUXHelper.hitTest(
                                event.rawX,
                                event.rawY,
                                boxPx,
                                cornerRadius,
                                edgeThreshold
                            )
                            invalidate()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (activeDrag != DragTarget.NONE) {
                                val dNormX = (event.rawX - downRawX) / w
                                val dNormY = (event.rawY - downRawY) / h
                                val newBox = CalibrationUXHelper.applyDrag(
                                    initialBoxNorm,
                                    activeDrag,
                                    dNormX,
                                    dNormY
                                )
                                if (isSearch) {
                                    WaspMobileState.update { it.copy(searchBoxNorm = newBox) }
                                } else {
                                    WaspMobileState.update { it.copy(invBoxNorm = newBox) }
                                }
                                invalidate()
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            activeDrag = DragTarget.NONE
                            WaspMobileState.savePrefs(context)
                            invalidate()
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }
}

enum class DragTarget {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    EDGE_LEFT,
    EDGE_TOP,
    EDGE_RIGHT,
    EDGE_BOTTOM,
    BODY_PAN
}

object CalibrationUXHelper {
    fun hitTest(
        rawX: Float,
        rawY: Float,
        boxRectPx: RectF,
        cornerRadiusPx: Float,
        edgeThresholdPx: Float
    ): DragTarget {
        fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
            kotlin.math.hypot(x1 - x2, y1 - y2)

        return when {
            dist(rawX, rawY, boxRectPx.left, boxRectPx.top) <= cornerRadiusPx -> DragTarget.TOP_LEFT
            dist(rawX, rawY, boxRectPx.right, boxRectPx.top) <= cornerRadiusPx -> DragTarget.TOP_RIGHT
            dist(rawX, rawY, boxRectPx.left, boxRectPx.bottom) <= cornerRadiusPx -> DragTarget.BOTTOM_LEFT
            dist(rawX, rawY, boxRectPx.right, boxRectPx.bottom) <= cornerRadiusPx -> DragTarget.BOTTOM_RIGHT
            rawX in (boxRectPx.left..boxRectPx.right) && abs(rawY - boxRectPx.top) <= edgeThresholdPx -> DragTarget.EDGE_TOP
            rawX in (boxRectPx.left..boxRectPx.right) && abs(rawY - boxRectPx.bottom) <= edgeThresholdPx -> DragTarget.EDGE_BOTTOM
            rawY in (boxRectPx.top..boxRectPx.bottom) && abs(rawX - boxRectPx.left) <= edgeThresholdPx -> DragTarget.EDGE_LEFT
            rawY in (boxRectPx.top..boxRectPx.bottom) && abs(rawX - boxRectPx.right) <= edgeThresholdPx -> DragTarget.EDGE_RIGHT
            rawX in (boxRectPx.left..boxRectPx.right) && rawY in (boxRectPx.top..boxRectPx.bottom) -> DragTarget.BODY_PAN
            else -> DragTarget.NONE
        }
    }

    fun applyDrag(
        initialBoxNorm: RectF,
        target: DragTarget,
        dNormX: Float,
        dNormY: Float,
        minDim: Float = 0.08f,
        minBound: Float = 0.01f,
        maxBound: Float = 0.99f
    ): RectF {
        val iLeft = initialBoxNorm.left
        val iTop = initialBoxNorm.top
        val iRight = initialBoxNorm.right
        val iBottom = initialBoxNorm.bottom

        return when (target) {
            DragTarget.TOP_LEFT -> {
                val maxLeft = max(minBound, iRight - minDim)
                val maxTop = max(minBound, iBottom - minDim)
                val newLeft = (iLeft + dNormX).coerceIn(minBound, maxLeft)
                val newTop = (iTop + dNormY).coerceIn(minBound, maxTop)
                RectF().apply { left = newLeft; top = newTop; right = iRight; bottom = iBottom }
            }
            DragTarget.TOP_RIGHT -> {
                val minRight = min(maxBound, iLeft + minDim)
                val maxTop = max(minBound, iBottom - minDim)
                val newRight = (iRight + dNormX).coerceIn(minRight, maxBound)
                val newTop = (iTop + dNormY).coerceIn(minBound, maxTop)
                RectF().apply { left = iLeft; top = newTop; right = newRight; bottom = iBottom }
            }
            DragTarget.BOTTOM_LEFT -> {
                val maxLeft = max(minBound, iRight - minDim)
                val minBottom = min(maxBound, iTop + minDim)
                val newLeft = (iLeft + dNormX).coerceIn(minBound, maxLeft)
                val newBottom = (iBottom + dNormY).coerceIn(minBottom, maxBound)
                RectF().apply { left = newLeft; top = iTop; right = iRight; bottom = newBottom }
            }
            DragTarget.BOTTOM_RIGHT -> {
                val minRight = min(maxBound, iLeft + minDim)
                val minBottom = min(maxBound, iTop + minDim)
                val newRight = (iRight + dNormX).coerceIn(minRight, maxBound)
                val newBottom = (iBottom + dNormY).coerceIn(minBottom, maxBound)
                RectF().apply { left = iLeft; top = iTop; right = newRight; bottom = newBottom }
            }
            DragTarget.EDGE_LEFT -> {
                val maxLeft = max(minBound, iRight - minDim)
                val newLeft = (iLeft + dNormX).coerceIn(minBound, maxLeft)
                RectF().apply { left = newLeft; top = iTop; right = iRight; bottom = iBottom }
            }
            DragTarget.EDGE_TOP -> {
                val maxTop = max(minBound, iBottom - minDim)
                val newTop = (iTop + dNormY).coerceIn(minBound, maxTop)
                RectF().apply { left = iLeft; top = newTop; right = iRight; bottom = iBottom }
            }
            DragTarget.EDGE_RIGHT -> {
                val minRight = min(maxBound, iLeft + minDim)
                val newRight = (iRight + dNormX).coerceIn(minRight, maxBound)
                RectF().apply { left = iLeft; top = iTop; right = newRight; bottom = iBottom }
            }
            DragTarget.EDGE_BOTTOM -> {
                val minBottom = min(maxBound, iTop + minDim)
                val newBottom = (iBottom + dNormY).coerceIn(minBottom, maxBound)
                RectF().apply { left = iLeft; top = iTop; right = iRight; bottom = newBottom }
            }
            DragTarget.BODY_PAN -> {
                val boxW = iRight - iLeft
                val boxH = iBottom - iTop
                val maxPanLeft = max(minBound, maxBound - boxW)
                val maxPanTop = max(minBound, maxBound - boxH)
                val targetLeft = (iLeft + dNormX).coerceIn(minBound, maxPanLeft)
                val targetTop = (iTop + dNormY).coerceIn(minBound, maxPanTop)
                RectF().apply { left = targetLeft; top = targetTop; right = targetLeft + boxW; bottom = targetTop + boxH }
            }
            DragTarget.NONE -> RectF().apply { left = iLeft; top = iTop; right = iRight; bottom = iBottom }
        }
    }
}
