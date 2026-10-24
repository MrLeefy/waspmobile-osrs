# OSRS overlay recovery — tested source, not deployed

**Do not uninstall WaspMobile or clear app data.** The current CI APK uses a different debug signer; the unsigned release is not an installable update. Reuse the existing Oracle signing key and verify the signer against the installed app before publishing an update.

## Implemented

- Distinct Save and Connect actions, including inside the floating overlay. A failed Save does not clear the typed token. A blank token field can retain a saved token for the same normalized HTTPS origin.
- Preserve the original phone ID and preference namespace. Successfully saved tokens migrate to Android Keystore AES-GCM; old credentials remain readable until migration succeeds. A different server cannot silently receive a retained token.
- Encrypted, bounded chat history and draft persistence. Restore history/drafts, not old outgoing requests, approval grants, automation jobs, or permission to inject input.
- Draggable Chat / Connection / Plan overlay. Separate Pause, Allow input, Stop, and Hide controls. Connection editing/reconnecting no longer requires leaving OSRS for the main app. Overlay tab and placement are retained and constrained to the visible screen.
- Home / Connection / Settings / Diagnostics main UI. It distinguishes Accessibility availability, saved credentials, transport connection, AI subscription, callback delivery and input permission.
- Optional messaging-only reconnection; taps and plans never auto-resume.
- Fresh, read-only capture of the verified OSRS window remains possible while chat/manual control holds input. Taps remain blocked and require current authorization and an unobscured game target.
- Mixed-protocol Oracle gateway candidate: independent handlers for MCP 2025-11-25 and 2026-07-28, truthful dual-version discovery, reserved-envelope translation, required modern routing headers, and no action retry. An event-enabled session cannot regain privileged Oracle actions by changing protocol metadata.

## Executed evidence

| Check | Result | Evidence |
|---|---|---|
| Portable Node/MCP regression suite | 142 passed, 0 failed, 0 skipped | Run 40134003936, MCP job 120991784914, source a0251a30522b82ee44812bbb86b8236fb3b52ddc |
| Android debug/release build and lint | Passed; 0 lint errors, 54 warnings | Run 40133047224, source a725bf499fa45372ed7735177849347184bbb96c |
| Complete Android JVM suite | 131 total; 120 passed, 11 failed, 0 skipped | Same run; XML failure messages explicitly name absent original S23 images |
| Actual Android 15 emulator tests | 7 passed, 0 failures/errors/skips | Same run: BridgePersistenceTest (4), OverlayWindowTest (2), MainScreenTest (1) |
| New overlay recovery JVM suite | 9/9 passed | OverlayRecoveryTest in the complete JVM run |
| Original Oracle integration suite | Not run in the recovery environment | Separated as test:oracle, not misrepresented as portable passing tests |
| Physical Samsung S23 / live OSRS | Not tested | No connected device-control path currently works |
| Live Oracle deployment | Not performed | Current Oracle_Admin calls reject protocol 2025-11-25 before tool execution |
| Signed in-place APK update | Not produced | Original signing key remains on Oracle and is inaccessible through the broken connector |

The final capture-retention-only instrumentation revision is being rerun in 40134003936; it does not change the Android production code verified above. The workflow archives the actual XML, screenshots, exact source, and debug/unsigned APKs. Its known-fixture gate does **not** mean the complete JVM suite passed.

## Remaining blocker

The live Oracle connection currently returns:

```
Unsupported protocol version: 2025-11-25
supported: [2026-07-28]
```

This is separate from the S23's saved pairing values. Generating more phone pairing links does not repair a protocol rejection at the ChatGPT-to-Oracle transport boundary. Restore authorized Oracle administration access, then deploy and validate the mixed-protocol gateway on the actual tunnel before claiming recovery. Keep the old gateway and unit/profile backup for rollback. Do not weaken the event-session privilege boundary.

Once Oracle access is restored, sign the built release with the original certificate, verify the certificate digest and package/version metadata, install it as an in-place update, and perform a real S23 test: save/reopen, chat reconnect, keyboard, screenshot under manual hold, one locally approved harmless input, and an actual subscribed-agent reply. No live automatic ChatGPT subscription is established by these tests.

## Repository scope

All recovery code is in normal Kotlin/JavaScript source files. The temporary encoded source-transfer bundle has been removed. The branch is OSRS-only and is not merged to main; no Swarm, 2009Scape, media service, production credential, or user's phone data was changed.
