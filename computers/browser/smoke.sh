#!/bin/sh
# Smoke test for the browser image. Starts it hardened (read-only rootfs, no capabilities, tmpfs
# profile), then over MCP (streamable HTTP + bearer key) proves:
#   1. unauthenticated requests are rejected
#   2. tools/list works
#   3. Firefox starts headless and a snapshot of a local file:// page returns element uids
#   4. uBlock Origin and Privacy Badger are installed and enabled (read from about:support)
#
#   computers/browser/smoke.sh [image]       (needs podman and curl)
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
image="${1:-ghcr.io/robog-two/clarity-browser:local}"
name="clarity-browser-smoke-$$"
port="${SMOKE_PORT:-18931}"
key="smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
tmp="$(mktemp -d)"
printf '%s' "$key" >"$tmp/browser_key"; chmod 644 "$tmp/browser_key"
cleanup() {
  if [ "${SMOKE_KEEP:-}" != 1 ]; then podman rm -f "$name" >/dev/null 2>&1 || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; podman logs "$name" 2>&1 | tail -30 >&2 || true; exit 1; }

podman run -d --name "$name" --read-only --cap-drop ALL --cap-add SYS_CHROOT --security-opt no-new-privileges \
  --tmpfs /tmp:rw,nosuid,size=512m --shm-size 512m --memory 1536m --pids-limit 1024 \
  -v "$tmp/browser_key:/run/secrets/browser_key:ro,z" \
  -v "$here/testpage.html:/srv/test.html:ro,z" \
  -p "127.0.0.1:$port:8931" "$image" >/dev/null

base="http://127.0.0.1:$port"
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/healthz" || true)" = 200 ] && break
  sleep 1
done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$base/healthz" || true)" = 200 ] || fail "bridge did not become healthy"

[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$base/mcp" -H 'Content-Type: application/json' -d '{}')" = 401 ] \
  || fail "unauthenticated request was not rejected"

hdr() { printf 'Authorization: Bearer %s' "$key"; }
post() { # post <session-id|-> <json>: prints the JSON-RPC payload of the (SSE or JSON) reply
  if [ "$1" = - ]; then sid=""; else sid="$1"; fi
  curl -sS --max-time 120 -X POST "$base/mcp" -H "$(hdr)" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${sid:+-H "Mcp-Session-Id: $sid"} -d "$2" \
    | sed -n 's/^data: //p; /^{/p'
}

sid="$(curl -sS -D - -o /dev/null -X POST "$base/mcp" -H "$(hdr)" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  | tr -d '\r' | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p')"
[ -n "$sid" ] || fail "no MCP session id"
post "$sid" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null

tools="$(post "$sid" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
for t in navigate_page take_snapshot list_pages; do
  echo "$tools" | grep -q "\"name\":\"$t\"" || fail "tools/list lacks $t"
done
echo "tools/list ok"

call() { # call <id> <tool> <arguments-json>
  post "$sid" "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"
}

call 3 navigate_page '{"url":"file:///srv/test.html"}' >/dev/null
snap="$(call 4 take_snapshot '{}')"
echo "$snap" | grep -q 'Press me' || fail "snapshot lacks page content: $snap"
echo "$snap" | grep -Eq 'uid|[0-9]+_[0-9]+' || fail "snapshot has no element uids: $snap"
echo "snapshot ok"

# Add-ons: Firefox records what it loaded in the profile's extensions.json.
addons="$(podman exec "$name" node -e "
const j = JSON.parse(require('fs').readFileSync('/tmp/profile/firefox_devtools_mcp_profile/extensions.json', 'utf8'));
for (const a of j.addons) if (a.active && a.location === 'app-profile') console.log(a.id, a.version);
")"
echo "$addons" | grep -q '^uBlock0@raymondhill.net ' || fail "uBlock Origin is not active"
echo "$addons" | grep -q '^jid1-MnnxcxisBPnSXQ@jetpack ' || fail "Privacy Badger is not active"
echo "add-ons active: $(echo "$addons" | tr '\n' ';')"

# No stray first-run tabs: the agent should see exactly the page it opened.
pages="$(call 5 list_pages '{}')"
echo "$pages" | grep -q '1 pages' || fail "unexpected tabs: $pages"
echo "single tab ok"

# --- session restore: open tabs survive the container being recreated (as every deploy does) -----
podman rm -f "$name" >/dev/null
volume="clarity-browser-smoke-profile-$$"
podman volume create "$volume" >/dev/null
trap 'cleanup; podman volume rm -f "$volume" >/dev/null 2>&1 || true' EXIT
start() { # start a hardened container on the kept profile volume and open an MCP session (sets $sid)
  podman run -d --name "$name" --read-only --cap-drop ALL --cap-add SYS_CHROOT --security-opt no-new-privileges \
    --tmpfs /tmp:rw,nosuid,size=512m --shm-size 512m --memory 1536m --pids-limit 1024 \
    -e BROWSER_PROFILE_DIR=/profile -v "$volume:/profile" \
    -v "$tmp/browser_key:/run/secrets/browser_key:ro,z" \
    -v "$here/testpage.html:/srv/test.html:ro,z" \
    -p "127.0.0.1:$port:8931" "$image" >/dev/null
  for _ in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/healthz" || true)" = 200 ] && break
    sleep 1
  done
  sid="$(curl -sS -D - -o /dev/null -X POST "$base/mcp" -H "$(hdr)" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
    | tr -d '\r' | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p')"
  [ -n "$sid" ] || fail "no MCP session id"
  post "$sid" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}
start
call 10 navigate_page '{"url":"file:///srv/test.html?first"}' >/dev/null
call 11 new_page '{"url":"file:///srv/test.html?second"}' >/dev/null
for round in 1 2; do   # twice: the spare empty tab must not pile up
  sleep 20   # Firefox writes its session every 15 s (browser.sessionstore.interval)
  podman stop -t 10 "$name" >/dev/null   # what a deploy does
  podman rm -f "$name" >/dev/null
  start
  pages=""
  for i in $(seq 1 20); do   # the restore finishes shortly after Firefox starts
    pages="$(call $((20 + i)) list_pages '{}')"
    echo "$pages" | grep -q 'test.html?first' && echo "$pages" | grep -q 'test.html?second' && break
    sleep 1
  done
  echo "$pages" | grep -q 'test.html?first' || fail "first tab not restored (round $round): $pages"
  echo "$pages" | grep -q 'test.html?second' || fail "second tab not restored (round $round): $pages"
  [ "$(echo "$pages" | head -1 | grep -o 'about:blank' | wc -l)" -le 1 ] || fail "more than one empty tab (round $round): $pages"
  call 50 select_page '{"url":"test.html?second"}' >/dev/null
  snap="$(call 51 take_snapshot '{}')"
  echo "$snap" | grep -q 'Press me' || fail "restored tab is not usable (round $round): $snap"
  echo "session restore ok (round $round)"
done
echo "browser smoke OK"
