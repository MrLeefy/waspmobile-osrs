#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -ne 0 ]]; then
  echo "Run locally as: sudo wasp-osrs-link-tunnel" >&2
  exit 1
fi
if [[ ! -t 0 || ! -t 1 ]]; then
  echo "Interactive terminal required. Refusing to collect OpenAI credentials in logs or an MCP session." >&2
  exit 2
fi
if [[ -e /etc/wasp-osrs/control-plane-key || -e /etc/wasp-osrs/tunnel-profiles/osrs-mobile.yaml ]]; then
  echo "A WaspMobile tunnel profile/credential already exists. Review it instead of overwriting." >&2
  exit 3
fi
echo "Create a NEW dedicated OSRS tunnel in https://platform.openai.com/settings/organization/tunnels"
echo "Associate the tunnel with the correct ChatGPT Work/dot workspace. Never reuse the Oracle Admin MCP tunnel."
read -r -p "New OSRS tunnel ID (tunnel_...): " tunnel_id
if [[ ! "$tunnel_id" =~ ^tunnel_[A-Za-z0-9_-]{16,80}$ ]]; then
  echo "Invalid tunnel ID format. No changes made." >&2
  exit 4
fi
read -r -s -p "Restricted OpenAI runtime API key (input hidden): " runtime_key
echo
if [[ ! "$runtime_key" =~ ^sk-[A-Za-z0-9_-]{20,}$ ]]; then
  echo "Expected a restricted sk-... runtime API key. No changes made." >&2
  exit 5
fi
umask 077
profiledir=/etc/wasp-osrs/tunnel-profiles
install -d -m 0750 -o root -g wasposrs "$profiledir"
if ! /usr/local/bin/tunnel-client init \
  --sample sample_mcp_remote_no_auth \
  --profile osrs-mobile \
  --profile-dir "$profiledir" \
  --tunnel-id "$tunnel_id" \
  --mcp-server-url http://127.0.0.1:7790/mcp \
  --control-plane-api-key-ref file:/etc/wasp-osrs/control-plane-key \
  --health-listen-addr 127.0.0.1:7795
then
  echo "Tunnel profile creation failed. The runtime key was not persisted." >&2
  exit 6
fi
profile="$profiledir/osrs-mobile.yaml"
chown root:wasposrs "$profile"
chmod 0640 "$profile"
tmp="$(mktemp /etc/wasp-osrs/.control-plane-key.XXXXXXXX)"
trap 'rm -f "$tmp"; unset runtime_key' EXIT
printf '%s\n' "$runtime_key" > "$tmp"
chown root:wasposrs "$tmp"
chmod 0640 "$tmp"
mv "$tmp" /etc/wasp-osrs/control-plane-key
unset runtime_key
systemctl daemon-reload
echo "Verifying OpenAI control-plane permissions and the local MCP service..."
if ! runuser -u wasposrs -- /usr/local/bin/tunnel-client doctor \
  --profile osrs-mobile \
  --profile-dir "$profiledir" \
  --mcp.extra-headers "Authorization: file:/etc/wasp-osrs/mcp-auth-header" \
  --mcp.discovery-extra-headers "Authorization: file:/etc/wasp-osrs/mcp-auth-header" \
  --explain
then
  echo "Tunnel profile saved, but doctor did not pass. The tunnel service remains disabled." >&2
  echo "Check Platform organization/workspace association and Tunnels Read + Use permissions." >&2
  exit 7
fi
systemctl enable --now wasp-osrs-tunnel.service
sleep 3
if ! systemctl is-active --quiet wasp-osrs-tunnel.service; then
  echo "Tunnel runtime not yet healthy; review: sudo journalctl -u wasp-osrs-tunnel -n 40" >&2
  exit 8
fi
echo "Dedicated OSRS tunnel is running. Now connect its custom MCP plugin in ChatGPT."
echo "In an OSRS-only Work chat/dot, subscribe to overlay.message.created for your paired Phone ID."
echo "Never post runtime credentials into ChatGPT or GitHub."
