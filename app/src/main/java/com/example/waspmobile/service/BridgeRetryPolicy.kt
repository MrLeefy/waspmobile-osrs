package com.example.waspmobile.service

/** Deterministic recovery policy, independently testable without Android or a network. */
internal object BridgeRetryPolicy {
    /** 400/403/404/422 are configuration/protocol failures; blindly polling wastes battery. */
    fun isPermanentHttp(status: Int): Boolean = status in setOf(400, 403, 404, 422)

    /** Bounded backoff with optional jitter; both inputs are clamped to avoid overflow. */
    fun delayMs(failures: Int, jitterMs: Long = 0L): Long {
        val exponent = (failures - 1L).coerceIn(0L, 4L).toInt()
        val base = (2000L shl exponent).coerceAtMost(30_000L)
        return (base + jitterMs.coerceIn(0L, 500L)).coerceAtMost(30_000L)
    }
}
