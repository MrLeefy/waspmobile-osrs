package com.example.waspmobile.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore key never leaves the phone. No insecure fallback on decrypt failure. */
internal object PrivateStorageCipher {
    private const val ALIAS = "waspmobile-osrs-private-v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun encrypt(text: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(data, Base64.NO_WRAP)
    }
    fun decrypt(encoded: String, purpose: String): String {
        val parts = encoded.split(':')
        require(parts.size == 3 && parts[0] == "v1" && encoded.length <= 400_000)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

internal data class BridgeSaveResult(val saved: Boolean, val message: String)
internal data class SavedBridgeInfo(val origin: String, val deviceId: String, val hasToken: Boolean,
    val encrypted: Boolean, val reconnectChat: Boolean)

/** Preserve the original preference file/device ID so an update does not unpair the S23. */
internal object BridgeSettingsStore {
    private const val FILE = "waspmobile_mcp_bridge"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun info(context: Context): SavedBridgeInfo {
        val p = prefs(context)
        return SavedBridgeInfo(p.getString("url", "").orEmpty(), p.getString("device_id", "").orEmpty(),
            p.contains("token_sealed") || !p.getString("token", "").isNullOrBlank(), p.contains("token_sealed"),
            p.getBoolean("reconnect_chat", false))
    }
    fun read(context: Context): PhoneMcpBridge.Settings? = runCatching {
        val p = prefs(context)
        val origin = BridgeConfigPolicy.normalizeOrigin(p.getString("url", "").orEmpty()) ?: return null
        val token = if (p.contains("token_sealed")) {
            PrivateStorageCipher.decrypt(p.getString("token_sealed", "").orEmpty(), "bridge:$origin")
        } else p.getString("token", "").orEmpty() // legacy migration occurs on next successful Save
        val id = p.getString("device_id", "").orEmpty()
        if (!BridgeConfigPolicy.validToken(token) || !BridgeConfigPolicy.validDevice(id)) return null
        PhoneMcpBridge.Settings(origin, token, id)
    }.getOrNull()
    /** Call off the main thread. commit() acknowledges durable disk persistence, not just memory. */
    @Synchronized fun save(context: Context, rawOrigin: String, enteredToken: String,
                           reconnectChat: Boolean = info(context).reconnectChat): BridgeSaveResult {
        val origin = BridgeConfigPolicy.normalizeOrigin(rawOrigin)
            ?: return BridgeSaveResult(false, "Enter an HTTPS server origin only, without /pair, a path, or query text.")
        val p = prefs(context)
        val typed = enteredToken.trim()
        if (typed.isEmpty() && !BridgeConfigPolicy.mayReuseToken(p.getString("url", "").orEmpty(), origin))
            return BridgeSaveResult(false, "Paste the pairing token for this server. Saved credentials are not sent to another host.")
        val token = typed.ifEmpty { read(context)?.token.orEmpty() }
        if (!BridgeConfigPolicy.validToken(token)) return BridgeSaveResult(false,
            "No usable saved token. Paste the complete 32–128 character pairing token; other settings will be kept.")
        return runCatching {
            val sealed = PrivateStorageCipher.encrypt(token, "bridge:$origin")
            val id = p.getString("device_id", "").orEmpty().takeIf(BridgeConfigPolicy::validDevice)
                ?: "phone-" + UUID.randomUUID()
            val saved = p.edit().putString("url", origin).putString("token_sealed", sealed)
                .remove("token").putString("device_id", id).putBoolean("reconnect_chat", reconnectChat).commit()
            BridgeSaveResult(saved, if (saved) "Saved on this phone. Pairing token retained securely." else "Could not write settings. Your entry has not been cleared.")
        }.getOrElse { BridgeSaveResult(false, "Secure save failed. Existing settings are unchanged; do not uninstall the app.") }
    }
    fun setDesired(context: Context, desired: Boolean) { prefs(context).edit().putBoolean("chat_requested", desired).apply() }
    fun shouldReconnect(context: Context) = prefs(context).getBoolean("reconnect_chat", false) &&
        prefs(context).getBoolean("chat_requested", false) && read(context) != null
}
