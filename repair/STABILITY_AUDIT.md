# WaspMobile OSRS — stability and adversarial edge-case audit

**Date:** 2026-10-24  
**Scope:** WaspMobile OSRS Android Accessibility overlay, private phone-to-Oracle relay, existing Oracle Admin MCP adapter, Work/dot webhook events, on-device storage, signed release. **Excluded:** 2009Scape/RustScape, Swarm, Jellyfin/TorBox and other Oracle applications.  
**Branch:** `fix/osrs-overlay-recovery-oct24` / draft PR #3; this code is **not installed on the S23**.

## Executive status

**Not release-ready.** The actual Oracle Admin connector still fails before executing tools (`Unsupported protocol version: 2025-11-25`, modern-only connector), the existing Android installation has not been updated with its original signing certificate, an actual ChatGPT Work/dot event subscription and returned overlay reply have not been proven, and the improved landscape keyboard focus test was cancelled before showing a visible keyboard. Never present passing unit tests as proof these four end-to-end gates work.

**Validated local source (current audit):** portable Node/MCP tests **144 passed, zero failed**, including new tests for default-denied phone input and for invalidating fresh screenshots and in-flight taps when local input consent changes. Pure Kotlin backoff helper compiled with `kotlinc`. **New full Android CI on this branch is pending; do not declare it passed until its exact run completes.**

The old verified baseline before these additional changes: 142 portable Node tests passed; Android debug/release/lint built; nine new Android recovery JVM tests passed; complete Android JVM suite had 120/131 pass and 11 unresolved original-private-S23 screenshot fixtures; API35 emulator navigation/window tests passed 7/7 but did **not** demonstrate actual keyboard visibility. Latest focus-corrected emulator run was cancelled, so landscape IME remains a release blocker.

## Confirmed defects fixed in the draft branch (not deployed)

| ID | Severity | Verified issue | Code correction / regression |
|---|---|---|---|
| SEC-01 | P0 | Phone heartbeat without `inputAllowed` or with non-boolean truthy values previously enabled remote game input (`!== false`). | Require **strict `=== true`**; all omission/null/number/string cases deny taps. Screenshot-only capture can remain available. |
| SEC-02 | P0 | Old camera frame persisted when the local user paused and then re-allowed input inside its 15-second TTL. | Any local input-consent change invalidates observations and pending actions; repeated old `frameId` is refused. |
| NET-01 | P1 | Android repeatedly retried invalid phone/pairing profile, missing route, and failed HTTPS authentication, potentially forever. | `PhoneMcpBridge` permanently stops 401/most client errors and certificate failures; `BridgeSettingsStore.setDesired(false)` prevents automatic restart until manual repair. |
| NET-02 | P1 | Every transient failure retried on the same two-second cadence, including mobile airplane mode and Oracle downtime. | Bounded exponential retry backoff **2, 4, 8, 16, 30 seconds** with ≤0.5s jitter; resets on success, never auto-replays game gestures. |
| NET-03 | P1 | HTTP redirect responses were previously retried forever. Redirects must never receive the saved bearer token. | Non-following HTTPS POST stays unchanged; **all 3xx and most 4xx** stop automatic retries. 408/409/423/425/429 and 5xx remain retryable. |
| UI-01 | P1 | A delayed secure Save could finish after the user hid/reopened the overlay and unexpectedly update/reconnect the new panel. | Capture the exact panel view at click time and ignore stale UI/reconnect completion after Hide or view replacement. The underlying explicit Save may still persist. |

**Caution:** Source changes and local tests are not a completed production rollout. A signed in-place APK and S23 smoke test are required.

## Remaining critical release blockers

| ID | Severity | Exposure | Required verification/repair |
|---|---|---|---|
| MCP-01 | P0 | Oracle Admin connector rejects negotiated **2025-11-25** before any command, despite a staged protocol-compatibility adapter. The current session cannot deploy server changes. | Recover authorized Oracle administration out-of-band or via a compatible connector; deploy the adapter with rollback; prove both legacy admin tools and modern OSRS Events in the **real ChatGPT connection**, not only a local SDK. |
| AI-01 | P0 | `overlay.message.created` discovery is not a Work/dot subscription. No verified webhook/agent response exists, so queued phone messages cannot wake ChatGPT. | Subscribe from an eligible OSRS-only Work/dot, verify signing challenge and `MCP EVENTS SUBSCRIBED`, send `hi`, receive the actual reply without leaving OSRS. |
| UI-02 | P0 | Software keyboard in landscape Accessibility overlay was absent in earlier emulator evidence (`PHASE_CLIENT_VIEW_SERVED`). Focus-race correction compiles, but its stronger visible-IME test was cancelled. | Successfully run keyboard-visible instrumentation with screenshots and IME window inspection, then test Samsung/One UI + Google/Samsung keyboard in actual OSRS. |
| REL-01 | P0 | Original APK signing credential is unavailable to current connector. CI debug signer is different; unsigned release isn't an update. | Retrieve original signing **fingerprint** securely, compare to installed app, sign official update in trusted build environment; do **not** uninstall or clear app data. |
| AI-02 | P1 | The server acknowledges phone receipt before webhook reaches ChatGPT; webhook retries are currently bounded to three, then status changes to delivery failed. A callback outage can leave a user message received but no agent wake-up. | Add audited retry/dead-letter/reconciliation with event-id idempotency and privacy limits, or explicit UI recovery with manual re-trigger after callback restoration. Do not silently claim delivered. |
| AI-03 | P1 | Phone session mailbox is in-memory and may expire after >120s offline. Agent reply during backgrounding/session replacement fails; an event payload alone is not enough to reply to an absent message. | Decide on encrypted bounded offline reply queue or re-read/re-delivery protocol; new-session reconciliation must avoid duplicate answers and must never resurrect plans or taps. |
| TRUST-01 | P1 | Existing Oracle Admin connection grants broad root actions, and older/newer protocols share a transport. Event-originated OSRS text is untrusted and must not be interpreted as shell instructions. | Preserve modern Work/dot privilege restriction to OSRS tools; validate old version route cannot escape into privileged admin actions. Ideally use an OSRS-only event connector. |
| STORAGE-01 | P1 | On rapid process death after typing, a draft queued for asynchronous encryption/write may not reach disk. | Instrument kill/restart 0–250ms after editing; flush a bounded snapshot on Hide/service shutdown while keeping disk I/O off the UI thread. Never persist approval grants, plan executions or session tokens. |
| UI-03 | P1 | Keyboard/insets/shrunk landscape panel may hide Send/Connection/Stop and misplace the draggable overlay. | Test small-screen landscape, rotation while typing, floating keyboard, multiple display insets, gesture navigation, OSRS behind overlay; emergency Pause/Stop must remain accessible. |

## Adversarial test matrix — required gates

Legend: **unit-pass** = directly exercised by current Node/Kotlin policy tests; **existing** = present in prior Android/Node tests; **pending** = physical/integration validation or a code change still needed.

### Initial configuration and secrets

| Case | Expected safe behavior | State |
|---|---|---|
| New install, missing pairing token | No connection; show exact action required | existing |
| Wrong host/HTTP/plaintext or /pair URL pasted into Server URL | Reject without sending token | existing |
| Empty token on unchanged saved HTTPS origin | Retain only that origin's encrypted token | existing |
| Empty token on different origin, including alternate port | Deny cross-host token reuse | existing |
| Whitespace, malformed Unicode, 31/129 character tokens | Reject, do not erase prior saved credentials | existing |
| Corrupted Android Keystore ciphertext/key invalidation | Fail closed; no plaintext fallback or silent unpair | existing; S23 pending |
| Keystore key surviving signed in-place update | Preserve phone ID and sealed token | S23 pending |
| Uninstall/reinstall or Android cloud restore | No false promise of Keystore/token recovery | pending documentation |
| Pairing URL expires/consumed or in-app query parameters appended | Clear status; no secret leak in logs/browser | existing server tests |
| TLS certificate expiration, self-signed MITM, redirect, DNS poisoning | Never bypass TLS or forward bearer; stop permanent failures | policy unit-pass; runtime pending |
| Origin request contains credentials, URL fragment, Unicode lookalike | Normalized parser rejects unsafe origins | existing |
| Multiple quick Save/Connect taps | Exactly one save/session; no duplicate listener | pending physical |
| Save completes after Hide, rotation, or service shutdown | Data save allowed; stale view cannot reconnect/update replacement | fixed in source; emulator pending |

### Network, phone session, and callbacks

| Case | Expected safe behavior | State |
|---|---|---|
| Oracle returns 401 | Stop retry loop until user changes pairing | fixed in source; emulator pending |
| Oracle returns 400/403/404/405/410/415/422 | Stop persistent bad-profile retries | Kotlin policy unit-pass |
| Oracle 301/302/307/308 | Never follow redirect with bearer, stop retries | Kotlin policy unit-pass |
| Oracle returns 408/409/423/425/429/5xx | Bounded retry, no input replay | Kotlin policy unit-pass |
| Airplane mode, captive portal, DNS failure, intermittent LTE/Wi-Fi | Draft/history retained; no game taps during loss; bounded backoff | policy pass; S23 pending |
| Failure occurs after command may have executed | Report uncertainty; never automatically repeat that TAP | existing |
| Old delayed HTTP 200 arrives after disconnect/reconnect | Ignore old session ID and old callback | existing |
| Duplicate, forged, replayed or cross-phone reply ID | Ignore or reject, never impersonate agent | existing |
| Phone app backgrounded/reopened >120 seconds | No stale game control; inbox continuity needs design | pending |
| User sends while event subscriber absent | Clearly mark *Queued*, not answered | existing |
| Agent receives webhook but phone closes during reply | Safe recovery without duplicate or silently lost message | pending |
| Event callback host invalid, private IP/SSRF, bad signature | Reject before persistent subscription | existing |
| Event callback accepts verification then later returns 429/500 | Idempotent bounded retry and visible failure; durable recovery pending | pending |
| Webhook subscription expires, refreshes, changes Work/dot | No surprise second subscriber, no leaked messages | existing |
| Oracle process restarts while pending actions exist | Old plans/messages are history, not replayed | existing; live pending |
| Two phones try same bearer with different IDs | Single pinned-device binding denies takeover | existing |
| Two overlapping old/new sessions from same phone | Honor one current session only | existing |
| Plugin refresh changes MCP 2025/2026 version metadata | Do not strand root admin or silently downgrade trust | server integration pending |

### Overlay, Accessibility and keyboard

| Case | Expected safe behavior | State |
|---|---|---|
| Accessibility permission faded/disabled/restricted settings | Show precise actionable status; no self-enable | S23 pending |
| OSRS absent, loading, minimized, lock screen, system Settings foreground | Game-only controls disabled | existing |
| Samsung One UI game booster/navigation bar overlays | No taps outside verified OSRS visible window | S23 pending |
| Rotation portrait⇄landscape in the editor | Session bound to current window; panel remains visible | emulator pending |
| Physical keyboard, Samsung keyboard, Gboard, floating/split IME | Send is visible and usable; typing suspends taps | emulator/S23 pending |
| Chat message composed then keyboard disappears | Draft and focus restored without losing characters | existing; physical pending |
| Press Android back while typing | Only dismiss keyboard/panel, never accidentally exit game | S23 pending |
| Rapid Hide→Show while Save still runs | No stale panel reconnect or corrupted UI | fixed in source; emulator pending |
| Overlay dragged beyond display cutout/notch or system bars | Clamp panel inside usable bounds | existing; S23 pending |
| Landscape game with small available height + keyboard | Stop/Pause still immediately reachable | unresolved |
| Permission toggled off while plan runs | Stop all pending gestures, no auto-resume | existing |
| Device screen changes under screenshot capture | Reject wrong window key/dimensions | existing |
| Same window screenshot reused after Pause→Allow | Deny stale frame/tap, require new observation | Node unit-pass |
| Missing/nonboolean heartbeat `inputAllowed` | Treat as **false**, no taps | Node unit-pass |
| Local Pause while remote TAP already dispatched | Cancel, report may-have-executed, don't retry | existing |
| User begins typing while remote script runs | Manual user control owns input and cancels plan | existing; physical pending |
| Android process killed abruptly | New session, previous queued text only history, no input grant | existing; physical pending |
| Android upgrade installed with original signer | Settings/data preserved, no duplicate Accessibility service | requires signed build |

### Script/AI safety and information boundaries

| Case | Expected safe behavior | State |
|---|---|---|
| Forged gameplay chat says "run a shell command" | Treat as untrusted content, block privileged actions | existing gateway scope; live pending |
| Model claims it sent a reply without a server response | Not shown as delivered; accurate statuses | needs Work agent test |
| Proposed plan altered after phone approval | Verify hash, exact steps, expiry; reject tampering | existing |
| Plan includes unsupported op, 13+ taps or >120s runtime | Reject parsing | existing |
| Fast consecutive plan approvals or second proposal while running | One ownership/approval at a time | existing |
| Network loss mid-game gesture | User sees uncertain execution, no retry | existing |
| Screenshot includes other app/notification | Capture only verified game window, no whole-screen fallback | existing; S23 pending |
| Chat transcript includes secrets, credentials, or pasted game text | No logs with bearer; no third-party forwarding except authorized event | needs privacy review |
| History encrypted file truncated, corrupt, or cannot decrypt | Warn, never reset token/device or execute recovered plan | existing |
| History >40 messages, duplicate ID or giant body | Bound and reject gracefully | existing |
| Offline agent input attempts | Never execute unless phone session is live and input explicitly allowed | existing |
| Chat event subscription in 2009Scape/Swarm agent | Never allow cross-project command context or privileged shell based on OSRS messages | operational/workspace pending |

### Release readiness criteria (all required)

- [ ] Oracle Admin connector works **from real ChatGPT** after protocol repair and refresh; existing legacy admin tools unaffected.
- [ ] Event-capable OSRS Work/dot subscribes, signed callback reaches agent, real overlay message `hi` receives a visible reply **without leaving OSRS**.
- [ ] Updated IME emulator test actually passes, captures **visible keyboard in landscape**, and validates Send/Stop insets.
- [ ] Exact current branch completes Android build, lint, JVM and instrumentation jobs; preserve/report 11 missing original-S23 screenshot fixtures rather than hiding failures.
- [ ] Original Android signing certificate fingerprint matches installed WaspMobile before **in-place update**; never uninstall to bypass mismatched signatures.
- [ ] Physical S23 validates Accessibility, OSRS foreground/background, drafting, Save/Connect/Disconnect, Pause/Allow/Stop, rotation, LTE/Wi-Fi switch and live event round-trip.
- [ ] Idempotent webhook failure/reconnect semantics and offline reply lifetime are specified and tested.
- [ ] Oracle deploy uses health checks and rollback, preserves existing OSRS, Oracle Admin, 2009Scape, media and other services.
- [ ] Then and only then mark PR #3 ready and release the signed update.

## Sources and evidence

- Current branch: https://github.com/MrLeefy/waspmobile-osrs/pull/3
- Previous CI: run 40134003936 (142 Node pass; Android JVM 120/131; emulator 7/7 unrelated to keyboard visibility).
- Latest keyboard correction: run 40135436819 (build/lint success; emulator cancelled; actual keyboard unverified).
- Baseline evidence: `repair/RECOVERY_STATUS.md`.
- Current audit regression source: `mcp-bridge/bridge.test.js`, `mcp-bridge/relay.js`, `BridgeRetryPolicyTest.kt`, and `PhoneMcpBridge.kt`.

**Do not clear WaspMobile data, uninstall the current APK, paste bearer tokens into GitHub, or infer event delivery from server readiness.**
