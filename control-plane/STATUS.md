# Wasp Mobile — current integration and release status

## Oracle controller: private service deployed

- Service: wasp-mobile-control.service
- Bind: 127.0.0.1:7792 (local only, deliberately no public URL yet)
- Git checkout: /opt/wasp-mobile-control/current, development branch PR #4
- Persistent state: /var/lib/wasp-mobile-control
- Administrator secret: /etc/wasp-mobile-control.env (root-only mode 0600)
- Runs as dedicated non-root waspmobile account with systemd resource limits
- Existing wasp-osrs-bridge.service on 7788 and 7790 remains untouched
- Existing Caddy HTTPS sites not modified

To check: sudo systemctl status wasp-mobile-control.service
Local readiness: curl -f http://127.0.0.1:7792/healthz

## Implemented on draft PR #4

- Mobile-first scalable account registry with + Add account
- Two connected workers by default; additional account registration remains available
- Unique one-time per-account worker secrets, rotation, offline profile removal
- Worker screenshots, telemetry, validated goals and remote commands
- Local Windows desktop launcher supervisor with only preapproved executable paths
- Remote START, PAUSE, RESUME, HOP and LOGOUT_STOP signals
- Bounded, opt-in autonomous woodcutting/mining session rotation
- Draft Simba Master Director adapter to read goals, honor bank/drop choice, level caps and selected world
- Worker bootstrap PowerShell and example private configuration templates

## Tested vs not tested

Node.js verification on Oracle Linux: 77 control-plane/bridge checks, 28 supervisor checks and 13 planner checks: 118 passing automated checks. These tests use simulated Simba screenshots, telemetry and process events. They are NOT proof that the official RuneLite client runs, that Simba scripts compile, or that gameplay has been performed successfully.

## Still required to finish running real game accounts

1. Choose a Windows PC or separately verified graphical Linux host which stays powered on and has an interactive desktop session. Desktop UI automation is not safely provided by a Windows Session 0 service.
2. Install/test Simba 2.0, WaspLib and the game client locally. Verify exact Simba script launching syntax; it was not confirmed by the source audit.
3. Create two named dashboard account profiles. Copy their one-time worker tokens privately to the PC.
4. Configure worker-profiles.json and worker-host.json by copying the examples and inserting actual paths, tokens, client launch parameters and account IDs. Do not put OSRS credentials or private files into GitHub.
5. Compile the changed master_account_director.simba and human_core.simba against the WaspLib version on that PC, then validate window targeting, target detection, banking, stop/restart and per-account game telemetry.
6. Run Start-WaspWorkers.ps1 in the logged-in desktop session. Windows must remain on and awake, even while the phone is off.
7. Enable autoLaunch only after verified exact launcher behavior; manual START must never report a game is logged in without fresh Simba telemetry.
8. Configure private phone access (VPN or authenticated HTTPS proxy). Oracle currently serves the dashboard only on localhost.
9. Validate one real account before two, then measure PC CPU/RAM for scaling. Full-scale registries need database and distributed scheduling rather than the present small-deployment JSON backend.
10. Optional LLM director: provider and API credentials still need to be selected. Current autonomous goal rotation is deterministic and bounded rather than LLM-run.

Game automation on OSRS is prohibited by Jagex's published rules. This project makes no ban safety claims.
