package com.example.waspmobile.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Phone-initiated, session-bound HTTPS relay. No incoming phone port or persisted consent. */
internal class PhoneMcpBridge(
    private val service: WaspAccessibilityService,
    private val scope: CoroutineScope,
    private val executor: suspend (String, String, JSONObject, String, Long) -> JSONObject
) {
    private var pollJob: Job? = null
    private var settings: Settings? = null
    @Volatile private var sessionId: String? = null
    private val connection = AtomicReference<HttpURLConnection?>(null)
    val enabled: Boolean get() = sessionId != null && pollJob?.isActive == true

    fun start(): Boolean {
        if (enabled) return true
        val config = readSettings(service) ?: return false
        val session = UUID.randomUUID().toString()
        settings = config
        sessionId = session
        service.remoteGate.enable(session)
        service.agentDesk.begin(session, config.deviceId)
        mutableStatus.value = Status(true, false, "Connecting to private bridge")
        pollJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && sessionId == session) {
                    try {
                        val payload = withContext(Dispatchers.Main) { service.agentDesk.attachToPoll(service.remoteDeviceStatus()) }
                            .put("deviceId", config.deviceId).put("sessionId", session)
                            .put("remoteEnabled", true)
                        val pollStartedAt = SystemClock.elapsedRealtime()
                        val answer = post(config, "/device/poll", payload)
                        currentCoroutineContext().ensureActive()
                        if (sessionId != session) break
                        withContext(Dispatchers.Main) {
                            service.recordRelayContact()
                            answer.optJSONObject("desk")?.let { service.acceptDeskPacket(session, it) }
                        }
                        mutableStatus.value = Status(true, true, "Connected to private MCP bridge")
                        val command = answer.optJSONObject("command")
                        if (command != null) {
                            val id = command.optString("id")
                            val type = command.optString("type")
                            val ttl = command.optLong("ttlMs", -1)
                            val result = withContext(Dispatchers.Main) {
                                val deadline = if (command.optString("sessionId") == session)
                                    service.remoteGate.accept(id, session, ttl, pollStartedAt) else null
                                if (deadline == null || type !in setOf("OBSERVE", "TAP", "PAUSE")) {
                                    JSONObject().put("ok", false).put("error", "Expired, duplicate or invalid command")
                                        .put("mayHaveExecuted", false)
                                } else executor(id, type, command.optJSONObject("params") ?: JSONObject(), session, deadline)
                            }
                            currentCoroutineContext().ensureActive()
                            if (sessionId != session) break
                            result.put("deviceId", config.deviceId).put("sessionId", session).put("id", id)
                            post(config, "/device/result", result)
                        }
                        delay(500L)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AuthRejected) {
                        withContext(Dispatchers.Main) {
                            if (sessionId == session) {
                                sessionId = null
                                service.onRemoteDisconnected()
                                service.remoteGate.disable()
                                mutableStatus.value = Status(false, false, "Pairing token rejected; reconnect locally")
                            }
                        }
                        break
                    } catch (_: Exception) {
                        if (sessionId == session) {
                            service.remoteGate.invalidateFrame()
                            mutableStatus.value = Status(true, false, "Bridge unreachable; no actions are retried")
                        }
                        delay(2000L)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (sessionId == session) {
                        sessionId = null
                        service.onRemoteDisconnected()
                        service.remoteGate.disable()
                        mutableStatus.value = Status(false, false, "Disconnected")
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        val oldSession = sessionId
        val oldSettings = settings
        sessionId = null
        service.onRemoteDisconnected()
        service.remoteGate.disable()
        pollJob?.cancel()
        pollJob = null
        connection.getAndSet(null)?.disconnect()
        mutableStatus.value = Status(false, false, "Disconnected")
        // Best effort only; local consent is revoked synchronously above even without a network.
        if (oldSession != null && oldSettings != null) scope.launch(Dispatchers.IO) {
            try {
                post(oldSettings, "/device/disconnect", JSONObject()
                    .put("deviceId", oldSettings.deviceId).put("sessionId", oldSession), track = false)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* heartbeat expires server-side */ }
        }
    }

    private class AuthRejected : Exception()
    private suspend fun post(config: Settings, endpoint: String, payload: JSONObject, track: Boolean = true): JSONObject {
        currentCoroutineContext().ensureActive()
        val conn = URL(config.baseUrl + endpoint).openConnection() as HttpURLConnection
        val started = SystemClock.elapsedRealtime()
        if (track) connection.set(conn)
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = if (track) 3000 else 1000
            conn.readTimeout = if (track) 3000 else 1000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + config.token)
            conn.setRequestProperty("Content-Type", "application/json")
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            require(body.size <= 1_000_000)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            currentCoroutineContext().ensureActive()
            if (conn.responseCode == 401) throw AuthRejected()
            check(conn.responseCode == 200) { "Relay rejected request" }
            val output = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.elapsedRealtime() - started <= 8000) { "Relay response timed out" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 32768) { "Relay response too large" }
                    output.write(buffer, 0, count)
                }
            }
            return JSONObject(output.toString("UTF-8"))
        } finally {
            if (track) connection.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    internal data class Settings(val baseUrl: String, val token: String, val deviceId: String)
    internal data class Status(val enabled: Boolean = false, val connected: Boolean = false, val message: String = "Disconnected")

    companion object {
        private const val PREFS = "waspmobile_mcp_bridge"
        private const val URL_KEY = "url"
        private const val TOKEN_KEY = "token"
        private const val DEVICE_ID_KEY = "device_id"
        private val mutableStatus = MutableStateFlow(Status())
        val status = mutableStatus.asStateFlow()
        private val tokenPattern = Regex("[A-Za-z0-9_-]{32,128}")
        fun savedUrl(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(URL_KEY, "") ?: ""
        fun savedDeviceId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEVICE_ID_KEY, "") ?: ""
        fun save(context: Context, url: String, newToken: String): Boolean {
            val normalized = url.trim().trimEnd('/')
            if (!validHttpsUrl(normalized)) return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Never send a saved credential to a newly entered endpoint without fresh token entry.
            if (newToken.isBlank() && prefs.getString(URL_KEY, "") != normalized) return false
            val token = if (newToken.isBlank()) prefs.getString(TOKEN_KEY, "") ?: "" else newToken.trim()
            if (!tokenPattern.matches(token)) return false
            val id = prefs.getString(DEVICE_ID_KEY, "")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                ?: "phone-" + UUID.randomUUID().toString()
            prefs.edit().putString(URL_KEY, normalized).putString(TOKEN_KEY, token).putString(DEVICE_ID_KEY, id).apply()
            return true
        }
        private fun readSettings(context: Context): Settings? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = p.getString(URL_KEY, "") ?: ""
            val token = p.getString(TOKEN_KEY, "") ?: ""
            val id = p.getString(DEVICE_ID_KEY, "") ?: ""
            if (!validHttpsUrl(url) || !tokenPattern.matches(token) || !id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
            return Settings(url, token, id)
        }
        internal fun validHttpsUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)
        } catch (_: Exception) { false }
    }
}
