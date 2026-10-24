# Oracle VM deployment: WaspMobile OSRS MCP

**Live deployment:** [Oracle status and activation steps](ORACLE_DEPLOYMENT_STATUS.md). Review the isolated systemd units, operator helpers and Caddy host under [ops/](ops/).

This deployment is **only** for the Samsung S23 OSRS/WaspMobile project. It does not modify the separate 2009Scape world, media vault or Swarm.

## Architecture

Oracle Linux hosts one persistent process, `oracle-daemon.js`, supervised by `wasp-osrs-bridge.service`:

- Phone relay at `127.0.0.1:7788`. Only the three authenticated device POST routes are forwarded by the existing Caddy HTTPS server.
- Private MCP at `127.0.0.1:7790/mcp`. It is accessible only from the VM and requires **a separate** bearer key from the phone token. Neither endpoint listens directly on a public IP address.
- Local health check at `127.0.0.1:7790/healthz`.
- Persistent, AES-256-GCM encrypted MCP Events subscription storage under `/var/lib/wasp-osrs/events.enc`.
- 15 original MCP tools, plus MCP 2.0 Work/dot event discovery/subscription. MCP requests use the official SDK's per-request HTTP handler, with legacy stateless support for older clients.

The VM's HTTPS phone address is:

`https://waspmobile.129.146.39.132.sslip.io`

**Network distinction:** This is an **Internet-reachable HTTPS hostname protected by a long random bearer token**, not a tailnet-only connection. The public reverse proxy exposes only the three phone endpoints; the MCP tool endpoint itself stays strictly loopback-only. You may replace the hostname with a custom domain or a private Tailscale Serve HTTPS endpoint later without changing the underlying MCP tool logic.

The VM does not run Windows Simba or WaspLib, so desktop-specific WaspLib probes/compilation are intentionally disabled. To use desktop Simba later, create a separate bounded PC client or site agent without broadening the phone's trust or opening the MCP listener publicly. The Android phone still controls OSRS via Accessibility. The actual OSRS game APK is not modified.

## Mobile-only pairing

A one-time mobile setup page is hosted at the existing HTTPS phone hostname under a randomized /pair/ URL. Opening the page does not disclose the token; clicking Reveal performs a POST protected by an unpredictable form challenge tied to the one-time link (compatible with Samsung in-app browsers that modify Origin and cookies) and shows the phone server URL and credential once. It expires within an hour. The portal is isolated on loopback port 7789 under a separate service and can be renewed without changing the phone secret. Do not commit an active pairing URL or the token to GitHub.

## Service operation and verification

```bash
sudo systemctl status wasp-osrs-bridge.service --no-pager
sudo journalctl -u wasp-osrs-bridge.service --no-pager -n 30
curl -sS http://127.0.0.1:7790/healthz
curl -i https://waspmobile.129.146.39.132.sslip.io/device/poll
# GET returns 404: device endpoints intentionally accept authenticated POST only.
```

To view the phone connection settings privately, SSH to the VM and run `sudo wasp-osrs-pair` **in your own terminal**. It prints the phone pairing token locally; do not copy it into ChatGPT, GitHub, diagnostics, screenshots or logs. If you have a running version signed by another key, back up local app settings before reinstalling the debug-signed test APK.

Set the URL and matching secret in the WaspMobile Android MCP Phone Bridge card. Connect MCP, leave one official OSRS game window open and unlocked, and check for a real "Connected" state. First test an observation-only plan and Take Control/STOP AI. A phone that has never connected cannot appear under wasp_devices.

## ChatGPT custom MCP tunnel (account authorization needed)

The VM already has OpenAI's `tunnel-client` binary for **other** services. Do **not** reuse or reconfigure their tunnel IDs, credentials or systemd units.

Create a **new WaspMobile-specific Secure MCP Tunnel** in your [OpenAI Platform tunnel settings](https://platform.openai.com/settings/organization/tunnels), associate it with the ChatGPT workspace in which your OSRS Work chat/dot operates, and obtain its dedicated `tunnel_id` and restricted **Tunnels Read + Use** runtime API key. Keep that runtime key secret. The VM daemon continues to run whether or not the tunnel is activated.

On the VM, have an administrator configure a separate tunnel-client profile with this private MCP URL:

`http://127.0.0.1:7790/mcp`

The profile must send an Authorization header for MCP requests and discovery. Point its header value to the private file `/etc/wasp-osrs/mcp-auth-header` (owned by root, readable to the isolated service user only). Never print or commit that header's contents. The tunnel-client supports `mcp.extra-headers` and `mcp.discovery-extra-headers` options with `file:/...` secret references.

After the profile and credentials are configured, supervise its runtime independently, verify the tunnel client's `/readyz` and `doctor --profile ... --explain`, and connect the new tunnel in ChatGPT Plugins with Connection: Tunnel.

The **MCP Events** functionality that triggers a Work chat or OSRS dot requires protocol 2026-07-28, that plugin's permissions, an active subscription to `overlay.message.created` for the paired Phone ID, and a valid signed webhook callback. A plain ChatGPT Instant thread does not auto-receive events. See [MCP_EVENTS.md](MCP_EVENTS.md).

Do not claim ChatGPT live linkage merely because the bridge is running. Validate a real `wasp_devices` call and a signed `events/subscribe` response from ChatGPT before claiming successful setup.

## Service credentials, updates and rollback

- The three independent secrets (Android device token, private MCP HTTP key, events storage key) reside only under `/etc/wasp-osrs/` with restricted permissions.
- The running code is installed under `/opt/wasp-osrs/current/` and is read-only to the service user.
- Service state, encrypted subscription store, and bounded draft files live under `/var/lib/wasp-osrs/`.
- The systemd unit uses a separate unprivileged `wasposrs` account with writable state directory and no capabilities.
- No firewall ports are added; existing Caddy 443 is used. `ss -ltnp` should show ports 7788 and 7790 only on 127.0.0.1.
- For updates, stage/test a new source commit, install into a separate versioned release directory and atomically repoint `current`. Restart only `wasp-osrs-bridge.service` after validation.
- For rollback, restore the previous `/opt/wasp-osrs/current` target and restart only the WaspMobile service. The Caddy site can be removed separately after validation. **Never overwrite or stop** existing 2009Scape/StreamVault services as part of WaspMobile rollback.

## Outstanding release gates

These service-side tests do not establish full product readiness. The user's Samsung S23 has not completed its end-to-end keyboard, screenshot, tap, connection-loss and resume tests. The original 11 fixture-dependent Android unit failures still require the three private S23 screenshots. An unverified account-level ChatGPT Work/dot subscription or private tunnel cannot be declared active. OSRS gameplay automation can violate Jagex rules.
