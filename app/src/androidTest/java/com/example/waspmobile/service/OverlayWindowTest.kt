package com.example.waspmobile.service

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waspmobile.MainActivity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Emulator only: no account, real OSRS process, network credential, or gameplay action is involved. */
class OverlayWindowTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val automation get() = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var oldServices = ""
    private var oldEnabled = "0"
    private var panel: AgentPanel? = null
    private var activity: ActivityScenario<MainActivity>? = null
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    @Before fun enableTestService() {
        oldServices = shell("settings get secure enabled_accessibility_services")
        oldEnabled = shell("settings get secure accessibility_enabled")
        // This test runs only on the disposable CI emulator and restores its settings afterward.
        context.getSharedPreferences("waspmobile_mcp_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        val component = "com.example.waspmobile/com.example.waspmobile.service.WaspAccessibilityService"
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        val deadline = SystemClock.elapsedRealtime() + 10000L
        while (WaspAccessibilityService.instance == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertNotNull("The emulator must actually bind the Accessibility service", WaspAccessibilityService.instance)
        activity = ActivityScenario.launch(MainActivity::class.java)
        instrument.waitForIdleSync()
    }
    @After fun restoreTestDevice() {
        instrument.runOnMainSync { panel?.close() }
        activity?.close()
        val services = oldServices.takeUnless { it == "null" }.orEmpty()
        if (services.isEmpty()) shell("settings delete secure enabled_accessibility_services")
        else if (services.matches(Regex("[A-Za-z0-9_./:]+"))) shell("settings put secure enabled_accessibility_services $services")
        shell("settings put secure accessibility_enabled ${if (oldEnabled == "1") "1" else "0"}")
    }
    private fun capture(name: String) {
        require(name.matches(Regex("[a-z-]+")))
        instrument.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap = automation.takeScreenshot()
        assertNotNull("Actual emulator screenshot must be available", bitmap)
        bitmap?.recycle()
        // Gradle may uninstall the test app and delete app-owned files afterward.
        // Shell-owned screenshots on this disposable emulator survive that cleanup.
        shell("mkdir -p /sdcard/Download/osrs-recovery-screens")
        shell("screencap -p /sdcard/Download/osrs-recovery-screens/$name.png")
        val size = shell("stat -c %s /sdcard/Download/osrs-recovery-screens/$name.png").toLongOrNull() ?: 0L
        assertTrue("The captured image must contain an actual screen", size > 1000L)
    }
    private fun makePanel(desk: AgentDeskState, onTakeControl: () -> Unit = {}): AgentPanel {
        val service = requireNotNull(WaspAccessibilityService.instance)
        return AgentPanel(service, service.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
            desk, onTakeControl, { error("Test must not permit game input") }, { _, _ -> error("No plan approval") }, {})
    }
    @Test fun actualOverlaySurvivesKeyboardHideAndReopenWithoutLosingDraft() {
        val desk = AgentDeskState { SystemClock.elapsedRealtime() }
        desk.setDraft("Emulator-only saved draft; no gameplay requested")
        var controlTaken = 0
        context.getSharedPreferences("osrs_overlay_ui", Context.MODE_PRIVATE).edit().putString("tab", "Chat").commit()
        instrument.runOnMainSync { panel = makePanel(desk) { controlTaken++ }; panel!!.show() }
        assertTrue(panel!!.isShowing)
        capture("overlay-chat")
        instrument.runOnMainSync { panel!!.edit() }
        SystemClock.sleep(700)
        assertTrue(panel!!.isEditing)
        assertTrue(controlTaken > 0)
        capture("overlay-keyboard")
        instrument.runOnMainSync { panel!!.close() }
        assertFalse(panel!!.isShowing)
        assertFalse(panel!!.isEditing)
        assertNotNull("Hiding chat must not disable Accessibility", WaspAccessibilityService.instance)
        assertEquals("Emulator-only saved draft; no gameplay requested", desk.state.value.draft)
        instrument.runOnMainSync { panel!!.show() }
        assertTrue(panel!!.isShowing)
        assertEquals("Emulator-only saved draft; no gameplay requested", desk.state.value.draft)
    }
    @Test fun savedConnectionTabRendersInsideRealAccessibilityWindow() {
        val desk = AgentDeskState { SystemClock.elapsedRealtime() }
        context.getSharedPreferences("osrs_overlay_ui", Context.MODE_PRIVATE).edit().putString("tab", "Connection").commit()
        instrument.runOnMainSync { panel = makePanel(desk); panel!!.show() }
        assertTrue(panel!!.isShowing)
        capture("overlay-connection")
        instrument.runOnMainSync { panel!!.close(); panel = null }
        capture("main-home")
    }
}
