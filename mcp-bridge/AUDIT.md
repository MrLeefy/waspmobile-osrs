# Verification audit — 2026-10-23

**Historical audit:** the subsequent Agent Desk / approved mobile executor revision is documented in [DASHBOARD.md](DASHBOARD.md), with current checks in [dashboard-results.json](dashboard-results.json). The table below describes the earlier hardening revision, not the latest expanded suite.

## Verdict

**The original PR was not complete or release-ready.** This revision fixes reproducible bridge problems, builds an Android debug APK, and adds exercised regression coverage. The draft must remain unmerged until the missing fixture tests and actual Windows/S23/tunnel validation are completed. No live gameplay, account login, APK installation on the phone, or production deployment was performed during this audit.

Audited starting commit: `93a99019d0dc6b6a2231a7b44c877bd554c3513e`, PR #2, branch `feature/mcp-android-agent-oct23`.

Tests ran in an isolated Linux aarch64 workspace with Node v20.20.2, Java 17, Gradle 9.1.0 and Android API 36 build inputs. This does not substitute for device instrumentation. Detailed test counts and failure descriptions are in [audit-results.json](audit-results.json).

## Executed verification

| Check | Observed result |
|---|---|
| Original Node tests | Seven checks printed success, but the process hung; external timeout terminated it with exit 124 |
| Revised Node tests | **41 passed, 0 failed, 0 skipped; clean exit 0** |
| MCP protocol integration | Official SDK client tested both in-memory and against the actual stdio child process; discovery, schemas and EOF shutdown exercised |
| Desktop adapter tests | Mock executables exercised compilation markers, timeout/cancellation and identity changes; no real Windows Simba.exe was available |
| Original Android unit suite | 73 total, 62 passed, 11 failed because original S23 image fixtures were unavailable |
| Revised Android full unit suite | **84 total, 73 passed, 11 failed, 0 errors, 0 skipped**; all 11 failures require the missing original images |
| Revised portable Android selection | Separate filtered run: 64 tests passed, no failures/errors/skips; the full result above remains authoritative for release gating |
| Android debug APK | **`:app:assembleDebug` succeeded**; debug artifact produced, not deployed or installed |
| Android lint | **`:app:lintDebug` succeeded**, zero errors/fatals, **36 warnings** remain |
| npm dependency audit | `npm audit --omit=dev --audit-level=high` reported zero known vulnerabilities at execution time |
| Patch formatting | `git diff --check` succeeded |

The full Android suite is intentionally still red. Test assertions were not removed, skipped, or replaced with invented screenshots to obtain a green result.

## Corrected defects and covered cases

1. **Hanging Node tests and orphaned processes.** Importing the old bridge attached a global readline listener. The replacement uses explicit factories, the official MCP SDK, bounded stdio buffering, and cleanup on EOF, signals and shutdown.
2. **Pause blocked behind pending work.** Pause now supersedes queued requests. A delivered gesture is reported as possibly executed; the bridge never pretends Pause can retract it.
3. **Stale or wrong-window taps.** A tap must present the exact, fresh, one-use frame ID from the selected game window. Session reconnect, screen rotation, window geometry, input and expiry invalidate that authorization.
4. **Stale foreground-package authorization.** Android now checks actual interactive window identity, focus, root package, display lock and obscuring windows. Remote multi-window selection is deliberately disabled rather than guessed.
5. **Full-phone screenshot privacy and capture races.** Remote observation uses API 34+ `takeScreenshotOfWindow`, not a full-display fallback. Window identity is checked before/after capture, encoding runs off the main thread, buffers are closed, and cancelled delivery recycles bitmaps.
6. **Command replay, clock skew and ambiguous timeout.** Per-enable session IDs, monotonic time budgets, bounded receipt tracking, deadline checks at receipt/result, and no automatic retries prevent stale work from silently being replayed.
7. **Fake JPEG acceptance.** The old validator accepted marker-only bytes. The revised relay decodes the JPEG with explicit memory/resolution limits and validates image dimensions against the captured window.
8. **False successful compilation.** Upstream Simba can return exit zero after errors. Success now requires its full success-marker line; embedded success-like error text is rejected. Compiler execution is locally opt-in.
9. **Mutable staged scripts and reused desktop handles.** Full SHA-256 filenames are verified again before compilation; symlinks and changed contents fail. Fixed read-only probes re-discover and compare handle/PID/title, and avoid inventory calls while logged out.
10. **Misleading pairing UI and credential handling.** The UI observes actual enabled/connected/error state. Disconnect revokes local consent synchronously, token rejection disables the session, response size/time are bounded, redirects are disabled, and a saved token is not silently sent to a new endpoint.
11. **Inventory actions using stale scans.** The local dropper re-observes controls and occupancy instead of trusting a previous caller scan. It aborts on uncertain input/window changes. This is not an item-identity guarantee.
12. **Nonportable fixture paths.** Three tests now obtain their private screenshots through `WASP_SCREENSHOT_FIXTURES` or the documented resources directory. Missing data fails visibly. Pairing preferences also have explicit cloud/device-transfer backup exclusions.

## Remaining release gates

### Required empirical checks

- Supply the three original S23 fixtures and run the full Android suite with `--rerun-tasks`. Eleven failing tests currently remain unverified.
- Instrument on the user's Samsung S23: actual accessibility re-enable flow, screenshot dimensions, lock/unlock, notification shade, app switching, keyboard, overlay hit testing, local stop/disconnect during dispatch, reconnect and Wi-Fi loss.
- Validate real Windows Simba source discovery, compilation, process timeout and read-only probing against the user's installed library version.
- Configure the actual ChatGPT MCP tunnel and confirm a real `wasp_devices` and `wasp_observe` call. An SDK smoke test is not proof that the user's ChatGPT account/tunnel is connected.

### Explicit functional/security boundaries

- Remote Android 11–13/API 30–33 support and Samsung split-screen/Secure Folder selection are not implemented. The app rejects these remote cases. The existing base application still targets MinSDK 30.
- The legacy Swarm server on port 7777 still has unauthenticated mutation routes. It was not connected to the new MCP relay, and must be secured separately before integration or exposure.
- The mobile high-level skill executor requested in the broader project is not implemented. Current remote tools perform bounded observations, single taps and Pause; generated desktop scripts are staged/optionally compiled, not automatically run.
- The inventory detector cannot identify all protected/valuable items. Occupancy confidence is not item identity. Broad unattended destructive operations remain unsafe to assume correct.
- Pairing tokens are app-private preferences, not Keystore-encrypted storage. The shared relay token grants privileged access; this is not a multi-user authorization system.
- Android `dispatchGesture` is not an atomic window-bound transaction. Immediate pre-dispatch checks reduce risk but cannot eliminate every external focus race; completion must be followed by observation.
- The 36 lint warnings include dependency freshness, localization, drawing allocations, static field lifetime and idiomatic API suggestions. They were not globally suppressed to make the report green.
- No claim of human indistinguishability, anti-cheat evasion, Play Integrity guarantees or ban safety has been verified.

## Reproduce

```text
cd mcp-bridge
npm ci
npm test
npm audit --omit=dev --audit-level=high

# repository root, Java 17 and Android SDK 36 available
./gradlew :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2
./gradlew :app:testDebugUnitTest --no-daemon --max-workers=2 --rerun-tasks
```

Set `WASP_SCREENSHOT_FIXTURES` before the final command; the expected filenames are in the bridge README. On Windows use `gradlew.bat`.

## References inspected

- WaspLib agent contract: https://github.com/WaspScripts/WaspLib/blob/main/AGENTS.md
- Simba CLI success/error behavior: https://github.com/Villavu/Simba/blob/2591bfe12142b71d370d08f89bf08c5edc95aeaf/Source/script/simba.script_runner.pas and `Source/Simba.lpr` in the checked-out upstream source
- Official MCP SDK: https://github.com/modelcontextprotocol/typescript-sdk
- Android accessibility APIs: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Secure MCP Tunnel setup: https://developers.openai.com/api/docs/guides/secure-mcp-tunnels
