# Wasp Mobile Remote Control Plane — development preview

This additive controller coexists with the Android overlay, Simba 2.0 / WaspLib desktop scripts, and the legacy Swarm dashboard.

## Implemented

- Mobile-first dashboard with a **+ Add account** control. No fixed limit on registered account profiles.
- Two active connected workers by default, controlled by the CONTROL_MAX_CONNECTED_WORKERS environment setting.
- Unique one-time 64-character worker tokens generated per account. Only SHA-256 hashes are persisted.
- Operator-only APIs for profile creation/removal, token rotation, saved goals, AI goal proposals with manual approval, command dispatch and real screenshots.
- Outbound-only worker bridge using existing Simba Swarm telemetry JSON/PNG and command files. Command delivery is acknowledged separately from execution.
- Honest states: registered, worker connected, fresh game-reported login. No simulated launches or fake PIDs.
- Integration test suite that simulates a Simba worker, without running a game.

## Always-on controller setup

Requires Node.js 18+. Set CONTROL_ADMIN_TOKEN to a securely generated random secret with at least 32 characters. Set CONTROL_HOST=127.0.0.1, CONTROL_PORT=7788, CONTROL_DATA_DIR to a persistent private directory, and CONTROL_MAX_CONNECTED_WORKERS=2 initially. From this directory run: node server.js.

Use a dedicated non-root service account and systemd or another supervisor for persistence across reboot. Keep the secret in a restricted 0600 environment file. Expose the dashboard only through a trusted private HTTPS route, such as Tailscale or an authenticated reverse proxy. Never expose port 7788 directly to the public internet, and never store game account passwords in this controller.

## Connect the PC bridge

1. From the mobile dashboard, unlock with the administrator token and choose **+ Add account**.
2. Give the account a lowercase ID (example: account-1) and a label. Copy the generated worker token immediately. It is shown only once.
3. On the Windows PC running Simba and RuneLite, use Node.js 18+ and set CONTROL_URL to the controller's private HTTPS address. Also set CONTROL_ACCOUNT_ID and WASP_BOT_ID to the matching account ID, CONTROL_WORKER_TOKEN to the secret, and SWARM_DIR to the Simba Swarm folder (often C:\Simba\Swarm).
4. Run: node control-plane/worker.js. Run one bridge instance per account.
5. Separately start the actual client and Simba script on the PC. The bridge receives telemetry and screenshots from the existing Human.SyncSwarm integration.

The bridge reads Swarm/telemetry/ACCOUNT.json and ACCOUNT.png; writes received commands to Swarm/commands/ACCOUNT.cmd; and writes approved goals to Swarm/goals/ACCOUNT.json. In other words, it does NOT launch clients or switch the script activity on its own. Existing WaspLib scripts need an additional tested adapter to consume goal changes. The dashboard does not invent a running state.

A goal consists of skill (woodcutting or mining), mode (bank or power_drop), durationMinutes (5 to 180), and optional targetLevel (1 to 99).

A trusted backend can submit an AI proposal to POST /api/accounts/ACCOUNT/proposal; the dashboard operator must approve it before it becomes a saved goal. There is currently NO automatic LLM provider, and you should never pass administrator credentials or OSRS passwords into an LLM prompt.

## Scaling plan

Profiles can be registered without a fixed two-account ceiling, but the present JSON-file persistence and full-list HTTP responses are intended for a modest initial deployment. For larger deployments, migrate to a database, paginate account lists, add a central durable job queue, bounded worker leases, resource-aware host scheduling, auditing, process exit confirmation, and an on-host launcher that explicitly reports real client state.

Oracle is currently ARM64 and carries other workloads. A Windows PC is the initial executor; Oracle can coordinate the remote dashboard and persistent plans. Headed Simba/RuneLite and headless execution on Oracle are NOT verified here.

## Validation

Run from repo root:
- node --check control-plane/server.js
- node --check control-plane/worker.js
- node control-plane/test.js

Tests exercise six registered profiles, two active workers, authentication, token rotation, offline account removal, saved goals, approved proposals, commands, and a real bridge subprocess with simulated local Simba files.

Game automation is prohibited by Jagex's published RuneScape rules. Multi-account ownership does not confer permission to automate play, and no antiban behavior guarantees against suspension. Use test environments where automation is permitted. Reference: https://legal.jagex.com/docs/rules/rules-of-runescape
