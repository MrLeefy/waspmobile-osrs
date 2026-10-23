package com.example.waspmobile.service

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Phone-initiated MCP relay client. No inbound phone port or APK patching.
 *
 * Remote control is opt-in for THIS service session only. A disabled service
 * immediately cancels polling. The user must configure an HTTPS endpoint
 * (e.g. Tailscale Serve forwarding to 127.0.0.1:7788 on the PC).
 */
internal class PhoneMcpBridge(
    private val service: WaspAccessibilityService,
    private val scope: CoroutineScope,
    private val executor: suspend (String, JSONObject) -> JSONObject
) {
    private var pollJob: Job? = null
    val enabled: Boolean get() = pollJob?.isActive == true

    fun start(): Boolean {
        if (enabled) return true
        val settings = readSettings(service) ?: return false
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val telemetry = com.example.waspmobile.engine.WaspMobileState.state.value
                    val req = JSONObject()
                        .put("deviceId", settings.deviceId)
                        .put("remoteEnabled", true)
                        .put("foreground", service.currentForegroundPackage)
                        .put("width", service.remoteScreenWidth())
                        .put("height", service.remoteScreenHeight())
                        .put("status", telemetry.statusText.take(150))
                    val answer = post(settings, "/device/poll", req)
                    val command = answer.optJSONObject("command")
                    if (command != null) {
                        val id = command.optString("id")
                        val type = command.optString("type")
                        val expiresAt = command.optLong("expiresAt")
                        val result = if (id.isEmpty() || expiresAt <= System.currentTimeMillis()) {
                            JSONObject().put("ok", false).put("error", "Command expired or invalid")
                        } else {
                            try {
                                withContext(Dispatchers.Main) {
                                    executor(type, command.optJSONObject("params") ?: JSONObject())
                                }
                            } catch (e: Exception) {
                                JSONObject().put("ok", false)
                                    .put("error", e.javaClass.simpleName.take(90))
                            }
                        }
                        result.put("deviceId", settings.deviceId).put("id", id)
                        post(settings, "/device/result", result)
                    }
                    delay(900L)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Network errors never trigger local game actions or retries of a command.
                    delay(2500L)
                }
            }
        }
        return true
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun post(settings: Settings, endpoint: String, payload: JSONObject): JSONObject {
        val url = URL(settings.baseUrl + endpoint)
        val conn = (url.openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 4500
            conn.readTimeout = 4500
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + settings.token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IllegalStateException("Bridge response " + conn.responseCode)
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    internal data class Settings(val baseUrl: String, val token: String, val deviceId: String)

    companion object {
        private const val PREFS = "waspmobile_mcp_bridge"
        private const val URL_KEY = "url"
        private const val TOKEN_KEY = "token"
        private const val DEVICE_ID_KEY = "device_id"

        fun savedUrl(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(URL_KEY, "") ?: ""

        /**
         * Do not expose stored tokens to the UI or telemetry.
         * A blank entry in the UI means "keep existing token".
         */
        fun save(context: Context, url: String, newToken: String): Boolean {
            val normalized = url.trim().trimEnd('/')
            if (!validHttpsUrl(normalized)) return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(TOKEN_KEY, "") ?: ""
            val token = if (newToken.isBlank()) existing else newToken.trim()
            if (token.length < 32 || token.any { it.isWhitespace() }) return false
            val id = prefs.getString(DEVICE_ID_KEY, "")?.takeIf { it.isNotEmpty() }
                ?: ("s23-" + UUID.randomUUID().toString().replace("-", "").take(20))
            prefs.edit().putString(URL_KEY, normalized).putString(TOKEN_KEY, token)
                .putString(DEVICE_ID_KEY, id).apply()
            return true
        }

        private fun readSettings(context: Context): Settings? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = p.getString(URL_KEY, "") ?: ""
            val token = p.getString(TOKEN_KEY, "") ?: ""
            val id = p.getString(DEVICE_ID_KEY, "") ?: ""
            if (!validHttpsUrl(url) || token.length < 32 || id.isBlank()) return null
            return Settings(url, token, id)
        }

        internal fun validHttpsUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") && uri.port in -1..65535
        } catch (_: Exception) { false }
    }
}
