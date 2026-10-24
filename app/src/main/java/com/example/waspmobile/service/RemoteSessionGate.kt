package com.example.waspmobile.service

/** Pure JVM policy: no wall-clock comparisons, no Android objects, no input dispatch. */
internal class RemoteSessionGate(private val now: () -> Long) {
    private data class Frame(val id: String, val windowKey: String, val capturedAt: Long)
    private var session: String? = null
    private var frame: Frame? = null
    private val received = LinkedHashSet<String>()

    @Synchronized fun enable(id: String) {
        require(validId(id))
        session = id
        frame = null
        received.clear()
    }
    @Synchronized fun disable() { session = null; frame = null; received.clear() }
    @Synchronized fun isSession(id: String): Boolean = session == id
    @Synchronized fun invalidateFrame() { frame = null }

    /** Mark before executing. A cancelled/failed command must not execute a second time. */
    @Synchronized fun accept(id: String, sessionId: String, ttlMs: Long, pollStartedAt: Long): Long? {
        val current = now()
        if (session != sessionId || !validId(id) || ttlMs !in 1L..10_000L ||
            pollStartedAt > current || current - pollStartedAt >= ttlMs ||
            id in received || received.size >= 4096) return null
        received.add(id)
        return pollStartedAt + ttlMs
    }
    @Synchronized fun recordFrame(id: String, key: String, capturedAt: Long, sessionId: String): Boolean {
        if (session != sessionId || !validId(id) || key.isBlank() || capturedAt > now()) return false
        frame = Frame(id, key, capturedAt)
        return true
    }
    @Synchronized fun consumeFrame(id: String, key: String, sessionId: String): Boolean {
        val candidate = frame
        frame = null
        return session == sessionId && candidate != null && candidate.id == id &&
            candidate.windowKey == key && now() - candidate.capturedAt in 0L..15_000L
    }
    private fun validId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,64}"))
}
