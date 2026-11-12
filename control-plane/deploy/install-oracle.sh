#!/usr/bin/env bash
# Install the private controller only. Never change existing wasp-osrs-bridge.
set -euo pipefail
if [[ "$(id -u)" != "0" ]]; then
  echo "Run as root on the Oracle host." >&2
  exit 1
fi
BASE=/opt/wasp-mobile-control/current
[[ -f "$BASE/control-plane/server.js" ]] || { echo "Missing server.js in controller checkout" >&2; exit 1; }
command -v node >/dev/null
command -v openssl >/dev/null
if ! id -u waspmobile >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /sbin/nologin waspmobile
fi
install -d -m 0700 -o waspmobile -g waspmobile /var/lib/wasp-mobile-control
if [[ ! -e /etc/wasp-mobile-control.env ]]; then
  umask 077
  TOKEN="$(openssl rand -hex 32)"
  {
    printf 'CONTROL_ADMIN_TOKEN=%s\n' "$TOKEN"
    printf 'CONTROL_HOST=127.0.0.1\n'
    printf 'CONTROL_PORT=7792\n'
    printf 'CONTROL_DATA_DIR=/var/lib/wasp-mobile-control\n'
    printf 'CONTROL_MAX_CONNECTED_WORKERS=2\n'
  } > /etc/wasp-mobile-control.env
  chmod 0600 /etc/wasp-mobile-control.env
  unset TOKEN
fi
install -m 0644 "$BASE/control-plane/deploy/wasp-mobile-control.service" /etc/systemd/system/wasp-mobile-control.service
systemctl daemon-reload
systemctl enable --now wasp-mobile-control.service
curl --fail --silent --show-error --max-time 3 http://127.0.0.1:7792/healthz >/dev/null
echo "Wasp Mobile controller ready privately on localhost:7792."
echo "Existing wasp-osrs-bridge untouched; admin secret stored in /etc/wasp-mobile-control.env."
