package com.example.waspmobile.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One accessible OSRS side panel: Chat / Connection / Plan. No app switch is needed to configure it. */
internal class AgentPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val desk: AgentDeskState,
    private val takeControl: () -> Unit,
    private val allowInput: () -> Unit,
    private val approve: (String, String) -> Unit,
    @Suppress("unused") private val shareToChatGPT: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("osrs_overlay_ui", Context.MODE_PRIVATE)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var deliveryText: TextView? = null
    private var transcript: LinearLayout? = null
    private var transcriptScroll: ScrollView? = null
    private var composer: EditText? = null
    private var sendButton: Button? = null
    private var connectionButton: Button? = null
    private var urlInput: EditText? = null
    private var tokenInput: EditText? = null
    private var saveFeedback: TextView? = null
    private var savedTokenLabel: TextView? = null
    private var rememberChoice: CheckBox? = null
    private var planText: TextView? = null
    private var approveButton: Button? = null
    private var rejectButton: Button? = null
    private var tabsRow: LinearLayout? = null
    private var controlsRow: LinearLayout? = null
    private val pages = mutableMapOf<String, View>()
    private val tabs = mutableMapOf<String, Button>()
    private var lastMessages: List<AgentMessage>? = null
    private var snapshot = AgentDeskSnapshot()
    private var connection = PhoneMcpBridge.Status()
    private var selectedTab = prefs.getString("tab", "Chat").orEmpty().takeIf { it in setOf("Chat", "Connection", "Plan") } ?: "Chat"
    private var saving = false
    private var renderingDraft = false
    private var imeBottom = 0
    var isEditing = false
        private set
    val isShowing get() = root != null
    private val ink = Color.rgb(226, 232, 240)
    private val muted = Color.rgb(166, 181, 201)
    private val accent = Color.rgb(56, 189, 248)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun surface(color: Int, border: Int = Color.rgb(51, 65, 85)) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
    }
    private fun label(value: String, size: Float = 13f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(dp(2), dp(4), dp(2), dp(4))
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false; minHeight = dp(44); minimumHeight = dp(44)
        minWidth = 0; minimumWidth = 0; setPadding(dp(8), dp(4), dp(8), dp(4)); setTextColor(ink)
        background = surface(Color.rgb(30, 41, 59)); contentDescription = value
        setOnClickListener { action() }
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weighted(view: View) = LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginEnd = dp(4)
    }.also { view.layoutParams = it }
    private fun field(hintText: String, secret: Boolean = false) = EditText(context).apply {
        hint = hintText; textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(true); minimumHeight = dp(48); setPadding(dp(10), dp(8), dp(10), dp(8))
        background = surface(Color.rgb(11, 18, 32))
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        filters = arrayOf(InputFilter.LengthFilter(if (secret) 128 else 2048))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) enterEditing(view)
            false
        }
    }
    fun show() {
        if (root != null) return
        val panel = column().apply {
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = surface(Color.argb(252, 15, 23, 42), Color.rgb(51, 88, 111))
        }
        val header = row()
        val title = label("OSRS companion", 16f).apply { typeface = Typeface.DEFAULT_BOLD; setTextColor(accent) }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Stop") { takeControl(); leaveEditing(); render(snapshot, connection) })
        header.addView(button("Hide") { close() })
        panel.addView(header)
        statusText = label("").apply { maxLines = 2 }; panel.addView(statusText)
        tabsRow = row().also { row ->
            for (name in listOf("Chat", "Connection", "Plan")) {
                val b = button(name) { selectTab(name) }; tabs[name] = b
                row.addView(b, weighted(b))
            }
        }
        panel.addView(tabsRow)
        val body = column()
        pages["Chat"] = buildChat()
        pages["Connection"] = buildConnection()
        pages["Plan"] = buildPlan()
        for (page in pages.values) body.addView(page, LinearLayout.LayoutParams(-1, -1))
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        controlsRow = row().also { controls ->
            val pause = button("Pause input") { takeControl(); leaveEditing(); render(snapshot, connection) }
            val allow = button("Allow input") { watch() }
            controls.addView(pause, weighted(pause)); controls.addView(allow, weighted(allow))
        }
        panel.addView(controlsRow)
        val area = usableBounds()
        val width = minOf(dp(370), if (area.width() > area.height()) (area.width() * .48f).toInt() else area.width() - dp(12)).coerceAtLeast(minOf(dp(220), area.width()))
        val height = minOf(dp(610), area.height() - dp(12)).coerceAtLeast(minOf(dp(160), area.height()))
        val p = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = area.left + OverlayPlacement.coordinate(prefs.getFloat("panel_x", 1f), area.width(), width)
            y = area.top + OverlayPlacement.coordinate(prefs.getFloat("panel_y", .05f), area.height(), height)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        root = panel; params = p
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = p.x; initialY = p.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = usableBounds(); p.x = (initialX + event.rawX - downX).toInt().coerceIn(bounds.left, maxOf(bounds.left, bounds.right - p.width))
                    p.y = (initialY + event.rawY - downY).toInt().coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - p.height))
                    runCatching { windowManager.updateViewLayout(panel, p) }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { savePlacement(); view.performClick(); true }
                else -> false
            }
        }
        panel.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            if (bottom != imeBottom) { imeBottom = bottom; panel.post { fitKeyboard() } }
            insets
        }
        try { windowManager.addView(panel, p) }
        catch (error: Exception) { root = null; params = null; throw error }
        selectTab(selectedTab)
        render(desk.state.value, PhoneMcpBridge.status.value)
    }
    private fun buildChat(): View {
        val page = column()
        deliveryText = label("").apply { setTextColor(muted); maxLines = 3 }
        page.addView(deliveryText)
        transcript = column()
        transcriptScroll = ScrollView(context).apply { addView(transcript); isFillViewport = false }
        page.addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val inputRow = row()
        composer = field("Message your OSRS agent").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false); maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(1000))
            setText(desk.state.value.draft)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!renderingDraft) desk.setDraft(s?.toString().orEmpty())
                    updateSendButton()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputRow.addView(composer, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        sendButton = button("Send") {
            val text = composer?.text?.toString().orEmpty()
            if (!connection.connected) { deliveryText?.text = "Oracle is offline. Your draft is saved; reconnect in Connection."; return@button }
            takeControl()
            if (desk.queue(text)) { desk.setDraft(""); composer?.setText(""); leaveEditing() }
            else deliveryText?.text = "Message was not queued. The session is offline, the text is empty, or eight requests are already waiting."
        }
        inputRow.addView(sendButton)
        page.addView(inputRow)
        return page
    }
    private fun buildConnection(): View {
        val page = column()
        val saved = PhoneMcpBridge.savedInfo(context)
        page.addView(label("Saved connection", 16f).apply { typeface = Typeface.DEFAULT_BOLD })
        page.addView(label("Save once. Reconnect from here without leaving OSRS. A server connection is not an AI subscription.").apply { setTextColor(muted) })
        page.addView(label("HTTPS server"))
        urlInput = field("https://server.example").apply { setText(saved.origin.ifBlank { BridgeConfigPolicy.DEFAULT_ORIGIN }) }
        page.addView(urlInput)
        savedTokenLabel = label(if (saved.hasToken) "Pairing token saved — leave blank to retain it." else "No token saved. Paste the private pairing token.")
        page.addView(savedTokenLabel)
        tokenInput = field("New token (leave blank to keep saved)", secret = true)
        page.addView(tokenInput)
        rememberChoice = CheckBox(context).apply {
            text = "Reconnect chat after the service restarts"; textSize = 13f; setTextColor(ink); isChecked = saved.reconnectChat
        }
        page.addView(rememberChoice)
        page.addView(label("Reconnection never restores game input permission, unfinished commands or plan approval.", 12f).apply { setTextColor(muted) })
        saveFeedback = label(""); page.addView(saveFeedback)
        page.addView(button("Save connection") { saveConnection(connectAfter = false) })
        connectionButton = button("Connect saved profile") {
            if (connection.enabled) WaspAccessibilityService.setMcpRemoteEnabled(false)
            else saveConnection(connectAfter = true)
        }
        page.addView(connectionButton)
        page.addView(label("Phone ID\n${saved.deviceId.ifBlank { "Created when settings are saved" }}", 11f).apply { setTextIsSelectable(true) })
        return ScrollView(context).apply { addView(page) }
    }
    private fun saveConnection(connectAfter: Boolean) {
        if (saving) return
        if (connection.enabled) { saveFeedback?.text = "Disconnect before changing this profile."; return }
        val origin = urlInput?.text?.toString().orEmpty()
        val token = tokenInput?.text?.toString().orEmpty()
        val remember = rememberChoice?.isChecked == true
        saving = true; saveFeedback?.text = "Saving securely…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { BridgeSettingsStore.save(context, origin, token, remember) }
            saving = false; saveFeedback?.text = result.message
            if (result.saved) {
                tokenInput?.setText(""); urlInput?.setText(PhoneMcpBridge.savedUrl(context))
                savedTokenLabel?.text = "Pairing token saved securely — leave blank to retain it."
                if (connectAfter) {
                    if (!WaspAccessibilityService.setMcpRemoteEnabled(true)) saveFeedback?.text = "Settings saved. Enable the WaspMobile Accessibility service to connect."
                    else { leaveEditing(); selectTab("Chat") }
                }
            }
        }
    }
    private fun buildPlan(): View {
        val page = column()
        page.addView(label("Review before running", 16f).apply { typeface = Typeface.DEFAULT_BOLD })
        page.addView(label("A proposed plan is not running. Read every step before approving it. Stop is always available.").apply { setTextColor(muted) })
        planText = label("").apply { setTextIsSelectable(true) }; page.addView(planText)
        approveButton = button("Approve displayed plan") { snapshot.proposal?.let { approve(it.id, it.sha256) } }
        rejectButton = button("Reject plan") { snapshot.proposal?.let { desk.finish(it.id, "REJECTED", "Rejected on phone") } }
        page.addView(approveButton); page.addView(rejectButton)
        return ScrollView(context).apply { addView(page) }
    }
    private fun selectTab(name: String) {
        leaveEditing(); selectedTab = name
        prefs.edit().putString("tab", name).apply()
        pages.forEach { (key, view) -> view.visibility = if (key == name) View.VISIBLE else View.GONE }
        tabs.forEach { (key, button) -> button.background = surface(if (key == name) Color.rgb(7, 89, 133) else Color.rgb(30, 41, 59)) }
    }
    private fun usableBounds(): Rect {
        val metrics = windowManager.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(metrics.bounds).apply { left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom }
    }
    private fun fitKeyboard() {
        val panel = root ?: return; val p = params ?: return
        val area = usableBounds()
        val visibleHeight = maxOf(dp(100), area.height() - imeBottom)
        p.height = minOf(dp(610), visibleHeight - dp(6)).coerceAtLeast(dp(90))
        p.y = p.y.coerceIn(area.top, maxOf(area.top, area.bottom - imeBottom - p.height))
        val compact = imeBottom > 0
        statusText?.visibility = if (compact) View.GONE else View.VISIBLE
        tabsRow?.visibility = if (compact) View.GONE else View.VISIBLE
        controlsRow?.visibility = if (compact) View.GONE else View.VISIBLE
        deliveryText?.visibility = if (compact) View.GONE else View.VISIBLE
        runCatching { windowManager.updateViewLayout(panel, p) }
    }
    private fun enterEditing(target: View?) {
        takeControl(); isEditing = true
        val r = root ?: return; val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(r, p) }
        target?.requestFocus()
        target?.post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun edit() { enterEditing(composer) }
    private fun leaveEditing() {
        root?.let { r ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0)
            r.clearFocus()
            params?.let { p -> p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; runCatching { windowManager.updateViewLayout(r, p) } }
        }
        isEditing = false
    }
    /** Deliberate user action only; sending, hiding or reconnecting never calls allowInput. */
    fun watch() { leaveEditing(); allowInput() }
    fun contains(x: Int, y: Int): Boolean {
        val r = root ?: return false; val location = IntArray(2); r.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + r.width, location[1] + r.height).contains(x, y)
    }
    private fun savePlacement() {
        val p = params ?: return; val area = usableBounds()
        prefs.edit().putFloat("panel_x", OverlayPlacement.fraction(p.x - area.left, area.width(), p.width))
            .putFloat("panel_y", OverlayPlacement.fraction(p.y - area.top, area.height(), p.height)).apply()
    }
    private fun updateSendButton() {
        sendButton?.isEnabled = connection.connected && !composer?.text.isNullOrBlank() && snapshot.outbox.size < 8
        sendButton?.text = if (snapshot.eventSubscribed) "Send" else "Queue"
    }
    fun render(state: AgentDeskSnapshot, status: PhoneMcpBridge.Status) {
        snapshot = state; connection = status
        val ai = ConnectionPresentation.ai(status.connected, state.eventSubscribed, state.eventDelivery)
        statusText?.text = "${status.message}\n${ai.title} · ${if (status.inputAllowed) "Input enabled" else "Input paused"}"
        deliveryText?.text = state.storageWarning.ifBlank { ai.detail }
        if (composer?.hasFocus() != true && composer?.text?.toString() != state.draft) {
            renderingDraft = true; composer?.setText(state.draft); renderingDraft = false
        }
        connectionButton?.text = if (status.enabled) "Disconnect (keep saved profile)" else "Connect saved profile"
        urlInput?.isEnabled = !status.enabled; tokenInput?.isEnabled = !status.enabled; rememberChoice?.isEnabled = !status.enabled
        updateSendButton()
        if (state.messages != lastMessages) {
            val scroll = transcriptScroll
            val atBottom = scroll == null || !scroll.canScrollVertically(1)
            transcript?.removeAllViews()
            if (state.messages.isEmpty()) transcript?.addView(label("Your OSRS conversation appears here. Drafts and history are saved on this phone; no old actions are restarted.").apply { setTextColor(muted) })
            state.messages.forEach { m ->
                val who = if (m.role == "user") "You" else if (m.role == "agent") "OSRS agent" else "Status"
                val delivery = when { m.interrupted -> " · not resent"; m.queued -> " · queued"; m.historical -> " · previous session"; else -> "" }
                transcript?.addView(label("$who$delivery\n${m.text}", 14f).apply {
                    setTextIsSelectable(true); setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = surface(if (m.role == "user") Color.rgb(13, 60, 80) else Color.rgb(30, 41, 59))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
                })
            }
            lastMessages = state.messages
            if (atBottom) scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val offer = state.proposal
        planText?.text = if (offer != null) {
            "${offer.plan.title}\n${offer.plan.purpose}\n\nMaximum ${offer.plan.maxRuntimeMs / 1000}s / ${offer.plan.maxTaps} taps\n\n" +
                offer.plan.steps.mapIndexed { i, step -> "${i + 1}. ${step.op}: ${step.label}" +
                    (if (step.op == "wait") " (${step.ms} ms)" else "") +
                    (step.guard?.let { "\nRegion ${it.region}; RGB ${it.rgb}; tolerance ${it.tolerance}; minimum ${it.minPixels} pixels" } ?: "") }.joinToString("\n\n") +
                "\n\nSHA-256: ${offer.sha256}\nColor matching does not establish item identity or game success."
        } else state.progress?.let { "${it.title}\n${it.status}: ${it.step}/${it.steps}\n${it.detail}" } ?: "No plan is waiting. The AI must propose a fresh plan before you can approve one."
        approveButton?.isEnabled = offer != null && status.connected && SystemClock.elapsedRealtime() < offer.expiresAt
        rejectButton?.isEnabled = offer != null
        tabs["Plan"]?.text = if (offer != null) "Plan •" else "Plan"
    }
    fun close() {
        val r = root ?: return
        if (isEditing) takeControl()
        savePlacement(); leaveEditing()
        runCatching { windowManager.removeView(r) }
        root = null; params = null; composer = null; statusText = null; deliveryText = null
        transcript = null; transcriptScroll = null; sendButton = null; connectionButton = null
        urlInput = null; tokenInput = null; savedTokenLabel = null; rememberChoice = null; saveFeedback = null
        planText = null; approveButton = null; rejectButton = null; tabsRow = null; controlsRow = null
        pages.clear(); tabs.clear(); lastMessages = null; imeBottom = 0; isEditing = false
    }
}
