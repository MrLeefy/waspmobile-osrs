# Use existing Oracle Admin MCP for WaspMobile OSRS

**October 24 gateway upgrade:** The existing Oracle Admin tunnel now supports WaspMobile MCP 2.0 Events. Legacy Oracle Admin tools remain available; event-capable modern sessions expose OSRS tools and harmless diagnostics, not privileged admin shell or file access. See [ORACLE_ADMIN_MCP2_UPGRADE.md](ORACLE_ADMIN_MCP2_UPGRADE.md). The ChatGPT Work/dot subscription must still be created by an authorized agent.


This is an **on-demand MCP tool route**. It needs no extra OpenAI Secure MCP Tunnel, no API key and no new ChatGPT plugin.

ChatGPT's installed Oracle Admin MCP tool -> Oracle shell command -> private WaspMobile MCP HTTP endpoint at `127.0.0.1:7790/mcp` -> one S23 game window.

An isolated wrapper is installed on Oracle at `/usr/local/sbin/wasp-osrs-mcp`. It reads the private bearer token internally from `/etc/wasp-osrs/bridge.env` without printing credentials. It opens no public port and does not modify the existing Oracle Admin MCP service.

## Commands available through the existing Oracle Admin execute tool

```bash
# List and check the online paired phone
/usr/local/sbin/wasp-osrs-mcp devices

# Read overlay inbox (replace PHONE_ID with the returned ID)
/usr/local/sbin/wasp-osrs-mcp wasp_inbox '{"deviceId":"PHONE_ID","afterSeq":0}'

# Respond to a specific overlay message
/usr/local/sbin/wasp-osrs-mcp wasp_reply '{"deviceId":"PHONE_ID","replyTo":"MESSAGE_ID","text":"Reply from ChatGPT"}'

# Observe the screen (sensitive JPEG goes to a local private file)
/usr/local/sbin/wasp-osrs-mcp wasp_observe '{"deviceId":"PHONE_ID"}'

# On user request, pause remote action
/usr/local/sbin/wasp-osrs-mcp wasp_pause '{"deviceId":"PHONE_ID"}'
```

The wrapper accepts all 15 existing `wasp_*` tool names with a strict JSON argument object. Unknown tool names fail. Gestures are subject to the same WaspMobile consent/one-window/fresh-frame rules as before. The adapter does not retry uncertain actions.

Screenshot images are saved to a restricted folder under `/var/lib/wasp-osrs/observations/` instead of printing base64 into logs. Old images are pruned during the next capture after five minutes.

## What this does not do

This does **not** make ordinary ChatGPT conversations respond automatically whenever the mobile overlay posts a message. Oracle Admin's existing Python MCP server supports protocol 2025-11-25, not the MCP 2026-07-28 Events required by ChatGPT Work chats/dots. It has no `events/subscribe` support.

For automatic Work/dot triggering, the actual ChatGPT plugin must advertise `overlay.message.created` and accept an event subscription. The existing WaspMobile MCP server supports it; Oracle Admin's present plugin does not. This requires either an approved and tested upgrade/gateway of Oracle Admin's MCP interface or a separate OSRS tunnel/plugin with the correct account authorization. Do not change the existing root-admin service without validating compatibility and protecting the other VM projects.

The new direct tool route is already live with no changes to other game/media projects or the running Oracle Admin tunnel.
