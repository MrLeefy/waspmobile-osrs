package com.example.waspmobile.service

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AgentDeskStateTest {
    private var clock = 1000L
    private val desk = AgentDeskState { clock }
    private val raw = """{"version":1,"title":"Observe","purpose":"Test only","maxRuntimeMs":5000,"steps":[{"op":"observe","label":"Fresh frame"}]}"""
    private val digest = MobilePlan.sha256(raw)
    private fun packet(epoch: String = "epoch1") = JSONObject().put("sessionId", "session1").put("epoch", epoch)
    private fun offer(id: String = "plan1", hash: String = digest) = JSONObject().put("id", id).put("sha256", hash)
        .put("planJson", raw).put("ttlMs", 1000)
    @Test fun disconnectedMailboxCannotQueueRequests() { assertFalse(desk.queue("hello")) }
    @Test fun outboxIsBoundedAndDoesNotAutomaticallyReplayAfterReconnect() {
        desk.begin("session1", "phone1")
        repeat(8) { assertTrue(desk.queue("hello $it")) }
        assertFalse(desk.queue("overflow"))
        desk.disconnect(); desk.begin("new", "phone1")
        assertTrue(desk.state.value.outbox.isEmpty())
    }
    @Test fun networkAcknowledgementClearsOnlyAcknowledgedMessages() {
        desk.begin("session1", "phone1"); desk.queue("one"); desk.queue("two")
        val id = desk.state.value.outbox[0].id
        desk.receive("session1", packet().put("ackMessages", JSONArray().put(id)))
        assertEquals(1, desk.state.value.outbox.size)
        assertFalse(desk.state.value.messages[0].queued)
    }
    @Test fun eventsWithoutSubscriptionStayInInboxOnly() {
        desk.begin("session1", "phone1")
        val info = JSONObject().put("available", true).put("subscribed", false)
            .put("status", "Subscription missing")
        desk.receive("session1", packet().put("events", info))
        assertTrue(desk.state.value.eventsAvailable)
        assertFalse(desk.state.value.eventSubscribed)
        assertEquals("Subscription missing", desk.state.value.eventStatus)
    }
    @Test fun subscribedEventStateIsShownOnlyWhenServerReportsIt() {
        desk.begin("session1", "phone1")
        val info = JSONObject().put("available", true).put("subscribed", true)
            .put("status", "Work/dot subscription active")
        desk.receive("session1", packet().put("events", info))
        assertTrue(desk.state.value.eventSubscribed)
        val revoked = JSONObject().put("available", true).put("subscribed", false)
        desk.receive("session1", packet().put("events", revoked))
        assertFalse(desk.state.value.eventSubscribed)
    }
    @Test fun staleSessionCannotFabricateEventSubscription() {
        desk.begin("session1", "phone1")
        desk.receive("old", packet().put("events",
            JSONObject().put("available", true).put("subscribed", true)))
        assertFalse(desk.state.value.eventSubscribed)
    }

    @Test fun staleSessionCannotInjectRepliesOrProposals() {
        desk.begin("session1", "phone1")
        desk.receive("old", packet().put("proposal", offer()))
        assertNull(desk.state.value.proposal)
    }
    @Test fun invalidDigestCannotBeApproved() {
        desk.begin("session1", "phone1")
        desk.receive("session1", packet().put("proposal", offer(hash = "0".repeat(64))))
        assertNull(desk.state.value.proposal)
        assertNull(desk.approve("plan1", digest))
    }
    @Test fun approvalBindsExactPlanIdentityAndDigest() {
        desk.begin("session1", "phone1"); desk.receive("session1", packet().put("proposal", offer()))
        assertNull(desk.approve("different", digest))
        assertNull(desk.approve("plan1", "0".repeat(64)))
        assertNotNull(desk.approve("plan1", digest))
        assertNull(desk.approve("plan1", digest))
        assertEquals("RUNNING", desk.state.value.progress?.status)
    }
    @Test fun repeatedProposalDoesNotExtendApprovalDeadline() {
        desk.begin("session1", "phone1"); desk.receive("session1", packet().put("proposal", offer()))
        clock += 999
        desk.receive("session1", packet().put("proposal", offer()))
        clock += 2
        assertNull(desk.approve("plan1", digest))
    }
    @Test fun relayRestartRevokesOldApproval() {
        desk.begin("session1", "phone1"); desk.receive("session1", packet().put("proposal", offer()))
        assertNotNull(desk.approve("plan1", digest))
        assertTrue(desk.receive("session1", packet("new-epoch")))
        assertEquals("CANCELLED", desk.state.value.progress?.status)
    }
    @Test fun staleCompletionCannotOverrideManualCancellation() {
        desk.begin("session1", "phone1"); desk.receive("session1", packet().put("proposal", offer()))
        desk.approve("plan1", digest); desk.cancel("manual")
        desk.finish("plan1", "COMPLETED", "late")
        assertEquals("CANCELLED", desk.state.value.progress?.status)
    }
    @Test fun transcriptIsBoundedAndDuplicateEchoesDoNotDuplicateMessages() {
        desk.begin("session1", "phone1")
        for (i in 1..50) {
            val message = JSONObject().put("seq", i).put("id", "message-$i").put("role", "agent").put("text", "test")
            desk.receive("session1", packet().put("messages", JSONArray().put(message)))
            desk.receive("session1", packet().put("messages", JSONArray().put(message)))
        }
        assertEquals(40, desk.state.value.messages.size)
        assertEquals(50L, desk.state.value.afterSeq)
    }
}
