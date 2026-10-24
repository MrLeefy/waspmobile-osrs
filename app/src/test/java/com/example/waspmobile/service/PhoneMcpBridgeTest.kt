package com.example.waspmobile.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMcpBridgeTest {
    @Test
    fun acceptsPrivateHttpsHostname() {
        assertTrue(PhoneMcpBridge.validHttpsUrl("https://my-pc.private-tailnet.ts.net"))
        assertTrue(PhoneMcpBridge.validHttpsUrl("https://my-pc.private-tailnet.ts.net:443"))
    }

    @Test
    fun rejectsCleartextAndMisleadingHttps() {
        assertFalse(PhoneMcpBridge.validHttpsUrl("http://192.168.1.116:7788"))
        assertFalse(PhoneMcpBridge.validHttpsUrl("https://safe.example@evil.example/path"))
        assertFalse(PhoneMcpBridge.validHttpsUrl("https://safe.example/redirect"))
        assertFalse(PhoneMcpBridge.validHttpsUrl("https://safe.example?token=secret"))
        assertFalse(PhoneMcpBridge.validHttpsUrl("not-a-url"))
    }
}
