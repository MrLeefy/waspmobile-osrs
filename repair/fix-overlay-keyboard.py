from pathlib import Path

panel = Path('app/src/main/java/com/example/waspmobile/service/AgentPanel.kt')
text = panel.read_text()
old = 'private fun field(hintText: String, secret: Boolean = false) = EditText(context).apply {'
new = 'private fun field(hintText: String, secret: Boolean = false) = OverlayEditor(context).apply {'
if old in text:
    assert text.count(old) == 1
    text = text.replace(old, new)
else:
    assert new in text
panel.write_text(text)

test = Path('app/src/androidTest/java/com/example/waspmobile/service/OverlayWindowTest.kt')
text = test.read_text()
old = '''        instrument.runOnMainSync { panel!!.edit() }
        SystemClock.sleep(700)
        assertTrue(panel!!.isEditing)
'''
new = '''        val automationInfo = automation.serviceInfo
        automationInfo.flags = automationInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = automationInfo
        shell("settings put secure show_ime_with_hard_keyboard 1")
        instrument.runOnMainSync { panel!!.edit() }
        val imeDeadline = SystemClock.elapsedRealtime() + 5000L
        fun keyboardVisible() = automation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        while (!keyboardVisible() && SystemClock.elapsedRealtime() < imeDeadline) SystemClock.sleep(100)
        capture("overlay-keyboard-assertion")
        assertTrue("The software keyboard must actually be visible, not just a focused field", keyboardVisible())
        assertTrue(panel!!.isEditing)
'''
if old in text:
    assert text.count(old) == 1
    text = text.replace(old, new)
else:
    assert new in text
# Make the keyboard regression use the orientation of actual OSRS gameplay.
anchor = '        activity = ActivityScenario.launch(MainActivity::class.java)'
addition = '''        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 1")
'''
if addition not in text:
    assert text.count(anchor) == 1
    text = text.replace(anchor, addition + anchor)
test.write_text(text)
print('Applied two exact, reviewed source substitutions; no server configuration or credentials involved.')
