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
        val config = BridgeSettingsStore.read(service) ?: run {
            mutableStatus.value = Status(message = "No usable saved connection. Open Connection and save the URL and token.", phase = BridgePhase.CONFIG_ERROR)
            return false
        }
        val session = UUID.randomUUID().toString()
        settings = config
        BridgeSettingsStore.setDesired(service, true)
        sessionId = session
        service.remoteGate.enable(session)
        service.agentDesk.begin(session, config.deviceId)
        mutableStatus.value = Status(true, false, "Connecting to Oracle…", phase = BridgePhase.CONNECTING)
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
                        val eventInfo = answer.optJSONObject("desk")?.optJSONObject("events")
                        mutableStatus.value = Status(true, true, "Oracle connected", phase = BridgePhase.CONNECTED,
                            lastContactAt = SystemClock.elapsedRealtime(),
                            eventsAvailable = eventInfo?.optBoolean("available") == true,
                            eventSubscribed = eventInfo?.optBoolean("available") == true && eventInfo?.optBoolean("subscribed") == true,
                            eventDelivery = eventInfo?.optString("lastDelivery").orEmpty(),
                            windowReady = payload.optString("foreground") == "com.jagex.oldscape.android",
                            inputAllowed = payload.optBoolean("inputAllowed"))
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
                                mutableStatus.value = Status(false, false, "Oracle rejected the saved token. Re-pair in Connection; your other settings are intact.", phase = BridgePhase.AUTH_REJECTED)
                            }
                        }
                        break
                    } catch (failure: Exception) {
                        if (sessionId == session) {
                            withContext(Dispatchers.Main) {
                                service.pauseForConnectionLoss()
                                service.agentDesk.transportLost()
                            }
                            val message = when (failure) {
                                is RelayRejected -> when (failure.status) {
                                    400, 409 -> "Oracle rejected this phone/session (HTTP ${failure.status}). Retrying safely; check the pinned Phone ID in Diagnostics."
                                    403 -> "Oracle denied this connection (HTTP 403). Check the relay configuration."
                                    404 -> "Server address is reachable, but the phone relay route was not found (HTTP 404)."
                                    else -> "Oracle returned HTTP ${failure.status}. Retrying; no action is replayed."
                                }
                                is javax.net.ssl.SSLException -> "HTTPS certificate check failed. Do not bypass it; check the server."
                                is java.net.UnknownHostException -> "Server name could not be resolved. Check the URL and connection."
                                is java.net.SocketTimeoutException -> "Oracle timed out. Retrying; game input is paused."
                                else -> "Oracle connection lost. Retrying; your saved profile and draft are retained."
                            }
                            mutableStatus.value = Status(true, false, message, phase = BridgePhase.RETRYING)
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

    fun stop(preservePreference: Boolean = false) {
        if (!preservePreference) BridgeSettingsStore.setDesired(service, false)
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
    private class RelayRejected(val status: Int) : Exception()
    private suspend fun post(config: Settings, endpoint: String, payload: JSONObject, track: Boolean = true): JSONObject {
        currentCoroutineContext().ensureActive()
        val conn = URL(config.baseUrl + endpoint).openConnection() as HttpURLConnection
        val started = SystemClock.elapsedRealtime()
        if (track) connection.set(conn)
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = if (track) 6000 else 1000
            conn.readTimeout = if (track) 8000 else 1000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + config.token)
            conn.setRequestProperty("Content-Type", "application/json")
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            require(body.size <= 1_000_000)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            currentCoroutineContext().ensureActive()
            if (conn.responseCode == 401) throw AuthRejected()
            if (conn.responseCode != 200) throw RelayRejected(conn.responseCode)
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
    internal data class Status(val enabled: Boolean = false, val connected: Boolean = false,
        val message: String = "Not connected", val phase: BridgePhase = BridgePhase.IDLE,
        val lastContactAt: Long = 0L, val eventsAvailable: Boolean = false,
        val eventSubscribed: Boolean = false, val eventDelivery: String = "not_subscribed",
        val windowReady: Boolean = false, val inputAllowed: Boolean = false)

    companion object {
        private val mutableStatus = MutableStateFlow(Status())
        val status = mutableStatus.asStateFlow()
        fun savedUrl(context: Context): String = BridgeSettingsStore.info(context).origin
        fun savedDeviceId(context: Context): String = BridgeSettingsStore.info(context).deviceId
        fun savedInfo(context: Context): SavedBridgeInfo = BridgeSettingsStore.info(context)
        fun save(context: Context, url: String, newToken: String): Boolean =
            BridgeSettingsStore.save(context, url, newToken).saved
        internal fun validHttpsUrl(value: String): Boolean = BridgeConfigPolicy.normalizeOrigin(value) != null
    }
}
