# Oracle VM deployment receipt — October 24, 2026

**Latest deployment:** The existing Oracle Admin tunnel now exposes MCP2 WaspMobile Events with a modern-session privilege guard, and the previously paired Samsung S23 is pinned for offline event subscription. See [ORACLE_ADMIN_MCP2_UPGRADE.md](ORACLE_ADMIN_MCP2_UPGRADE.md). ChatGPT Work/dot event subscription is still not activated.


**October 24 gateway upgrade:** The existing Oracle Admin tunnel now supports WaspMobile MCP 2.0 Events. Legacy Oracle Admin tools remain available; event-capable modern sessions expose OSRS tools and harmless diagnostics, not privileged admin shell or file access. See [ORACLE_ADMIN_MCP2_UPGRADE.md](ORACLE_ADMIN_MCP2_UPGRADE.md). The ChatGPT Work/dot subscription must still be created by an authorized agent.


**OSRS/WaspMobile only.** Deployed source commit: `57b3a1649789653f610df305da4576ac9c8cc887` from the draft OSRS branch.

| Component | Observed state |
|---|---|
| Oracle systemd `wasp-osrs-bridge.service` | **active/running**, enabled on boot, running as unprivileged `wasposrs` |
| Persistent Android HTTPS relay | **ready**, locally bound to `127.0.0.1:7788` |
| Private MCP HTTP service | **ready**, bound to `127.0.0.1:7790/mcp` only |
| Public Android relay hostname | `https://waspmobile.129.146.39.132.sslip.io` |
| TLS | **Valid Let's Encrypt certificate**, confirmed against trusted CA |
| Public API authentication | POST without phone pairing token: **401**; with token but malformed device identity: **400** |
| Public MCP endpoint | **404**, intentionally not publicly routed |
| Private MCP authorization | Without separate MCP local bearer key: **401** |
| Legacy MCP tools | **15 tools available**, verified against the running service |
| MCP 2.0 Events | `overlay.message.created` discovered through the running service |
| Local test suite | **114/114 Node/MCP tests pass**; production npm audit reported 0 known vulnerabilities |
| Registered physical phones | **0**, awaiting the user's Samsung S23 pairing |
| Dedicated ChatGPT tunnel service | **Prepared but disabled**, awaiting a new OSRS-specific Platform tunnel ID and restricted runtime key |
| Actual ChatGPT Work/dot subscription | **Not created or verified** |
| Physical Samsung S23 keyboard/input tests | **Not completed** |
| Other services | Existing Caddy, 2009Scape web/world, media mount verified active; scape website returned 200; media vault returned 302 |

## Security and separation

- Three independent secrets (phone pairing, localhost MCP, events encryption) were generated on Oracle and **never printed in this chat**.
- Secrets reside in root-managed, group-restricted files under `/etc/wasp-osrs`; the Node service only writes under `/var/lib/wasp-osrs`.
- The VM's system Node binary has a file capability for privileged ports. To avoid granting this unneeded capability to the game bridge, the isolated systemd service executes a capability-free copy at `/opt/wasp-osrs/bin/node`. Refresh that binary whenever you update the system Node runtime.
- The existing Caddy site configuration was backed up before adding a single isolated host block; no existing project's reverse proxy settings were changed.
- No Swarm or 2009Scape source was modified. No existing Oracle Admin tunnel ID was reused.
- **Important:** The HTTPS phone hostname is internet-accessible but requires a strong token. It is not Tailscale-only. The MCP tool API remains inaccessible from that public hostname.

## Phone setup without SSH

A one-use HTTPS pairing page has been deployed to let the user copy the phone URL and credential directly from mobile. The live short-lived link is shared only in the ChatGPT conversation, never saved in repository files. The initial GET contains no persistent phone credential; one form POST with an unpredictable challenge reveals it once. The dedicated OpenAI tunnel and ChatGPT Work/dot subscription still require account-level authorization and are not active.

## Existing Oracle Admin MCP access now available

The existing Oracle Admin connection can directly invoke the isolated WaspMobile tool adapter using the command `/usr/local/sbin/wasp-osrs-mcp devices` and other approved WaspMobile MCP methods. It uses the private localhost MCP API and reads the local token without revealing it. All 15 original tools are reachable on demand. The Oracle Admin Python server is an older MCP version and cannot advertise MCP Events, so messages queued in the phone still do not automatically start a ChatGPT turn. See [ORACLE_ADMIN_PROXY.md](ORACLE_ADMIN_PROXY.md).

## Finish account-level activation

1. SSH to the Oracle VM in a terminal and run `sudo wasp-osrs-pair`. The command intentionally refuses noninteractive execution to prevent leaking the pairing token. Enter the HTTPS URL and token in WaspMobile > MCP Phone Bridge on your S23.
2. Create a **dedicated** WaspMobile tunnel in OpenAI [Platform Tunnels](https://platform.openai.com/settings/organization/tunnels), associated with the ChatGPT Work/dot workspace. Obtain a restricted runtime key with Tunnels Read + Use.
3. In a **private interactive SSH terminal**, run `sudo wasp-osrs-link-tunnel`. It securely prompts for the tunnel ID and runtime key, saves them separately from the phone token, runs the tunnel client's doctor checks and enables the dedicated systemd tunnel only after they pass.
4. From ChatGPT Plugins, add a private custom MCP server using **Connection: Tunnel**, select the new WaspMobile tunnel, and validate a real `wasp_devices` tool call.
5. In an OSRS-only Work chat or dot, subscribe to `overlay.message.created` filtered to the paired Phone ID. Send a harmless message from the floating overlay without leaving OSRS; confirm webhook receipt and the returned `wasp_reply` message.

The published APK is still a **debug-signed test build**:
https://github.com/MrLeefy/waspmobile-osrs/releases/tag/v2.1.2-s23-mcp-events-test.1

Do not uninstall an existing differently signed WaspMobile without preserving important settings. Jagex may prohibit automated gameplay. See [ORACLE_DEPLOYMENT.md](ORACLE_DEPLOYMENT.md) for operation and rollback.
