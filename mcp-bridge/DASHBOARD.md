# Agent Desk and approved mobile scripts

This development build adds a native floating **Agent Desk** and a bounded mobile-plan executor. It extends the audited bridge; it does not automatically connect ChatGPT, sign into an OpenAI account, install an APK on a phone, or merge the draft PR.

## What is implemented

The existing WaspMobile pill has an **AI** button. It opens a right-side panel with a session-scoped transcript, queued requests, connected-agent replies, exact proposed script steps, a SHA-256 fingerprint, local Approve/Reject controls, and run progress.

- **Take control / type** pauses input, cancels the approved plan, and enables the keyboard. Editing never runs alongside remote taps.
- **Watch / allow input** explicitly leaves editing mode and permits fresh, independently validated remote input. It does not resume a cancelled script.
- **Send to MCP inbox** queues a request for the connected agent to read with `wasp_inbox`. Acknowledgements and message IDs prevent duplicate delivery after a network retry.
- **Share to ChatGPT** opens Android's standard share chooser with the request and device identifier, never credentials. Choose ChatGPT and this OSRS/WaspMobile conversation yourself. It is not an automatic dot-routing API.
- **Approve this plan** authorizes only the exact displayed proposal and digest. Expired, replaced, invalid or already-consumed approvals cannot start it.
- The pill shows **STOP AI** while a plan runs, **PAUSE REMOTE** when remote input is enabled, or **ALLOW REMOTE** after manual takeover. It cannot accidentally present the legacy START action as the stop control for an AI run.

Watch mode is non-focusable. The expanded panel is a real touchable window: its rectangle and the original pill are explicitly excluded from game input. The keyboard, window focus and OEM overlay behavior still require S23 instrumentation.

## Important: OSRS conversation only, not embedded ChatGPT

The panel is a **bidirectional MCP mailbox and execution dashboard**. The agent still runs in ChatGPT, in this OSRS/WaspMobile conversation under the user's subscription. Other game projects, conversations, agents and task queues are separate; this panel must not direct requests to them. The relay contains no OpenAI API key, API billing integration, browser-session scraping, or copied ChatGPT cookies.

Sending a message to an MCP server does not, by itself, wake an agent or start an LLM turn. An active, authorized agent must call `wasp_inbox`, then `wasp_reply`. The panel labels this limitation instead of suggesting a response is already running. “MCP agent recently active” means an inbox read or reply was observed recently; it is not proof that the OSRS agent is continuously watching.

For the first interaction, open ChatGPT or use the share button and ask the agent in this OSRS conversation to read the paired WaspMobile inbox. Establish the custom MCP connection using the [bridge setup](README.md) first. If the tools are not installed/discovered in ChatGPT, the panel will wait; installing this APK alone cannot add tools to that conversation.

OpenAI's separate Sign in with ChatGPT mechanism can authorize eligible application requests, but it does not grant access to existing ChatGPT conversations or memory. It was **not** used here to impersonate an existing conversation or connect another project's agent. A seamless embedded-dot chat remains dependent on a verified supported integration, not an inferred capability of MCP.

Official references checked during implementation:

- https://developers.openai.com/api/docs/guides/secure-mcp-tunnels
- https://developers.openai.com/siwc/token-sharing-open-source
- https://learn.chatgpt.com/docs/dots

## New MCP tools

| Tool | Contract |
|---|---|
| `wasp_inbox(deviceId, afterSeq)` | Read current-consent-session messages and plan progress; bounded transcript with an explicit cursor |
| `wasp_reply(deviceId, replyTo, messageId, text)` | Reply to a real visible user request. Reusing the same ID/content is idempotent; changing its content is rejected |
| `wasp_offer_plan(deviceId, requestId?, plan)` | Validate and offer an immutable script proposal. It never starts the script remotely |
| `wasp_cancel_plan(deviceId)` | Mark a pending/running plan cancelled; the phone receives this on the next poll |

Together with the existing eleven tools, the bridge now exposes **15 MCP tools**. The existing `wasp_pause` also preempts a pending single-step request and holds remote input until the user allows it locally. Cancellation cannot retract a gesture already submitted to Android.

## Mobile plan format and limits

A plan is JSON data, not executable Kotlin, JavaScript or Pascal. Strict field validation exists independently in Node and Kotlin. Both implementations use the same 21 valid/invalid contract examples in `contracts/mobile-plan-v1-cases.json`.

Supported steps:

- `observe`: capture and validate a fresh game-window frame.
- `wait`: wait 100–5,000 ms, checking cancellation/readiness in short intervals.
- `assertColor`: require a sufficiently strong, unambiguous color component inside a specified region.
- `tapColor`: acquire a fresh raw frame, find a qualifying color component, and tap an actual matching pixel after another authorization check.

Each proposal is limited to **24 steps, 12 tap attempts and 120 seconds**, with no loops, arbitrary code, shell commands, account operations or recursive execution. It expires if not approved within two minutes. The sum of explicit waits must leave runtime for other operations.

Color steps specify a normalized game-window region, RGB triplet, per-channel tolerance 0–40, and a minimum of 4–400 sampled pixels. Detection runs on raw, uncompressed window pixels scaled to at most 512 pixels on either axis. The matcher rejects regions dominated by the selected color and similarly sized competing components. It picks a pixel within the accepted component, not an arbitrary bounding-box center.

**Color recognition is not semantic item recognition.** Matching a color does not prove that an item is safe to drop, that a tree is reachable, or that a click will perform the expected game action. The current plan language intentionally has no generic “drop all,” trading, purchasing, or inventory-identity claim. Do not approve a color-tap plan whose actions you do not understand.

A zero-input starting example is provided in [examples/observe-only.plan.json](examples/observe-only.plan.json). Review and approve this first after pairing; it captures, waits briefly, and captures again without tapping.

## Execution and cancellation

The phone executes approved plans locally, without an LLM round trip for each step. The private bridge must still answer heartbeats: an expired six-second local connection lease stops further actions. Every step checks the selected game window, unlocked display, input ownership, calibration state, session and time budget. Each tap requires a fresh observation. Unknown gesture outcomes stop the plan; they are never retried automatically.

Progress is reported back through polling and read with `wasp_inbox`. A final frame is captured locally before completion is reported. **COMPLETED means the script's bounded operations finished, not that the game objective was independently verified.** The agent must request a fresh `wasp_observe` after the run to evaluate its outcome.

Local Take Control/STOP AI cancels immediately at the next coroutine/dispatch guard. Agent cancellation normally arrives on the next successful poll. Loss of the network is bounded by the connection lease. An input already in Android's dispatch queue cannot be undone by either path.

Switching applications, locking the phone, opening a keyboard, losing the verified game window, changing display geometry, starting a local script, disabling Accessibility, disconnecting, or restarting the relay invalidates the relevant authorization. No plan is automatically resumed after reconnect. A new local approval is required.

## Session data and permissions

Transcripts, message receipts and plans are memory-only. There are at most 40 transcript entries, eight unsent phone requests, two messages transferred per poll, 512 message receipts per session, and one pending/running plan per phone. Restart/reconnect clears or invalidates old requests and approvals. The relay issues a new epoch when its in-memory desk is recreated so stale cursors and approvals cannot masquerade as the new session.

The existing private token authenticates the phone relay. It remains a privileged shared relay credential, not per-user multitenant authorization. Pairing secrets never appear in plan messages or the Android share request. Existing TLS, token, window-only capture and backup-exclusion protections remain in place.

## Executed verification for this update

| Check | Actual result |
|---|---|
| Node / MCP suite | **78 passed, 0 failed, 0 skipped; clean exit** |
| New Android dashboard/plan tests | **30 passed, 0 failed** |
| Full Android unit suite | **114 total: 103 passed, 11 failed, 0 skipped**; the 11 failures require the missing original S23 screenshots |
| Shared Node/Kotlin plan contract | **21 synthetic cases accepted/rejected consistently** |
| Android debug APK | **Build passed**, version `2.1.0-agent-preview`, version code 3; not installed on the phone |
| Android lint | **0 errors/fatals; 44 warnings** |
| npm production dependency audit | **0 known vulnerabilities reported at execution time** |
| Real S23 / Windows Simba / ChatGPT pairing | **Not tested in this environment** |

Tests ran in an isolated Linux aarch64 workspace, with Node 20, Java 17, Gradle 9.1 and Android SDK 36. The HTTP mailbox and official MCP SDK exchanges were executed, not inferred from source inspection. Android UI, actual phone gesture delivery and live gameplay remain outside those tests. The complete Android suite remains visibly failing until the real screenshot inputs are supplied; no tests were silently skipped or synthetic screenshots substituted.

## Activation checklist

1. Build/install the preview APK with a signing key compatible with the intended installation; this code uses version code 3 and version name `2.1.0-agent-preview`.
2. Disable/re-enable the Accessibility service after updating its window capability; configure the private HTTPS bridge origin and token.
3. Run `npm ci` and the stdio bridge on the PC, configure the supported MCP tunnel, and install that connection in ChatGPT. This step is separate from the Android relay pairing.
4. Confirm `wasp_devices` and a fresh `wasp_observe` work from the actual ChatGPT conversation.
5. Open AI in the HUD, submit a request, ask the agent in this OSRS conversation in ChatGPT to read the inbox, and confirm a reply appears.
6. Offer the observe-only example, review the exact proposal on the phone, and approve it. Test STOP AI and Take Control before permitting a tap.

The build is not declared production-ready until the original S23 fixture suite, actual device behavior, real Windows Simba installation and actual ChatGPT tunnel have been verified. The legacy unauthenticated Swarm server remains separate and must not be exposed as an MCP endpoint. Remote split-screen/Secure Folder selection and Android API 30–33 remote capture remain unsupported.

See `dashboard-results.json` for executed checks in this revision and [AUDIT.md](AUDIT.md) for the preceding audit and unchanged limitations.
