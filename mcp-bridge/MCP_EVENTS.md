# Live OSRS overlay messages through MCP Events

**Goal:** keep the official OSRS game in the foreground while sending an overlay message that **automatically triggers a subscribed ChatGPT Work chat or OSRS-specific dot**, then receive the agent's reply in the floating WaspMobile panel.

This is **not** a second AI service, nor a modification to the OSRS APK. It uses the existing WaspMobile relay, the existing MCP tools, and OpenAI's MCP Events feature. It does not route messages to Squishy's 2009Scape project.

## What is and is not possible

**MCP Events (protocol 2026-07-28)** supports server-to-ChatGPT signed webhooks when an event subscription exists. Official availability is restricted to ChatGPT Work chats (web, or desktop Cloud) and dots, subject to workspace/plan/plugin permissions.

**The ordinary ChatGPT chat interface is not an event destination.** MCP tools alone are passive; they do not cause a new message to appear in this exact existing Instant chat. An authorized Work chat or OSRS-specific dot must subscribe once. Do not pretend that this creates a response in the existing ordinary chat, copies account memory, or wakes an agent that has not subscribed.

Official reference: https://developers.openai.com/plugins/build/mcp-events

## Existing Oracle Admin connection (on-demand tools)

[Oracle Admin on-demand WaspMobile tool access](ORACLE_ADMIN_PROXY.md) is already working through the current connected Oracle Admin plugin, with no new tunnel credentials. It does not provide the MCP 2.0 event subscription required to wake a Work chat or dot on a new overlay message. Continue using a separate event-capable connector or explicitly review an Oracle Admin MCP 2.0 gateway if live push delivery is needed.

## Oracle hosting

The [Oracle VM bridge](ORACLE_DEPLOYMENT_STATUS.md) is running, but the new OSRS-specific OpenAI tunnel and Work/dot subscription are not connected yet. Create the dedicated Platform tunnel, then run sudo wasp-osrs-link-tunnel interactively on Oracle. The VM does not require Windows Simba or Tailscale just to host the phone relay.

## Event loop

1. User signs into OSRS on the S23, enables WaspMobile Accessibility and explicitly connects the private HTTPS WaspMobile relay.
2. User opens an OSRS-only ChatGPT Work chat or OSRS dot, installs the custom MCP plugin, and asks it to subscribe to `overlay.message.created` filtered by the phone's displayed **Phone ID**.
3. ChatGPT supplies a signed callback destination. The MCP server validates the callback URL, verifies a signed challenge and stores **one** encrypted subscriber record. The phone now says “MCP EVENTS SUBSCRIBED” instead of “MCP inbox only.”
4. User types a request inside WaspMobile's floating AI panel while OSRS remains the visible task. The phone sends that request over the existing authenticated HTTPS poll.
5. The relay deduplicates the message, dispatches a signed `overlay.message.created` event to the verified ChatGPT callback, and reports whether the callback accepted delivery. **A 2xx acknowledgement means webhook receipt, not that ChatGPT has replied.**
6. ChatGPT resumes the subscribed Work chat/dot task. It can call `wasp_inbox`, `wasp_observe`, `wasp_reply`, `wasp_offer_plan`, etc. The phone displays the reply on its next relay poll.
7. Any gameplay script still needs an **explicit local approval** in the phone panel. The existing one-S23/one-window/one-input-controller constraints remain in force.

Use the separate [agent instructions](AGENT_WORKFLOW.md) to tell the subscribed Work chat/dot to reply to every received overlay event through `wasp_reply`.

## Enable events on the Windows PC once

The main bridge is still the same `mcp-bridge/bridge.js` process. It now serves both MCP 2025 tool clients and MCP 2026-07-28 event-enabled clients. It does not require Swarm or a separate LLM.

From the local repository folder:

```powershell
cd mcp-bridge
npm ci
npm test

# Continue using the existing private WASP_DEVICE_TOKEN.
# Generate a different 32-byte secret to encrypt event subscriptions on disk:
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:WASP_EVENTS_STORAGE_KEY = [Convert]::ToBase64String($bytes)

$env:WASP_EVENTS_ENABLED = 'true'
$env:WASP_EVENTS_ALLOWED_CALLBACK_HOSTS = 'openai.com,chatgpt.com'
node bridge.js
```

**Store this encryption key securely.** A different encryption key after restart prevents reading existing subscriptions. To rotate it, unsubscribe/recreate the event subscription; never publish keys or pairing tokens in Git, screenshots or this conversation. A corrupted encrypted store fails closed instead of silently replacing it.

The server allows HTTPS callbacks only on port 443, checks the hostname against the configured allowlist, resolves DNS to public IPs only, pins the validated address for each TLS connection, verifies the TLS certificate, rejects redirects, caps response size and uses Standard Webhooks HMAC signatures. If ChatGPT legitimately returns a different callback domain, verify that domain is genuinely controlled by the expected service before adding it to the local allowlist.

The encrypted subscription file is stored by default under the PC user's private configuration directory. No webhook secret is sent to the Android app.

## Set up the ChatGPT event subscriber

1. Bring up the private Windows bridge and supported [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels); ensure the tunnel is connected. This does not require opening a public listener.
2. In a workspace/plan supporting Work/dot events, add the custom WaspMobile MCP plugin and refresh tool/event discovery so the client negotiates MCP 2.0.
3. In **an OSRS-only Work chat or OSRS-specific dot**, select the WaspMobile plugin and request:

   > Subscribe to `overlay.message.created` for my paired WaspMobile phone. When an overlay message arrives, read the new request using `wasp_inbox` and reply using `wasp_reply`. When the message asks for an action, observe the OSRS window first. For multi-step game input, offer a bounded plan and wait for my explicit approval in the phone overlay. Never route this to 2009Scape or another conversation.

4. Verify the server reports an active event subscription in the phone panel and send a harmless message (“Can you see my request?”). Confirm a response appears **without leaving OSRS**.

The MCP Events provider is optional. Without it, the phone continues to store messages in the MCP inbox for manual retrieval by an active agent; it does **not** falsely claim that sending a message started a ChatGPT turn.

## Limits

- One authenticated phone and **one** active Work/dot subscription at a time.
- Messages are text; optional screenshots are pulled by the subscribed agent via `wasp_observe`. The event itself does not stream the screen or run game inputs.
- Active messages are limited to 1,000 characters. Duplicated phone outbox messages do not emit duplicate events.
- Notifications are best-effort with bounded retries. No historical replay cursor is claimed; messages missed during a complete outage remain in the inbox and can be retrieved later.
- Actual Work/dot event delivery requires account eligibility, a working private tunnel, valid callback verification and a live phone. **All of those remain untested for the user's account/device until pairing happens.**
- The Android keyboard remains inside an accessibility overlay, but focus/IME behavior on the physical Samsung S23 is not yet validated; verify the OSRS game stays connected during a sample text entry.
- Automated OSRS gameplay may violate Jagex rules. Gesture completion does not prove game-state success, and this system does not guarantee account safety.

## Verification scope

The event implementation is regression-tested for MCP 2.0 discovery, modern subscribe handling, HTTPS callback validation, concurrent subscription locking, forged/expired subscription failures, HMAC signatures, callback challenge verification, idempotency, device scoping and encrypted subscriptions. These are offline tests; they are **not** evidence that the user's current ordinary ChatGPT conversation is an event subscriber.

## Executed development verification

- **105/105 Node/MCP tests passed**, including modern MCP 2.0 stdio negotiation and event discovery, webhook verification/signatures, encrypted persistent subscription, deduplication, renewal, unsubscription, DNS/private-address rejection and callback failure cases.
- **Android debug APK built and debug-signed** as version 2.1.2-mcp-events-preview (version code 5); Android lint zero errors and 44 warnings.
- **111/122 Android JVM tests passed**, zero skipped. The 11 remaining failures depend on three unavailable private S23 screenshots. All 13 tests in the AgentDeskState event state suite passed.
- **Actual ChatGPT Work/dot event subscription, OSRS login persistence while keyboard is open, and physical S23 behavior are unverified.** This is a test prerelease, not a finished production app.

Detailed evidence and APK hash: [mcp-events-results.json](mcp-events-results.json).
