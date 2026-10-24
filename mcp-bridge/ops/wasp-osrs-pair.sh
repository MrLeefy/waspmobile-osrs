#!/usr/bin/env bash
set -euo pipefail
if [[ "$EUID" -ne 0 ]]; then
  echo "Run this on the Oracle VM as: sudo wasp-osrs-pair" >&2
  exit 1
fi
if [[ ! -t 1 ]]; then
  echo "Refusing to print pairing secrets to logs or a noninteractive MCP session." >&2
  exit 2
fi
envfile=/etc/wasp-osrs/bridge.env
if [[ ! -r "$envfile" ]]; then
  echo "Bridge credentials unavailable." >&2
  exit 1
fi
token="$(sed -n 's/^WASP_DEVICE_TOKEN=//p' "$envfile")"
if [[ ! "$token" =~ ^[A-Za-z0-9_-]{32,128}$ ]]; then
  echo "Stored pairing token invalid." >&2
  exit 1
fi
echo ""
echo "WaspMobile OSRS -- single-phone pairing (PRIVATE)"
echo "------------------------------------------------"
echo "HTTPS bridge URL: https://waspmobile.129.146.39.132.sslip.io"
printf 'Phone pairing token: %s\n' "$token"
echo ""
echo "Enter these only in Android WaspMobile > MCP Phone Bridge."
echo "Do not paste the token into ChatGPT, GitHub, Discord, or public logs."
echo "The MCP tools use a different local-only token."
