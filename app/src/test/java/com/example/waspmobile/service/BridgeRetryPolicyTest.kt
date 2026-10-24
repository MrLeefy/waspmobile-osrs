package com.example.waspmobile.service

import org.junit.Assert.*
import org.junit.Test

/** Regression gates for networks that cannot reach Oracle or are paired incorrectly. */
class BridgeRetryPolicyTest {
    @Test fun repeatedTimeoutsBackOffWithoutOverflowOrBatteryDrain() {
        assertEquals(2000L, BridgeRetryPolicy.delayMs(0))
        assertEquals(2000L, BridgeRetryPolicy.delayMs(1))
        assertEquals(4000L, BridgeRetryPolicy.delayMs(2))
        assertEquals(8000L, BridgeRetryPolicy.delayMs(3))
        assertEquals(16000L, BridgeRetryPolicy.delayMs(4))
        assertEquals(30000L, BridgeRetryPolicy.delayMs(5))
        assertEquals(30000L, BridgeRetryPolicy.delayMs(Int.MAX_VALUE))
        assertEquals(2000L, BridgeRetryPolicy.delayMs(Int.MIN_VALUE))
    }
    @Test fun jitterIsBoundedAndCannotBypassBackoff() {
        assertEquals(2499L, BridgeRetryPolicy.delayMs(1, 499))
        assertEquals(2000L, BridgeRetryPolicy.delayMs(1, -1000))
        assertEquals(2500L, BridgeRetryPolicy.delayMs(1, Long.MAX_VALUE))
        assertEquals(30000L, BridgeRetryPolicy.delayMs(100, Long.MAX_VALUE))
    }
    @Test fun permanentPhoneMisconfigurationMustNotLoopForever() {
        for (code in listOf(300, 301, 302, 307, 308, 400, 401, 402, 403, 404, 405, 410, 415, 422, 426))
            assertTrue("HTTP $code", BridgeRetryPolicy.isPermanentHttp(code))
        for (code in listOf(200, 201, 408, 409, 423, 425, 429, 500, 502, 503, 504))
            assertFalse("HTTP $code", BridgeRetryPolicy.isPermanentHttp(code))
    }
}
