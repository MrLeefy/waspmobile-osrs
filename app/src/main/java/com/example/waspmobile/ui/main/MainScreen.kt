package com.example.waspmobile.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.waspmobile.data.DefaultDataRepository
import com.example.waspmobile.engine.TargetColorPreset
import com.example.waspmobile.engine.WaspMobileState
import com.example.waspmobile.engine.SingleCharacterPolicy
import com.example.waspmobile.service.BridgeConfigPolicy
import com.example.waspmobile.service.BridgeSettingsStore
import com.example.waspmobile.service.ConnectionPresentation
import com.example.waspmobile.service.PhoneMcpBridge
import com.example.waspmobile.service.WaspAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val Night = Color(0xFF0B1220)
private val CardColor = Color(0xFF111D2F)
private val TextColor = Color(0xFFE2E8F0)
private val Muted = Color(0xFFABBDD2)
private val Cyan = Color(0xFF38BDF8)
private val Amber = Color(0xFFFBBF24)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telemetry by WaspMobileState.state.collectAsState()
    val connection by PhoneMcpBridge.status.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var savedVersion by remember { mutableIntStateOf(0) }
    val saved = remember(savedVersion) { PhoneMcpBridge.savedInfo(context) }
    var origin by rememberSaveable { mutableStateOf(saved.origin.ifBlank { BridgeConfigPolicy.DEFAULT_ORIGIN }) }
    // Unsaved secrets deliberately do not go into the Activity SavedState bundle.
    var token by remember { mutableStateOf("") }
    var reconnectChat by rememberSaveable { mutableStateOf(saved.reconnectChat) }
    var saving by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var showDisableConfirm by remember { mutableStateOf(false) }
    val ai = ConnectionPresentation.ai(connection.connected, connection.eventSubscribed, connection.eventDelivery)
    LaunchedEffect(Unit) { WaspMobileState.loadPrefs(context) }

    fun save(connectAfter: Boolean) {
        if (saving || connection.enabled) return
        val proposedUrl = origin; val proposedToken = token; val remember = reconnectChat
        saving = true; feedback = "Saving securely on this phone…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { BridgeSettingsStore.save(context, proposedUrl, proposedToken, remember) }
            saving = false; feedback = result.message
            if (result.saved) {
                token = ""; origin = PhoneMcpBridge.savedUrl(context); savedVersion++
                if (connectAfter && !WaspAccessibilityService.setMcpRemoteEnabled(true)) {
                    feedback = "Connection saved. Enable the WaspMobile Accessibility service, then tap Connect."
                }
            }
        }
    }
    fun openSettings(action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Open this setting in Android Settings.", Toast.LENGTH_LONG).show() }
    }
    fun openGame() {
        val intent = context.packageManager.getLaunchIntentForPackage("com.jagex.oldscape.android")
        if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(context, "Install the official OSRS Mobile app first.", Toast.LENGTH_LONG).show()
    }
    Column(modifier.fillMaxSize().background(Night).imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("OSRS companion", color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("One phone. Your saved connection. Control stays with you.", color = Muted, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Home", "Connection", "Settings", "Diagnostics").forEach { name ->
                FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(labelColor = TextColor, selectedLabelColor = Color.White,
                        selectedContainerColor = Color(0xFF075985), containerColor = CardColor))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (tab) {
                "Home" -> {
                    Section("Connection health") {
                        HealthLine("Overlay", if (telemetry.serviceConnected) "Ready" else "Service is off", telemetry.serviceConnected)
                        HealthLine("Oracle", connection.message, connection.connected)
                        HealthLine("ChatGPT", ai.title.removePrefix("AI: "), ai.ready)
                        HealthLine("Game input", if (connection.inputAllowed) "Enabled for fresh requests" else "Paused / not authorized", false)
                        Text(ai.detail, color = Muted, fontSize = 13.sp)
                    }
                    if (!telemetry.serviceConnected) Section("Enable the overlay") {
                        Text("Android Accessibility supplies this overlay. The separate ‘Appear on top’ permission is not required for it.", color = TextColor)
                        Action("Open Accessibility settings") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                        Text("Choose Installed apps → WaspMobile OSRS Overlay. If Android blocks a sideloaded app, open App info → ⋮ → Allow restricted settings, then return here.", color = Muted, fontSize = 13.sp)
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Open WaspMobile app info", color = Cyan)
                        }
                    }
                    Section("Open your OSRS chat") {
                        Text(if (saved.hasToken) "Your pairing profile is saved. No need to paste the token again." else "Save the server and private pairing token once in Connection.", color = TextColor)
                        if (saved.hasToken && !connection.enabled) Action("Connect saved profile", telemetry.serviceConnected) {
                            if (!WaspAccessibilityService.setMcpRemoteEnabled(true)) feedback = "Could not connect. Check Accessibility and saved settings."
                        }
                        Action("Open floating chat", telemetry.serviceConnected) { WaspAccessibilityService.showAgentDesk() }
                        Action("Open OSRS") { openGame() }
                        OutlinedButton(onClick = { tab = "Connection" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Connection settings", color = Cyan) }
                    }
                    Section("Pause, don’t lose the connection") {
                        Text("Stop pauses game input but keeps chat available. Hiding the panel does not disable Accessibility. Only the separate Disable service action turns the overlay off.", color = Muted)
                        Action("Pause all game input", telemetry.serviceConnected) { WaspAccessibilityService.pauseInput() }
                    }
                }
                "Connection" -> {
                    Section("Saved phone profile") {
                        Text(if (saved.hasToken) "Token saved${if (saved.encrypted) " securely" else " — will be upgraded to encrypted storage when saved"}. Blank keeps it." else "No pairing token saved yet.", color = if (saved.hasToken) Cyan else Amber)
                        if (connection.enabled) Text("Disconnect before editing. The saved profile will not be deleted.", color = Amber, fontSize = 13.sp)
                        OutlinedTextField(value = origin, onValueChange = { origin = it }, enabled = !connection.enabled && !saving,
                            label = { Text("HTTPS server origin") }, textStyle = TextStyle(color = TextColor), singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = token, onValueChange = { token = it.take(128) }, enabled = !connection.enabled && !saving,
                            label = { Text(if (saved.hasToken) "Replace token (blank keeps saved)" else "Private pairing token") },
                            visualTransformation = PasswordVisualTransformation(), textStyle = TextStyle(color = TextColor), singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = reconnectChat, onCheckedChange = { reconnectChat = it }, enabled = !connection.enabled && !saving)
                            Spacer(Modifier.width(10.dp)); Text("Reconnect chat when the service restarts", color = TextColor, modifier = Modifier.weight(1f))
                        }
                        Text("This option never restores input permission, unfinished actions, old messages or script approvals.", color = Muted, fontSize = 12.sp)
                        if (feedback.isNotBlank()) Text(feedback, color = Cyan, fontSize = 13.sp)
                        Action(if (saving) "Saving…" else "Save connection", !saving && !connection.enabled) { save(false) }
                        Action("Save and connect", !saving && !connection.enabled) { save(true) }
                        if (connection.enabled) OutlinedButton(onClick = { WaspAccessibilityService.setMcpRemoteEnabled(false) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Disconnect — keep saved settings", color = Amber) }
                    }
                    Section("Two connections, shown separately") {
                        Text("Oracle connected means the phone can send and receive messages. AI subscribed means a ChatGPT event subscription exists. Neither one alone means an AI reply or game action has happened.", color = Muted)
                        HealthLine("Oracle", connection.message, connection.connected)
                        HealthLine("AI", ai.title, ai.ready)
                        Text(ai.detail, color = TextColor)
                    }
                }
                "Settings" -> {
                    Section("Local script") {
                        Text("Local scripts and remote AI input cannot own the phone at the same time. Selecting a script does not start it.", color = Muted, fontSize = 13.sp)
                        SingleCharacterPolicy.selectableModes.forEach { mode ->
                            OutlinedButton(onClick = {
                                WaspMobileState.update { it.copy(scriptMode = mode) }; WaspMobileState.savePrefs(context)
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text((if (telemetry.scriptMode == mode) "Selected: " else "") + mode.title,
                                    color = if (telemetry.scriptMode == mode) Cyan else TextColor)
                            }
                        }
                    }
                    Section("Calibration and item protection") {
                        Text("Calibration boxes, selected script, target color and slot protection are retained on this phone.", color = Muted)
                        Text("Color tolerance: ${telemetry.colorTolerance}", color = TextColor)
                        Slider(value = telemetry.colorTolerance.toFloat().coerceIn(0f, 48f),
                            onValueChange = { value -> WaspMobileState.update { it.copy(colorTolerance = value.toInt()) } },
                            onValueChangeFinished = { WaspMobileState.savePrefs(context) }, valueRange = 0f..48f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = telemetry.protectSlot1, onCheckedChange = { value -> WaspMobileState.update { it.copy(protectSlot1 = value) }; WaspMobileState.savePrefs(context) })
                            Text("Protect inventory slot 1", color = TextColor)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = telemetry.protectSlot2, onCheckedChange = { value -> WaspMobileState.update { it.copy(protectSlot2 = value) }; WaspMobileState.savePrefs(context) })
                            Text("Protect inventory slot 2", color = TextColor)
                        }
                        Text("Use the floating toolbar’s Color and Grid controls while OSRS is open. Do not uninstall to reset a connection; uninstalling removes app data.", color = Amber, fontSize = 13.sp)
                    }
                    Section("Service controls") {
                        Text("Disable only when you are finished. Android requires you to re-enable Accessibility afterward; your saved profile stays on this phone.", color = Muted)
                        OutlinedButton(onClick = { showDisableConfirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Disable overlay service…", color = Amber) }
                    }
                }
                "Diagnostics" -> Section("Connection diagnostics — no secrets") {
                    val diagnostic = "OSRS overlay recovery\nService: ${telemetry.serviceConnected}\nProfile saved: ${saved.hasToken}\nPhone ID: ${saved.deviceId}\nServer: ${saved.origin}\nPhase: ${connection.phase}\nOracle connected: ${connection.connected}\nEvent subscriber: ${connection.eventSubscribed}\nEvent delivery: ${connection.eventDelivery}\nOSRS window verified: ${connection.windowReady}\nInput allowed: ${connection.inputAllowed}\nStatus: ${connection.message}"
                    Text(diagnostic, color = TextColor, fontSize = 13.sp)
                    Action("Copy diagnostics (no token)") {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OSRS diagnostics", diagnostic))
                        Toast.makeText(context, "Diagnostics copied; pairing token excluded.", Toast.LENGTH_SHORT).show()
                    }
                    Text("If Oracle works but AI is not subscribed, reinstalling or re-pairing will not fix the missing ChatGPT subscription. If tools show a protocol-version error, the gateway needs repair, not your phone token.", color = Amber, fontSize = 13.sp)
                }
            }
            Text("OSRS only • Single-phone recovery preview", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showDisableConfirm) AlertDialog(onDismissRequest = { showDisableConfirm = false }, title = { Text("Disable the overlay service?") },
        text = { Text("Chat and game controls will disconnect. Your saved profile and calibration stay intact, but Android will require Accessibility to be enabled again.") },
        confirmButton = { TextButton(onClick = { showDisableConfirm = false; WaspAccessibilityService.closeOverlayAndDisableService() }) { Text("Disable service") } },
        dismissButton = { TextButton(onClick = { showDisableConfirm = false }) { Text("Keep enabled") } })
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(16.dp))
        .border(1.dp, Color(0xFF293D55), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
@Composable private fun HealthLine(label: String, value: String, healthy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = if (healthy) Cyan else TextColor, fontSize = 15.sp)
    }
}
@Composable private fun Action(label: String, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075985), contentColor = Color.White)) { Text(label) }
}
