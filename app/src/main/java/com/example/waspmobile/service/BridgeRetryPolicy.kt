package com.example.waspmobile.service

/** Deterministic recovery policy, independently testable without Android or a network. */
internal object BridgeRetryPolicy {
    /** Redirects and most client errors are permanent; never follow a redirect with a bearer token. */
    fun isPermanentHttp(status: Int): Boolean = status in 300..499 &&
        status !in setOf(408, 409, 423, 425, 429)

    /** Bounded backoff with optional jitter; both inputs are clamped to avoid overflow. */
    fun delayMs(failures: Int, jitterMs: Long = 0L): Long {
        val exponent = (failures - 1L).coerceIn(0L, 4L).toInt()
        val base = (2000L shl exponent).coerceAtMost(30_000L)
        return (base + jitterMs.coerceIn(0L, 500L)).coerceAtMost(30_000L)
    }
}
