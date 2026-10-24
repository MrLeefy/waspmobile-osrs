package com.example.waspmobile.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class AgentMessage(val id: String, val role: String, val text: String, val queued: Boolean = false,
    val historical: Boolean = false, val interrupted: Boolean = false)
internal data class PlanOffer(val id: String, val sha256: String, val planJson: String, val plan: MobilePlan, val expiresAt: Long)
internal data class PlanProgress(val id: String, val sha256: String, val title: String, val status: String,
    val step: Int, val steps: Int, val detail: String)
internal data class AgentDeskSnapshot(val session: String = "", val deviceId: String = "", val afterSeq: Long = 0,
    val messages: List<AgentMessage> = emptyList(), val outbox: List<AgentMessage> = emptyList(),
    val proposal: PlanOffer? = null, val progress: PlanProgress? = null, val agentActive: Boolean = false,
    val eventsAvailable: Boolean = false, val eventSubscribed: Boolean = false,
    val eventStatus: String = "No ChatGPT event subscription",
    val eventDelivery: String = "not_subscribed",
    val draft: String = "", val storageWarning: String = "",
    val notice: String = "Save your connection, then connect to Oracle.")

/** Bounded transcript; only history/draft may persist. Live approvals and send queues never restore. */
internal class AgentDeskState(private val now: () -> Long) {
    private val mutable = MutableStateFlow(AgentDeskSnapshot())
    val state = mutable.asStateFlow()
    private var epoch = ""
    @Synchronized fun begin(session: String, deviceId: String) {
        epoch = ""
        val old = mutable.value
        mutable.value = AgentDeskSnapshot(session = session, deviceId = deviceId,
            messages = old.messages.map { it.copy(queued = false, historical = true, interrupted = it.interrupted || it.queued) },
            draft = old.draft, storageWarning = old.storageWarning,
            notice = "Oracle connected does not mean AI is subscribed. Check the separate AI status before expecting a reply.")
    }
    @Synchronized fun disconnect() {
        cancel("Remote session disconnected", includeOffer = true)
        mutable.value = mutable.value.copy(session = "", outbox = emptyList(), agentActive = false,
            eventsAvailable = false, eventSubscribed = false, eventDelivery = "not_subscribed",
            messages = mutable.value.messages.map { it.copy(queued = false, interrupted = it.interrupted || it.queued, historical = true) },
            notice = "Disconnected. Unsent requests are not replayed into a new session.")
    }
    @Synchronized fun queue(text: String): Boolean {
        val s = mutable.value
        if (s.session.isBlank() || text.isBlank() || text.length > 1000 || s.outbox.size >= 8) return false
        val message = AgentMessage(UUID.randomUUID().toString(), "user", text.trim(), queued = true)
        mutable.value = s.copy(messages = (s.messages + message).takeLast(40), outbox = s.outbox + message)
        return true
    }
    @Synchronized fun attachToPoll(payload: JSONObject): JSONObject {
        val s = mutable.value
        payload.put("afterSeq", s.afterSeq)
        payload.put("outbox", JSONArray().apply {
            s.outbox.take(2).forEach { put(JSONObject().put("id", it.id).put("text", it.text)) }
        })
        s.progress?.let { p -> payload.put("planStatus", JSONObject().put("id", p.id).put("sha256", p.sha256)
            .put("status", p.status).put("step", p.step).put("detail", p.detail)) }
        return payload
    }
    /** Returns true when an existing running plan must stop, e.g. after a relay restart. */
    @Synchronized fun receive(session: String, data: JSONObject): Boolean {
        var s = mutable.value
        if (s.session != session || data.optString("sessionId") != session) return false
        var stop = false
        val incomingEpoch = data.optString("epoch")
        if (epoch.isNotBlank() && incomingEpoch != epoch) {
            stop = true
            s = s.copy(afterSeq = 0, proposal = null, progress = s.progress?.copy(status = "CANCELLED", detail = "Bridge restarted"),
                messages = s.messages.map { it.copy(historical = true, queued = false, interrupted = it.interrupted || it.queued) },
                outbox = emptyList(), notice = "Oracle restarted. Prior messages are history; old actions will not run.")
        }
        epoch = incomingEpoch
        val acks = data.optJSONArray("ackMessages")
        val ackSet = if (acks == null) emptySet() else (0 until minOf(acks.length(), 2)).map { acks.optString(it) }.toSet()
        val list = s.messages.map { if (it.id in ackSet) it.copy(queued = false) else it }.toMutableList()
        var seq = s.afterSeq
        val messages = data.optJSONArray("messages")
        if (messages != null) for (i in 0 until minOf(messages.length(), 2)) {
            val message = messages.optJSONObject(i) ?: continue
            val id = message.optString("id"); val role = message.optString("role"); val body = message.optString("text")
            val incomingSeq = message.optLong("seq", -1)
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || role !in setOf("user", "agent", "system") ||
                body.isBlank() || body.length > 1000 || incomingSeq <= seq) continue
            val index = list.indexOfFirst { it.id == id }
            val item = AgentMessage(id, role, body)
            if (index >= 0) list[index] = item else list.add(item)
            seq = incomingSeq
        }
        s = s.copy(messages = list.takeLast(40), afterSeq = seq, outbox = s.outbox.filterNot { it.id in ackSet },
            agentActive = data.optBoolean("agentActive", false))
        val remoteStatus = data.optJSONObject("planStatus")
        if (remoteStatus != null && remoteStatus.optString("id") == s.progress?.id &&
            remoteStatus.optString("sha256") == s.progress?.sha256 &&
            remoteStatus.optString("status") in setOf("CANCELLED", "EXPIRED", "REJECTED") &&
            s.progress?.status in setOf("OFFERED", "RUNNING")) {
            stop = true
            s = s.copy(proposal = null, progress = s.progress?.copy(status = remoteStatus.optString("status"),
                detail = remoteStatus.optString("detail", "Cancelled by connected agent").take(160)))
        }
        val offer = data.optJSONObject("proposal")
        if (offer != null && offer.optString("id") != s.progress?.id) {
            try {
                val id = offer.getString("id"); val digest = offer.getString("sha256"); val raw = offer.getString("planJson")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                require(digest.matches(Regex("[a-f0-9]{64}")) && MobilePlan.sha256(raw) == digest) { "Plan digest mismatch" }
                val ttl = offer.getLong("ttlMs"); require(ttl in 1..120000)
                val plan = MobilePlan.parse(raw)
                require(s.progress?.status != "RUNNING") { "A plan is already running" }
                s = s.copy(proposal = PlanOffer(id, digest, raw, plan, now() + ttl),
                    progress = PlanProgress(id, digest, plan.title, "OFFERED", 0, plan.steps.size, "Review the exact steps and approve on this phone"))
            } catch (_: Exception) {
                s = s.copy(notice = "Rejected invalid or oversized script proposal; no input was executed.")
            }
        }
        val eventInfo = data.optJSONObject("events")
        if (eventInfo != null) {
            val available = eventInfo.optBoolean("available", false)
            val subscribed = available && eventInfo.optBoolean("subscribed", false)
            s = s.copy(eventsAvailable = available, eventSubscribed = subscribed,
                eventStatus = eventInfo.optString("status", "No ChatGPT event subscription").take(200),
                eventDelivery = eventInfo.optString("lastDelivery", "no_recent_message").take(80))
        }
        mutable.value = s
        return stop
    }
    @Synchronized fun setDraft(text: String) {
        mutable.value = mutable.value.copy(draft = text.take(1000))
    }
    @Synchronized fun persistenceWarning(message: String) {
        if (mutable.value.storageWarning != message) mutable.value = mutable.value.copy(storageWarning = message.take(180))
    }
    @Synchronized fun exportHistory(): String = JSONObject().put("version", 1)
        .put("draft", mutable.value.draft).put("messages", JSONArray().apply {
            mutable.value.messages.takeLast(40).forEach { m -> put(JSONObject()
                .put("id", m.id).put("role", m.role).put("text", m.text)
                .put("interrupted", m.interrupted || m.queued)) }
        }).toString()
    @Synchronized fun restoreHistory(raw: String) {
        require(raw.toByteArray(Charsets.UTF_8).size <= 262_144)
        val json = JSONObject(raw)
        require(json.optInt("version") == 1)
        val source = json.optJSONArray("messages") ?: JSONArray()
        val messages = (maxOf(0, source.length() - 40) until source.length()).mapNotNull { i ->
            val m = source.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id"); val role = m.optString("role"); val text = m.optString("text")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || role !in setOf("user", "agent", "system") ||
                text.isBlank() || text.length > 1000) null
            else AgentMessage(id, role, text, historical = true, interrupted = m.optBoolean("interrupted"))
        }
        mutable.value = AgentDeskSnapshot(messages = messages.distinctBy { it.id },
            draft = json.optString("draft").take(1000), notice = "Local chat restored. No old messages or actions will be resent.")
    }
    @Synchronized fun transportLost() {
        mutable.value = mutable.value.copy(agentActive = false, eventSubscribed = false,
            eventDelivery = "offline", eventStatus = "Waiting for a fresh Oracle connection")
    }
    @Synchronized fun approve(id: String, sha256: String): PlanOffer? {
        val s = mutable.value; val proposal = s.proposal ?: return null
        if (now() >= proposal.expiresAt) {
            mutable.value = s.copy(proposal = null, progress = s.progress?.copy(status = "EXPIRED", detail = "Request a fresh proposal"))
            return null
        }
        if (s.session.isBlank() || proposal.id != id || proposal.sha256 != sha256 || s.progress?.status != "OFFERED") return null
        mutable.value = s.copy(proposal = null, progress = s.progress.copy(status = "RUNNING", detail = "Approved locally; preparing fresh observation"))
        return proposal
    }
    @Synchronized fun progress(id: String, step: Int, detail: String) {
        val s = mutable.value; val p = s.progress ?: return
        if (p.id != id || p.status != "RUNNING") return
        mutable.value = s.copy(progress = p.copy(step = step.coerceIn(0, p.steps), detail = detail.take(160)))
    }
    @Synchronized fun finish(id: String, status: String, detail: String) {
        require(status in setOf("COMPLETED", "FAILED", "CANCELLED", "REJECTED"))
        val s = mutable.value; val p = s.progress ?: return
        if (p.id != id || p.status !in setOf("RUNNING", "OFFERED")) return
        mutable.value = s.copy(proposal = null, progress = p.copy(status = status,
            step = if (status == "COMPLETED") p.steps else p.step, detail = detail.take(160)))
    }
    @Synchronized fun cancel(reason: String, includeOffer: Boolean = false) {
        val p = mutable.value.progress ?: return
        if (p.status == "RUNNING" || (includeOffer && p.status == "OFFERED")) finish(p.id, "CANCELLED", reason)
    }
}
