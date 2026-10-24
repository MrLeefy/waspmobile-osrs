package com.example.waspmobile.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Uses an emulator Android Keystore and real SharedPreferences, not mocked persistence. */
class BridgePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("waspmobile_mcp_bridge", Context.MODE_PRIVATE)
    private val token = "synthetic-test-token-" + "x".repeat(32)
    @Before fun reset() { prefs.edit().clear().commit() }
    @After fun clean() { prefs.edit().clear().commit() }
    @Test fun migratePlainTokenAndPreserveDeviceIdAcrossStorageRecreation() {
        prefs.edit().putString("url", "https://example.com").putString("token", token)
            .putString("device_id", "original-s23-id").commit()
        assertTrue(BridgeSettingsStore.save(context, "https://example.com", "").saved)
        assertFalse(prefs.contains("token"))
        assertTrue(prefs.contains("token_sealed"))
        assertFalse(prefs.getString("token_sealed", "")!!.contains(token))
        val reread = BridgeSettingsStore.read(context)!!
        assertEquals(token, reread.token)
        assertEquals("original-s23-id", reread.deviceId)
        assertTrue(BridgeSettingsStore.info(context).hasToken)
    }
    @Test fun saveWithoutConnectAndBlankTokenKeepsCredentials() {
        assertTrue(BridgeSettingsStore.save(context, "https://example.com", token, true).saved)
        val original = BridgeSettingsStore.read(context)!!
        assertTrue(BridgeSettingsStore.save(context, "https://EXAMPLE.com:443/", "", true).saved)
        assertEquals(original, BridgeSettingsStore.read(context))
        assertFalse(BridgeSettingsStore.shouldReconnect(context))
        BridgeSettingsStore.setDesired(context, true)
        assertTrue(BridgeSettingsStore.shouldReconnect(context))
        BridgeSettingsStore.setDesired(context, false)
        assertFalse(BridgeSettingsStore.shouldReconnect(context))
    }
    @Test fun wrongHostOrMalformedTokenDoesNotOverwriteGoodSettings() {
        assertTrue(BridgeSettingsStore.save(context, "https://example.com", token).saved)
        val original = BridgeSettingsStore.read(context)
        assertFalse(BridgeSettingsStore.save(context, "https://other.example", "").saved)
        assertFalse(BridgeSettingsStore.save(context, "https://example.com/pair", token).saved)
        assertFalse(BridgeSettingsStore.save(context, "https://example.com", "bad").saved)
        assertEquals(original, BridgeSettingsStore.read(context))
    }
    @Test fun encryptedHistoryRoundTripUsesDomainSeparatedKey() {
        val body = "{\"version\":1,\"draft\":\"test\",\"messages\":[]}"
        val encoded = PrivateStorageCipher.encrypt(body, "history-test")
        assertFalse(encoded.contains("messages"))
        assertEquals(body, PrivateStorageCipher.decrypt(encoded, "history-test"))
        assertTrue(runCatching { PrivateStorageCipher.decrypt(encoded, "wrong-domain") }.isFailure)
    }
}
