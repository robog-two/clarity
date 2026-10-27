#!/bin/sh
# Starts a throwaway browser container for the browser pilot's live test (LiveBrowserPilotTest),
# never the production one: its own name, a fresh tmpfs profile, no saved sessions.
#
#   computers/browser/pilot-e2e/start.sh [image]    prints the MCP URL and the site's state URL
#   computers/browser/pilot-e2e/stop.sh
#
# The test site (site.mjs) stands in for the egress proxy inside the container (`egress` points at
# 127.0.0.1), with a test CA that a policy file installs, so https://*.clarity.test works.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
image="${1:-ghcr.io/robog-two/clarity-browser:e2e}"
name="${PILOT_BROWSER_NAME:-clarity-browser-pilot-e2e}"
mcp_port="${PILOT_MCP_PORT:-18951}"
site_port="${PILOT_SITE_PORT:-18952}"
work="${PILOT_WORK:-$here/.work}"
mkdir -p "$work"

if [ ! -f "$work/site.pem" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=Clarity pilot test CA" \
    -keyout "$work/ca.key" -out "$work/ca.pem" >/dev/null 2>&1
  openssl req -newkey rsa:2048 -nodes -subj "/CN=*.clarity.test" -keyout "$work/site.key" -out "$work/site.csr" >/dev/null 2>&1
  printf 'subjectAltName=DNS:*.clarity.test,DNS:clarity.test\nbasicConstraints=CA:FALSE\n' >"$work/ext.cnf"
  openssl x509 -req -in "$work/site.csr" -CA "$work/ca.pem" -CAkey "$work/ca.key" -CAcreateserial -days 30 \
    -extfile "$work/ext.cnf" -out "$work/site.pem" >/dev/null 2>&1
  chmod 644 "$work"/*.pem "$work"/*.key
fi

# The image's policies plus the test CA.
sed 's|"DisableProfileImport": true|"DisableProfileImport": true,\n    "Certificates": { "Install": ["/srv/pilot/ca.pem"] }|' \
  "$here/../policies.json" >"$work/policies.json"

podman rm -f "$name" >/dev/null 2>&1 || true
podman run -d --name "$name" --read-only --cap-drop ALL --cap-add SYS_CHROOT --security-opt no-new-privileges \
  --tmpfs /tmp:rw,nosuid,size=512m --shm-size 512m --memory 1536m --pids-limit 1024 \
  --add-host egress:127.0.0.1 -e BROWSER_ALLOW_NO_AUTH=true \
  -v "$work:/srv/pilot:ro,z" -v "$here/site.mjs:/srv/site.mjs:ro,z" \
  -v "$work/policies.json:/opt/firefox/distribution/policies.json:ro,z" \
  -p "127.0.0.1:$mcp_port:8931" -p "127.0.0.1:$site_port:3128" "$image" >/dev/null

for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$mcp_port/healthz" || true)" = 200 ] && break
  sleep 1
done
podman exec -d "$name" node /srv/site.mjs >/dev/null
for _ in $(seq 1 20); do
  curl -s "http://127.0.0.1:$site_port/__state" >/dev/null 2>&1 && break
  sleep 0.5
done
echo "PILOT_MCP_URL=http://127.0.0.1:$mcp_port/mcp"
echo "PILOT_SITE_STATE=http://127.0.0.1:$site_port/__state"
