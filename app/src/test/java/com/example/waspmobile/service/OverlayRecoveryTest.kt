package com.example.waspmobile.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OverlayRecoveryTest {
    @Test fun originNormalizationPreservesTrustBoundary() {
        assertEquals("https://example.com", BridgeConfigPolicy.normalizeOrigin(" HTTPS://Example.COM:443/ "))
        assertEquals("https://example.com:8443", BridgeConfigPolicy.normalizeOrigin("https://example.com:8443/"))
        for (bad in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/pair/abc", "https://example.com?token=secret", "https://example.com/#x", "", "https://example.com:0")) {
            assertNull(bad, BridgeConfigPolicy.normalizeOrigin(bad))
        }
    }
    @Test fun oldCredentialCannotBeSentToNewHost() {
        assertTrue(BridgeConfigPolicy.mayReuseToken("https://EXAMPLE.com:443/", "https://example.com"))
        assertFalse(BridgeConfigPolicy.mayReuseToken("https://example.com", "https://other.example.com"))
        assertFalse(BridgeConfigPolicy.mayReuseToken("", "https://example.com"))
        assertFalse(BridgeConfigPolicy.validToken("short"))
        assertTrue(BridgeConfigPolicy.validToken("a".repeat(32)))
    }
    @Test fun historyAndDraftSurviveReconnectWithoutReplayingMessages() {
        val desk = AgentDeskState { 1000L }
        desk.begin("first", "phone")
        desk.queue("Previously requested action")
        desk.setDraft("Text not sent yet")
        desk.disconnect()
        desk.begin("second", "phone")
        assertEquals("Text not sent yet", desk.state.value.draft)
        assertEquals(1, desk.state.value.messages.size)
        assertTrue(desk.state.value.messages.single().historical)
        assertTrue(desk.state.value.messages.single().interrupted)
        assertFalse(desk.state.value.messages.single().queued)
        assertTrue(desk.state.value.outbox.isEmpty())
        assertNull(desk.state.value.proposal)
    }
    @Test fun restoredHistoryDoesNotRestoreControlOrApproval() {
        val before = AgentDeskState { 1000L }
        before.begin("secret-session", "phone")
        before.queue("Please inspect")
        before.setDraft("My draft")
        val json = before.exportHistory()
        assertFalse(json.contains("secret-session"))
        val restored = AgentDeskState { 1000L }
        restored.restoreHistory(json)
        assertEquals("My draft", restored.state.value.draft)
        assertEquals(1, restored.state.value.messages.size)
        assertEquals("", restored.state.value.session)
        assertFalse(restored.state.value.eventSubscribed)
        assertTrue(restored.state.value.outbox.isEmpty())
        assertNull(restored.state.value.proposal)
        assertFalse(restored.queue("Must not queue while disconnected"))
    }
    @Test fun malformedHistoryFailsClosed() {
        val desk = AgentDeskState { 1000L }
        assertTrue(runCatching { desk.restoreHistory("not json") }.isFailure)
        assertTrue(runCatching { desk.restoreHistory("x".repeat(262145)) }.isFailure)
        assertTrue(desk.state.value.session.isEmpty())
        assertTrue(desk.state.value.outbox.isEmpty())
    }
    @Test fun draftSizeIsBounded() {
        val desk = AgentDeskState { 1000L }
        desk.setDraft("x".repeat(2000))
        assertEquals(1000, desk.state.value.draft.length)
    }
    @Test fun transportLossNeverReportsSubscribedOrActive() {
        val desk = AgentDeskState { 1000L }
        desk.begin("session", "phone")
        desk.receive("session", JSONObject().put("sessionId", "session").put("epoch", "one")
            .put("agentActive", true).put("events", JSONObject().put("available", true).put("subscribed", true)))
        assertTrue(desk.state.value.eventSubscribed)
        desk.transportLost()
        assertFalse(desk.state.value.eventSubscribed)
        assertFalse(desk.state.value.agentActive)
    }
    @Test fun placementClampsToSmallAndRotatedScreens() {
        assertEquals(100, OverlayPlacement.coordinate(Float.NaN, 400, 200))
        assertEquals(200, OverlayPlacement.coordinate(2f, 400, 200))
        assertEquals(0, OverlayPlacement.coordinate(.5f, 200, 400))
        assertEquals(0f, OverlayPlacement.fraction(20, 200, 400))
        assertEquals(1f, OverlayPlacement.fraction(900, 400, 200))
    }
    @Test fun callbackReceiptDoesNotClaimAgentReply() {
        val message = ConnectionPresentation.ai(true, true, "accepted_by_chatgpt_webhook")
        assertTrue(message.detail.contains("accepted", ignoreCase = true))
        assertFalse(message.detail.contains("replied", ignoreCase = true))
        assertTrue(ConnectionPresentation.ai(true, false, "").title.contains("subscrib", ignoreCase = true))
    }
}
