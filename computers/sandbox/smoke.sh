#!/bin/sh
# Smoke test for the sandbox image: starts it hardened (read-only rootfs, no capabilities),
# then lists tools and runs a command over MCP with the bearer key.
#   computers/sandbox/smoke.sh [image]
set -eu
image="${1:-ghcr.io/robog-two/clarity-sandbox:local}"
name="clarity-sandbox-smoke-$$"
port="${SMOKE_PORT:-18940}"
key="smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
tmp="$(mktemp -d)"
keyfile="$tmp/sandbox_key"
printf '%s' "$key" >"$keyfile"; chmod 644 "$keyfile"
cleanup() { podman rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT

podman run -d --name "$name" --read-only --cap-drop ALL --security-opt no-new-privileges \
  --tmpfs /tmp:rw,nosuid,size=64m --memory 1g --cpus 1 --pids-limit 256 \
  -v "$keyfile:/run/secrets/sandbox_key:ro,z" -p "127.0.0.1:$port:8940" "$image" >/dev/null

url="http://127.0.0.1:$port/mcp"
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/healthz" || true)" = 200 ] && break
  sleep 1
done
rpc() { # rpc <json>
  curl -sS -X POST "$url" -H "Authorization: Bearer $key" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' -d "$1"
}
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$url" -d '{}')" = 401 ] || { echo "FAIL: unauthenticated request not rejected"; exit 1; }
rpc '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | grep -q '"name":"shell"' || { echo "FAIL: tools/list"; exit 1; }
cmd='for t in rg jq git python3 pandoc pdftotext sqlite3; do command -v $t >/dev/null || echo MISSING $t; done; echo hello > a.txt; id -u; touch /usr/x 2>&1 | head -1'
out="$(rpc "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"shell\",\"arguments\":{\"command\":\"$cmd\"}}}")"
echo "$out"
echo "$out" | grep -q 'MISSING' && { echo "FAIL: tool missing"; exit 1; }
echo "$out" | grep -q '10001' || { echo "FAIL: not running as 10001"; exit 1; }
echo "$out" | grep -qi 'read-only' || { echo "FAIL: rootfs writable"; exit 1; }
echo "sandbox smoke OK"
