package com.example.waspmobile.service

import android.content.Context
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Accessibility windows become focusable asynchronously. Requesting the IME
 * immediately after updateViewLayout can fail at PHASE_CLIENT_VIEW_SERVED.
 * Wait for actual window/editor focus; never force a keyboard over another app.
 */
internal class OverlayEditor(context: Context) : EditText(context) {
    private var requestGeneration = 0

    init {
        // Landscape chat must remain over the game, not enter the IME's full-screen editor.
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        requestGeneration++
        if (!hasWindowFocus || !hasFocus()) return
        val generation = requestGeneration
        val deadline = SystemClock.elapsedRealtime() + 1500L
        fun showWhenConnected() {
            if (generation != requestGeneration || !isAttachedToWindow || !hasWindowFocus() || !hasFocus()) return
            val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            windowInsetsController?.show(WindowInsets.Type.ime())
            if (input.isActive(this) && input.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) return
            if (SystemClock.elapsedRealtime() < deadline) postDelayed({ showWhenConnected() }, 50L)
        }
        // ViewRoot establishes the input connection after dispatching window focus.
        post { showWhenConnected() }
    }

    override fun onDetachedFromWindow() {
        requestGeneration++
        super.onDetachedFromWindow()
    }
}
