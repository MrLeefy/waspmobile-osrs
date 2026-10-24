# OSRS overlay recovery — implemented, not deployed

**Do not uninstall WaspMobile or clear app data.** CI uses a temporary debug signer; the unsigned release is not an installable update. A safe in-place update must use the existing Oracle signing certificate. No update has been installed on the user's S23.

## Implemented

- Distinct Save and Connect actions in the main app and floating overlay. Failed Save keeps typed text; a blank token field can retain a saved token only for the same normalized HTTPS origin.
- Preserve the original phone ID and preference namespace. Successfully saved credentials migrate to Android Keystore AES-GCM; old credentials remain readable until migration succeeds.
- Encrypted, bounded chat history and draft persistence. Restoring history does not replay outgoing requests, approvals, jobs or input authorization.
- Draggable Chat / Connection / Plan overlay, with separate Pause, Allow input, Stop and Hide controls. Save/reconnect inside the overlay; retain tab and screen-constrained placement.
- Home / Connection / Settings / Diagnostics main UI, distinguishing Accessibility, saved profile, Oracle connectivity, AI subscription, callback delivery and input ownership.
- Optional messaging-only reconnection; never automatically resume game actions.
- Read-only screenshot capture of the verified OSRS window while manual/chat input is held. Taps remain protected by fresh-frame, foreground and explicit input-authorization checks.
- Mixed-protocol Oracle adapter with independent handlers, correct modern routing headers and truthful dual-version discovery. No input retries or privilege downgrade after event discovery.
- Keyboard focus-race candidate: wait for the overlay editor's actual window focus and input connection, suppress landscape full-screen extraction, and cancel pending show requests when detached. This candidate compiles, but visible-keyboard validation is not yet confirmed.

## Completed verification

Source a0251a30522b82ee44812bbb86b8236fb3b52ddc, GitHub Actions run 40134003936:

| Check | Observed result |
|---|---|
| Portable Node/MCP regressions | **142 passed, zero failed/skipped** |
| Android debug/release build and lint | **Passed; zero lint errors, 54 warnings** |
| Complete Android JVM suite | **131 total: 120 passed, 11 failed, zero errors/skips** |
| Nine new recovery JVM tests | **9/9 passed** |
| API 35 emulator instrumentation | **7/7 passed**: persistence/migration (4), overlay windows (2), main UI navigation (1) |
| Actual screen captures | Archived portrait/landscape main UI and attached overlay screenshots |

The 11 JVM failures explicitly identify missing original private S23 screenshot fixtures. Tests remain enabled. The workflow's known-fixture gate does **not** mean the complete JVM suite passed.

### Important keyboard finding

The original overlay instrumentation checked focus/editing state, not actual keyboard visibility. Screenshot review and a separate landscape probe (40135038559) showed the software keyboard absent and Android logs reported PHASE_CLIENT_VIEW_SERVED. Therefore the original 7/7 result must **not** be presented as proof the keyboard worked.

The correction in OverlayEditor.kt and the stronger actual-input-method-window assertion were committed as normal source at fbd8ba0a301993f66b8de7918d7bbf58e96f4ef2. Run **40135436819** compiled the corrected app, release and instrumentation and passed lint. At the last recorded check its emulator phase had not completed; **keyboard behavior is not certified**. Check the actual run, XML and images before changing this statement. No physical S23 keyboard/OSRS-backgrounding result has been obtained.

## Live deployment blocker

Every current Oracle_Admin call from this chat is rejected before execution:

```
Unsupported protocol version: 2025-11-25
supported: [2026-07-28]
```

The protocol adapter repair passed portable tests but is **not installed on Oracle**. Another pairing code does not fix this transport mismatch. Authorized Oracle administration must be restored before applying and validating the server change.

The original signing key is on Oracle and remains inaccessible through this broken connection. Do not distribute CI's differently signed debug APK as an update or uninstall the user's app to bypass signature checks. Preserve the existing settings, identity and app data.

## Remaining release gates

Restore an authorized Oracle administration route, deploy and test the protocol repair with rollback retained, obtain a successful visible-keyboard regression result, sign the app with its existing certificate, install as an in-place update, and verify the complete flow on the physical S23. No actual ChatGPT event callback or automatic overlay reply has been established by these tests.

## Scope and artifacts

Changes are in draft PR #3, not merged. All production code is normal reviewable Kotlin/JavaScript; temporary source-patch helpers were removed. Verification workflows are read-only after source materialization. No Swarm, 2009Scape, media project, live credential or physical phone data was changed.
