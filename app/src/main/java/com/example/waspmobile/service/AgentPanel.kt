package com.example.waspmobile.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bounded native overlay. Watch mode is non-focusable; editing explicitly takes input ownership. */
internal class AgentPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val desk: AgentDeskState,
    private val takeControl: () -> Unit,
    private val allowInput: () -> Unit,
    private val approve: (String, String) -> Unit,
    private val shareToChatGPT: (String) -> Unit
) {
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var connection: TextView? = null
    private var transcript: TextView? = null
    private var planText: TextView? = null
    private var composer: EditText? = null
    private var approveButton: Button? = null
    private var rejectButton: Button? = null
    private var state = AgentDeskSnapshot()
    private var bridgeStatus = PhoneMcpBridge.Status()
    var isEditing = false
        private set
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 12f) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(226, 232, 240)); setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(text: String, action: () -> Unit) = Button(context).apply {
        this.text = text; textSize = 11f; isAllCaps = false; minHeight = dp(40); minimumHeight = dp(40)
        setOnClickListener { action() }
    }
    fun show() {
        if (root != null) return
        val bounds = windowManager.maximumWindowMetrics.bounds
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(248, 15, 23, 42)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(56, 189, 248))
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("OSRS AGENT · ONE PHONE", 14f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Close") { close() })
        panel.addView(header)
        connection = label(""); panel.addView(connection)
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Take control / type") { edit() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("Watch / allow input") { watch() }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(controls)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        transcript = label(""); content.addView(transcript)
        planText = label("").apply { typeface = Typeface.MONOSPACE }; content.addView(planText)
        val approval = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        approveButton = button("Approve this plan") {
            val p = state.proposal ?: return@button
            approve(p.id, p.sha256)
        }
        rejectButton = button("Reject") { state.proposal?.let { desk.finish(it.id, "REJECTED", "Rejected locally") } }
        approval.addView(approveButton, LinearLayout.LayoutParams(0, -2, 1f))
        approval.addView(rejectButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(approval)
        val scroll = ScrollView(context).apply { addView(content); isFillViewport = false }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        composer = EditText(context).apply {
            hint = "Take control to type a request"; setHintTextColor(Color.rgb(148, 163, 184)); setTextColor(Color.WHITE)
            textSize = 12f; minLines = 1; maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(1000)); isEnabled = false
        }
        panel.addView(composer)
        val sendRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        sendRow.addView(button("Send to MCP inbox") {
            val typed = composer?.text?.toString().orEmpty()
            if (typed.isNotBlank()) takeControl()
            if (desk.queue(typed)) composer?.setText("")
            else connection?.text = "Connect first; request must be 1–1000 characters and inbox must have space."
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sendRow.addView(button("Share to ChatGPT") {
            takeControl()
            shareToChatGPT(composer?.text?.toString().orEmpty())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(sendRow)
        panel.addView(label("OSRS/WaspMobile mailbox, not embedded ChatGPT. Open this OSRS conversation; sending does not start an agent turn.", 10f))
        val p = WindowManager.LayoutParams(
            minOf(dp(380), (bounds.width() * .48).toInt()).coerceAtLeast(minOf(dp(240), bounds.width())),
            (bounds.height() * .88).toInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT; y = (bounds.height() * .06).toInt(); x = dp(6)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(panel, p)
        root = panel; params = p; isEditing = false
        render(desk.state.value, PhoneMcpBridge.status.value)
    }
    fun edit() {
        takeControl(); isEditing = true
        val r = root ?: return; val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager.updateViewLayout(r, p)
        composer?.isEnabled = true
        composer?.requestFocus()
        composer?.post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun watch() {
        val r = root
        if (r != null) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0)
            composer?.clearFocus(); composer?.isEnabled = false
            params?.let { p -> p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windowManager.updateViewLayout(r, p) }
        }
        isEditing = false; allowInput()
    }
    fun contains(x: Int, y: Int): Boolean {
        val r = root ?: return false
        val location = IntArray(2); r.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + r.width, location[1] + r.height).contains(x, y)
    }
    fun render(snapshot: AgentDeskSnapshot, status: PhoneMcpBridge.Status) {
        state = snapshot; bridgeStatus = status
        connection?.text = status.message + (if (isEditing) " · INPUT PAUSED WHILE TYPING" else "") + if (snapshot.agentActive) " · MCP agent recently active" else " · Waiting for MCP agent"
        transcript?.text = snapshot.messages.joinToString("\n\n") { message ->
            val who = if (message.role == "user") "You" else if (message.role == "agent") "Connected MCP agent" else "System"
            "$who${if (message.queued) " (queued)" else ""}: ${message.text}"
        }.ifBlank { snapshot.notice }
        val p = snapshot.proposal
        planText?.text = if (p != null) {
            val details = p.plan.steps.mapIndexed { i, step ->
                val guard = step.guard
                "${i + 1}. ${step.op}: ${step.label}" + (if (step.op == "wait") " (${step.ms} ms)" else "") +
                    (if (guard != null) "\nROI ${guard.region}; RGB ${guard.rgb}, tolerance ${guard.tolerance}, minimum ${guard.minPixels}" else "")
            }.joinToString("\n")
            "PROPOSED: ${p.plan.title}\n${p.plan.purpose}\nLimit: ${p.plan.maxRuntimeMs / 1000}s / ${p.plan.maxTaps} taps\n" +
                "SHA-256: ${p.sha256}\n$details\nColor matching does not identify valuable items or prove the game goal."
        } else snapshot.progress?.let { "${it.title}\n${it.status}: ${it.step}/${it.steps}\n${it.detail}" }.orEmpty()
        approveButton?.isEnabled = p != null && status.connected && SystemClock.elapsedRealtime() < p.expiresAt
        rejectButton?.isEnabled = p != null
    }
    fun close() {
        val r = root ?: return
        if (isEditing) takeControl() // Closing the editor never silently resumes input.
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0)
        runCatching { windowManager.removeView(r) }
        root = null; params = null; composer = null; connection = null; transcript = null; planText = null
        approveButton = null; rejectButton = null; isEditing = false
    }
}
