# One-phone OSRS pilot

This branch is deliberately limited to **one Samsung S23, one foreground OSRS game window, and one active automation controller at a time**. This does **not** attest to or permanently lock an in-game RuneScape account name. The user can manually switch accounts; the bridge cannot identify an account from color pixels.

## What is enabled

- Local Android WaspMobile Accessibility overlay and Agent Desk (review and approve bounded plans).
- One authenticated, outbound phone-to-PC connection via private HTTPS Tailscale Serve.
- Fifteen MCP tools for observations, one-off taps, local approvals, messages, source inspection and optional PC compilation/probing.
- The first phone is pinned by the bridge for its process lifetime. A second phone is rejected even after the first goes offline. A bridge restart clears the binding unless an explicit allowed device ID is configured.
- Only a single foreground OSRS application window is eligible for remote screenshot capture or gestures. User/agent/local-script input conflicts cause work to pause or reject.
- The legacy `DUAL_SPLIT_SCREEN` enum remains for saved-preference compatibility, but split-screen selection is hidden, persisted split settings normalize to Woodcut, and the service refuses a legacy split selection.

## Not part of this pilot

No Swarm integration, multi-account rotation, Secure Folder dual-window control, bot-farm scheduling, automatic connection to an existing dot conversation, unattended gameplay beyond the approved bounded plan language, or full WaspLib-for-Android implementation. The separate `swarm-server` source is **untouched**.

## Setup (Windows PC)

From your local repository clone:

```powershell
cd mcp-bridge
npm ci
npm test

$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:WASP_DEVICE_TOKEN = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()

# Optional: once the phone has been paired, copy the Phone ID from its
# MCP Phone Bridge panel and pin this PC process across restarts.
# $env:WASP_ALLOWED_DEVICE_ID = 'phone-...'

node bridge.js
```

The phone ID is not a password, but the pairing token **is**; never post the token in GitHub, chat, or screenshots.

Use Tailscale Serve on the Windows PC to expose only the loopback relay as private HTTPS, then enter the displayed HTTPS `*.ts.net` origin and the private token in WaspMobile. The relay does not open a public MCP HTTP endpoint. Connect ChatGPT separately through a supported MCP tunnel pointing to this stdio process. Do not start a second relay on the same port while the tunnel owns it.

On the S23, open OSRS in one normal application window. Tap **AI** to open Agent Desk, test the observation-only plan, and confirm STOP AI and Take Control before approving a plan with taps. No generated plan runs without local approval.

## Tests and release requirements

- `node --test mcp-bridge/single-phone.test.js` covers second-device rejection, offline cleanup, duplicate live sessions, disconnect, expired leases, optional persistent phone pinning, and denied second-device input.
- Kotlin `SingleCharacterPolicyTest` covers hidden split selection, preference normalization, and mode cycling; the previous mode-transition regression is updated.
- The full Android screenshot suite still requires three private, user-owned S23 screenshots. It must not be called passing until those fixtures are supplied.
- A debug APK build is not a physical S23 test or proof of signed-update compatibility. Installing an APK signed with a different key may require uninstalling an old app, deleting its saved settings. Do not uninstall a working app without backing up needed settings.
- Screenshot capture and gesture completion do not establish gameplay outcomes, item identity, account safety or compliance with Jagex rules.

This is an **OSRS-only** project, not the separate 2009Scape project.


## Executed verification on this preview

- **MCP/Node:** 86/86 passed, including 8 tests for single-phone binding, session locking and rejecting a second device.
- **Android:** Debug APK assembled, package integrity and debug signature verified; version code 4, 2.1.1-single-device-preview.
- **Android lint:** 0 errors and 44 warnings.
- **Android JVM tests:** 108/119 passed. The 11 failures are all tests needing the three unavailable private S23 screenshots; the five new single-character policy tests passed.
- **Still unverified:** Installing on the real S23, Windows Simba, live ChatGPT MCP connection and actual gameplay accuracy. This is a test build only.

Full test evidence, cert digest and APK SHA-256 are in [one-phone-results.json](one-phone-results.json).
