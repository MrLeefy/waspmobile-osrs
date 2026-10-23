# WaspMobile MCP ↔ Android Bridge (experimental)

This feature branch adds a real **opt-in MCP-to-phone control path**. The official OSRS APK remains unchanged.

## Features

- MCP over stdio: `wasp_devices`, `wasp_observe`, `wasp_last_frame`, `wasp_tap`, `wasp_pause`.
- Local Simba/WaspLib APIs: `wasp_source_find`, `wasp_source_read`, `wasp_stage_script`, `wasp_compile_script`.
- Phone: opt-in outbound HTTPS polling; screenshots and bounded taps are performed by the existing accessibility engine.
- PC: Node 20+ relay listens only on `127.0.0.1:7788` and requires a 32+-character bearer token.
- No generated Pascal script is executed automatically. Staging, compilation and gameplay execution are different activities.

## Set up on Windows PC (PowerShell)

From a local checkout of this repository (Node.js 20+, Simba 2.0):

```powershell
$env:WASP_DEVICE_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:WASP_LIB_ROOT = 'C:\Simba\Includes\WaspLib'
$env:WASP_SIMBA_EXE = 'C:\Simba\Simba.exe'
$env:WASP_DRAFT_ROOT = 'C:\Simba\Scripts\ai-drafts'
$env:WASP_BRIDGE_PORT = '7788'
node mcp-bridge\bridge.js
```

WASP_LIB_ROOT must match the actual **installed** WaspLib source directory. The path shown is a configurable default, not a verified fact about your PC. Never commit or paste your token into a public issue or chat.

For a private phone connection, run Tailscale Serve **on the PC**:

```powershell
tailscale serve --bg 7788
tailscale serve status
```

The displayed HTTPS `*.ts.net` URL points to this local relay. Tailscale Serve makes it available to devices in the same tailnet. Do **not** use public Funnel, direct port forwarding, or the legacy unauthenticated Swarm `:7777` endpoints.

## Set up on the phone

1. Build and install the Android APK from this feature branch, then enable its Accessibility service through Android settings.
2. Open the **MCP Phone Bridge** card in WaspMobile.
3. Enter the HTTPS Tailscale Serve URL (not an MCP `/mcp` path) and the same private token.
4. Tap **Connect MCP Remote Control**. Remote access is not auto-enabled after an Accessibility service restart.
5. Bring OSRS foreground; switching away and back can refresh the window-state event.
6. Pause any local loop before remote taps. Use **Disconnect** or close the overlay to stop remote control.

The token is stored in app-private preferences and backup is disabled for this build. This is not encrypted-at-rest Keystore storage; improve it before broad distribution.

## Connect ChatGPT

ChatGPT needs an MCP connection configured separately. The relay HTTP port is **not** an MCP endpoint. This bridge exposes MCP over **stdio** for a Secure MCP Tunnel.

Configure the tunnel's MCP command as `node <repo>\mcp-bridge\bridge.js` with the same environment variables. When tunnel-client launches it, don't also start a second instance of the Node script on the same port. Install the custom MCP server under ChatGPT Plugins, subject to account/workspace eligibility.

Once connected, sample tasks are:

- “List paired WaspMobile devices.”
- “Pause local automation, observe the phone's current OSRS screen, and describe it.”
- “Tap normalized x=0.42, y=0.51, then observe a new frame to verify.”
- “Search installed WaspLib for inventory APIs, create an AI draft, and compile it.”

LLM tool calls have network and model latency. Fast reaction and frequent deterministic actions remain local to the phone or Simba. There is intentionally **no multi-click script runner** exposed by MCP yet.

## Tests / validation

```powershell
node --test mcp-bridge\bridge.test.js
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

The project's existing `RealScreenshotVisionTest.kt` still references user screenshots at local Windows paths under `C:\Users\baseb\.gemini\...`. Those fixtures need to be copied with consent into test resources for CI portability.

**Unverified:** actual Samsung S23 API 34–36 behavior, app build, installed Simba compilation, and ChatGPT tunnel pairing. Tests were authored but have not run on those environments from this session.

## Trust boundaries / known limitations

- Device must opt in locally. There is no inbound port on Android.
- Only one in-flight command per phone with 14-second expiry; failures are **not replayed**.
- Screenshot observation is sent to the paired PC and may be returned to ChatGPT after an MCP call.
- Remote actions require most recently observed non-overlay accessibility package to match `com.jagex.oldscape.android`. This is an initial guard, **not reliable window attestation**. Improve it before split-screen or multitasking automation.
- Touch dispatch completion does not confirm the game action succeeded; follow with a new screenshot.
- Script drafts are content-addressed, but compilation is not a sandbox. Do not execute generated Pascal blindly.
- The separate existing Swarm HTTP server on port 7777 has unauthenticated endpoints. This branch does not expose them and does not yet fix them.
- Inventory occupancy cannot establish item identity. Remote drops and item movement need stronger recognition and allowlists.
- OSRS may prohibit automation; human-like randomized input is not a guarantee against detection.

## Next development milestones

- Replace package-name hint with foreground window identity and geometry versioning.
- Add tri-state inventory and UI recognition, explicit item protection, regression fixture corpus.
- Secure the legacy Swarm server separately; then add one-controller-per-game-client scheduling.
- Verify MCP with the official Inspector and test Kotlin APK on S23 before merging.
