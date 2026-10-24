package com.example.waspmobile.service

internal enum class BridgePhase { IDLE, CONNECTING, CONNECTED, RETRYING, AUTH_REJECTED, CONFIG_ERROR }
internal data class ConnectionPresentation(val title: String, val detail: String, val ready: Boolean) {
    companion object {
        fun ai(connected: Boolean, subscribed: Boolean, delivery: String): ConnectionPresentation = when {
            !connected -> ConnectionPresentation("AI: waiting for Oracle", "Connect the saved phone profile first.", false)
            !subscribed -> ConnectionPresentation("AI: not subscribed", "Oracle is online, but no ChatGPT event subscriber is attached. Queued messages will not start a reply.", false)
            delivery in setOf("delivery_failed", "rejected_by_callback") -> ConnectionPresentation("AI: delivery failed", "Your message is in the inbox. The callback was not accepted; it has not been answered.", false)
            delivery == "sending" -> ConnectionPresentation("AI: delivering", "Sending the event to the subscribed agent…", true)
            delivery == "accepted_by_chatgpt_webhook" -> ConnectionPresentation("AI: event received", "The callback accepted the event. This is not yet an AI reply.", true)
            else -> ConnectionPresentation("AI: subscribed", "New overlay messages notify the subscribed OSRS agent.", true)
        }
    }
}

/** Fractional overlay placement survives rotation and never leaves controls off-screen. */
internal object OverlayPlacement {
    fun coordinate(fraction: Float, available: Int, size: Int): Int =
        ((if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0.5f) * maxOf(0, available - size)).toInt()
    fun fraction(position: Int, available: Int, size: Int): Float =
        if (available <= size) 0f else (position.toFloat() / (available - size)).coerceIn(0f, 1f)
}
