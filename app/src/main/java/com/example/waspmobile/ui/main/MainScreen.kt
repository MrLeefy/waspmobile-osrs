package com.example.waspmobile.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.waspmobile.data.DefaultDataRepository
import com.example.waspmobile.engine.TargetColorPreset
import com.example.waspmobile.engine.WaspMobileState
import com.example.waspmobile.engine.WaspScriptMode
import com.example.waspmobile.service.StoreInstallResultReceiver
import com.example.waspmobile.service.PhoneMcpBridge
import com.example.waspmobile.service.WaspAccessibilityService
import com.example.waspmobile.theme.WaspMobileTheme

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val telemetry by WaspMobileState.state.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var mcpUrl by remember { mutableStateOf(PhoneMcpBridge.savedUrl(context)) }
    var mcpTokenInput by remember { mutableStateOf("") }
    var mcpEnabled by remember { mutableStateOf(WaspAccessibilityService.isMcpRemoteEnabled()) }

    LaunchedEffect(Unit) {
        WaspMobileState.loadPrefs(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF080C14))
            .verticalScroll(scroll)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            if (telemetry.serviceConnected) Color(0xFF10B981) else Color(0xFFF59E0B),
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🐝 WASPMOBILE OSRS OVERLAY v5",
                    color = Color(0xFF38BDF8),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (telemetry.serviceConnected) {
                    "STATUS: OVERLAY CONNECTED (${telemetry.statusText})"
                } else {
                    "STATUS: OVERLAY OFF — Tap Step 1 below to enable in Accessibility Settings"
                },
                color = if (telemetry.serviceConnected) Color(0xFF10B981) else Color(0xFFFBBF24),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (!telemetry.serviceConnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            StoreInstallResultReceiver.triggerStoreSessionUnlock(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔓 1-Tap Unlock 'Unavailable'", color = Color.White, fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            Toast.makeText(
                                context,
                                "Tap ⋮ (top-right 3 dots) -> Allow restricted settings!",
                                Toast.LENGTH_LONG
                            ).show()
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚙️ App Info (⋮ Allow)", color = Color.White, fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        Toast.makeText(
                            context,
                            "Scroll to bottom -> tap 'Installed apps' -> WaspMobile OSRS Overlay",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1. Enable Overlay", color = Color.White, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val pm = context.packageManager
                        val osrsIntent = pm.getLaunchIntentForPackage("com.jagex.oldscape.android")
                        if (osrsIntent != null) {
                            osrsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(osrsIntent)
                        } else {
                            Toast.makeText(
                                context,
                                "OSRS Mobile (com.jagex.oldscape.android) not found on this device yet.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("2. Open OSRS App", color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    WaspAccessibilityService.closeOverlayAndDisableService()
                    val activity = context.findActivity()
                    activity?.finishAndRemoveTask()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✕ Close Overlay & Exit App",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // Opt-in AI control over an authenticated HTTPS phone-to-PC bridge.
        // Disabling the Accessibility service also cancels the remote poller.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "MCP PHONE BRIDGE (EXPERIMENTAL)",
                color = Color(0xFF38BDF8),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "Manual opt-in each service session. Screenshots may be sent to your private PC bridge. " +
                    "Use an HTTPS Tailscale Serve URL and a private 32+ character token. " +
                    "Pause local scripts before remote taps.",
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp
            )
            OutlinedTextField(
                value = mcpUrl,
                onValueChange = { mcpUrl = it },
                label = { Text("HTTPS bridge URL", color = Color(0xFF94A3B8)) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = mcpTokenInput,
                onValueChange = { mcpTokenInput = it },
                label = { Text("Bridge token (blank keeps saved token)", color = Color(0xFF94A3B8)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (mcpEnabled) {
                        WaspAccessibilityService.setMcpRemoteEnabled(false)
                        mcpEnabled = false
                    } else {
                        val valid = PhoneMcpBridge.save(context, mcpUrl, mcpTokenInput)
                        val started = valid && WaspAccessibilityService.setMcpRemoteEnabled(true)
                        mcpEnabled = started
                        mcpTokenInput = ""
                        Toast.makeText(
                            context,
                            if (started) "MCP phone bridge enabled for this service session"
                            else "Could not start: enable Accessibility, enter HTTPS URL + token",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mcpEnabled) Color(0xFFDC2626) else Color(0xFF0369A1)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (mcpEnabled) "Disconnect MCP Remote Control"
                    else "Connect MCP Remote Control",
                    color = Color.White
                )
            }
        }

        // Live Biometric & Markov Telemetry Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131D33), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TelemetryStat("MARKOV STATE", telemetry.attention.badge, Color(0xFF38BDF8))
            TelemetryStat("INV LIMIT", "${telemetry.filledSlots} / ${telemetry.currentInventoryLimit}", Color(0xFF10B981))
            TelemetryStat("ACTIONS", "${telemetry.actionsCount}", Color(0xFFA855F7))
        }

        // Script Selector Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SELECT WASPMOBILE SCRIPT",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            WaspScriptMode.entries.forEach { mode ->
                val selected = telemetry.scriptMode == mode
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0xFF162036) else Color(0xFF0B111E),
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            val newPreset = when (mode) {
                                WaspScriptMode.WOODCUTTER_DROP -> TargetColorPreset.TREE_CANOPY
                                WaspScriptMode.POWER_MINER -> TargetColorPreset.IRON_ROCK
                                else -> telemetry.colorPreset
                            }
                            WaspMobileState.update {
                                it.copy(
                                    scriptMode = mode,
                                    colorPreset = newPreset,
                                    targetColorRgb = if (newPreset != it.colorPreset) newPreset.rgb else it.targetColorRgb,
                                    colorTolerance = if (newPreset != it.colorPreset) newPreset.tolerance else it.colorTolerance,
                                    statusText = "Selected script: ${mode.title}"
                                )
                            }
                            WaspMobileState.savePrefs(context)
                            WaspMobileState.log("Selected script: ${mode.title}")
                        }
                        .padding(10.dp)
                ) {
                    Text(
                        text = mode.title,
                        color = if (selected) Color(0xFF38BDF8) else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = mode.description,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Target Color Preset & Tolerance Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color(telemetry.targetColorRgb or -0x1000000), CircleShape)
                        .border(1.dp, Color.White, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(
                        "3D TARGET COLOR (#%06X) | TOLERANCE: %d",
                        0xFFFFFF and telemetry.targetColorRgb,
                        telemetry.colorTolerance
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = telemetry.colorTolerance.toFloat(),
                onValueChange = { newTol ->
                    WaspMobileState.update { it.copy(colorTolerance = newTol.toInt()) }
                },
                onValueChangeFinished = {
                    WaspMobileState.savePrefs(context)
                },
                valueRange = 10f..48f
            )

            TargetColorPreset.entries.forEach { preset ->
                val isSelected = telemetry.colorPreset == preset
                val swatchColor = if (preset == TargetColorPreset.CUSTOM_LOCKED) {
                    telemetry.targetColorRgb or -0x1000000
                } else {
                    preset.rgb or -0x1000000
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) Color(0xFF162036) else Color(0xFF0B111E),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            WaspMobileState.update {
                                it.copy(
                                    colorPreset = preset,
                                    targetColorRgb = if (preset == TargetColorPreset.CUSTOM_LOCKED) it.targetColorRgb else preset.rgb,
                                    colorTolerance = preset.tolerance
                                )
                            }
                            WaspMobileState.savePrefs(context)
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(swatchColor), CircleShape)
                            .border(1.dp, Color.White, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = preset.label,
                        color = if (isSelected) Color(0xFF38BDF8) else Color(0xFFE2E8F0),
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Protected Inventory Slots Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(
                text = "INVENTORY PROTECTION (TAP-TO-DROP)",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = telemetry.protectSlot1,
                    onCheckedChange = { checked ->
                        WaspMobileState.update { it.copy(protectSlot1 = checked) }
                        WaspMobileState.savePrefs(context)
                    }
                )
                Text("Protect Slot 1 (Axe / Pickaxe / Knife / Net)", color = Color.White, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = telemetry.protectSlot2,
                    onCheckedChange = { checked ->
                        WaspMobileState.update { it.copy(protectSlot2 = checked) }
                        WaspMobileState.savePrefs(context)
                    }
                )
                Text("Protect Slot 2 (Tinderbox / Feathers / Chisel)", color = Color.White, fontSize = 13.sp)
            }
        }

        // Screen & Inventory Calibration Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SCREEN & INVENTORY CALIBRATION",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Default coordinates are pre-calibrated for Samsung Galaxy S23 OSRS Mobile landscape (1024x472). Tap below to restore defaults at any time.",
                color = Color(0xFF64748B),
                fontSize = 11.sp
            )
            Button(
                onClick = {
                    WaspMobileState.resetBoxesToDefaults()
                    WaspMobileState.savePrefs(context)
                    Toast.makeText(
                        context,
                        "Reset Search Box & Inventory Grid to S23 OSRS Mobile defaults!",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔄 Reset Boxes to S23 Defaults",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // Live Log Feed
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B111E), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "LIVE ENGINE LOGS",
                color = Color(0xFF38BDF8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            telemetry.logs.takeLast(8).forEach { line ->
                Text(
                    text = line,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun TelemetryStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    WaspMobileTheme { MainScreen() }
}
