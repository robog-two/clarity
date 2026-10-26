#!/bin/sh
# Smoke test for the egress proxy, against real containers (needs podman and internet access).
# Mirrors the compose topology with throwaway networks: two internal client networks (browser,
# backend) and one normal network, with the proxy on all three. Proves:
#   - one public HTTPS request through the browser listener works
#   - private/loopback/link-local/ULA/mapped destinations, a name resolving to a private address,
#     the host gateway names and non-443 CONNECT are refused (403)
#   - the backend listener allows only api.anthropic.com (+ CLARITY_BACKEND_EGRESS_ALLOW)
#   - clients on the internal networks have no direct route out
#   - the same proxy listeners are not reachable across networks
#
#   computers/egress/smoke.sh [egress-image]
set -eu
image="${1:-ghcr.io/robog-two/clarity-egress:local}"
curl_image="${SMOKE_CURL_IMAGE:-docker.io/curlimages/curl:8.11.1}"
id="$$"
egress="clarity-egress-smoke-$id"
nets="clarity-egsmoke-browser-$id clarity-egsmoke-backend-$id clarity-egsmoke-out-$id"
net_browser="clarity-egsmoke-browser-$id"; net_backend="clarity-egsmoke-backend-$id"; net_out="clarity-egsmoke-out-$id"

cleanup() {
  podman rm -f "$egress" >/dev/null 2>&1 || true
  for n in $nets; do podman network rm -f "$n" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; podman logs "$egress" 2>&1 | tail -20 >&2 || true; exit 1; }

podman network create --internal "$net_browser" >/dev/null
podman network create --internal "$net_backend" >/dev/null
podman network create "$net_out" >/dev/null

# private.test resolves (via /etc/hosts, which Squid reads) to an RFC 1918 address.
podman run -d --name "$egress" --user 10001:10001 --read-only --cap-drop ALL --security-opt no-new-privileges \
  --tmpfs /tmp:rw,nosuid,size=64m --memory 128m \
  --add-host private.test:10.1.2.3 --add-host private6.test:fd00::5 \
  -e CLARITY_BACKEND_EGRESS_ALLOW=example.com \
  --network "$net_browser:alias=egress-browser,alias=egress" --network "$net_backend:alias=egress-backend" --network "$net_out" \
  "$image" >/dev/null
for _ in $(seq 1 30); do
  podman exec "$egress" nc -z 127.0.0.1 3130 >/dev/null 2>&1 && break
  sleep 1
done
podman exec "$egress" nc -z 127.0.0.1 3130 || fail "proxy did not start"

# A client container on one internal network. Prints the HTTP status curl saw, or the proxy's reply
# to CONNECT when the tunnel was refused (curl reports 000 for the status then), or 000 for no connection.
client() { # client <network> <curl args...>
  net="$1"; shift
  out="$(podman run --rm --network "$net" --read-only --cap-drop ALL "$curl_image" \
    -s -o /dev/null -w '%{http_code} %{http_connect}' --max-time 20 "$@" 2>/dev/null || true)"
  code="${out%% *}"; connect="${out##* }"
  if [ "${code:-000}" = 000 ] && [ "${connect:-000}" != 000 ]; then echo "$connect"; else echo "${code:-000}"; fi
}
expect() { # expect <description> <wanted-status> <actual>
  if [ "$3" = "$2" ]; then echo "ok   $1 ($3)"; else fail "$1: wanted $2, got $3"; fi
}
via_browser() { client "$net_browser" -x http://egress-browser:3128 "$@"; }
via_backend() { client "$net_backend" -x http://egress-backend:3128 "$@"; }

# --- browser listener: public yes, private no ---------------------------------------------------
expect "browser: public HTTPS (example.com)" 200 "$(via_browser https://example.com/)"
for url in http://10.0.0.1/ http://192.168.1.1/ http://172.16.0.1/ http://169.254.169.254/ \
           http://127.0.0.1/ http://localhost/ 'http://[::1]/' 'http://[fd00::1]/' 'http://[fe80::1]/' \
           'http://[::ffff:10.0.0.1]/' http://100.64.0.1/ http://0.0.0.0/ http://2130706433/ \
           http://0x7f.0.0.1/ http://host.containers.internal/ http://host.docker.internal/ \
           http://private.test/ http://private6.test/ https://10.0.0.1/ https://private.test/; do
  expect "browser refuses $url" 403 "$(via_browser "$url")"
done
expect "browser: CONNECT to non-443 port refused" 403 "$(via_browser https://example.com:8443/)"
expect "browser: plain HTTP to non-80 port refused" 403 "$(via_browser http://example.com:8080/)"

# --- backend listener: only the allowlist -------------------------------------------------------
expect "backend: allowlisted host (example.com via CLARITY_BACKEND_EGRESS_ALLOW)" 200 "$(via_backend https://example.com/)"
code="$(via_backend https://api.anthropic.com/)"
case "$code" in 000|403|407|5*) fail "backend: api.anthropic.com should be allowed, got $code" ;; *) echo "ok   backend: api.anthropic.com reachable ($code)" ;; esac
expect "backend refuses other public host (www.iana.org)" 403 "$(via_backend https://www.iana.org/)"
expect "backend refuses a private address" 403 "$(via_backend http://10.0.0.1/)"

# --- no direct route out, no borrowing the other network's listener ------------------------------
expect "browser network: no direct route to a public IP" 000 "$(client "$net_browser" --noproxy '*' https://1.1.1.1/)"
expect "backend network: no direct route to a public IP" 000 "$(client "$net_backend" --noproxy '*' https://1.1.1.1/)"
expect "backend network: no direct route to a public name" 000 "$(client "$net_backend" --noproxy '*' https://example.com/)"
# From the backend network, the browser-network listener must not be reachable.
browser_ip="$(podman exec "$egress" getent ahostsv4 egress-browser | awk 'NR==1{print $1}')"
expect "backend network cannot reach the browser listener" 000 "$(client "$net_backend" -x "http://$browser_ip:3128" https://example.com/)"
expect "browser network cannot use the backend listener name" 000 "$(client "$net_browser" -x http://egress-backend:3128 https://example.com/)"

# --- optional: the real browser, on the browser network only, through the proxy -------------------
# SMOKE_BROWSER_IMAGE=ghcr.io/robog-two/clarity-browser:local computers/egress/smoke.sh
if [ -n "${SMOKE_BROWSER_IMAGE:-}" ]; then
  here="$(cd "$(dirname "$0")" && pwd)"
  browser="clarity-egsmoke-firefox-$id"
  trap 'podman rm -f "$browser" >/dev/null 2>&1 || true; cleanup' EXIT
  podman run -d --name "$browser" --read-only --cap-drop ALL --cap-add SYS_CHROOT --security-opt no-new-privileges \
    --tmpfs /tmp:rw,nosuid,size=512m --shm-size 512m --memory 1536m --pids-limit 1024 \
    -e BROWSER_ALLOW_NO_AUTH=true --network "$net_browser" "$SMOKE_BROWSER_IMAGE" >/dev/null
  for _ in $(seq 1 60); do
    podman exec "$browser" node -e "fetch('http://127.0.0.1:8931/healthz').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))" 2>/dev/null && break
    sleep 1
  done
  podman exec -i "$browser" node - <"$here/browser-check.mjs" || { podman logs "$browser" 2>&1 | tail -20 >&2; fail "browser through the proxy"; }
fi

# --- the proxy logged the refusals ---------------------------------------------------------------
podman logs "$egress" 2>&1 | grep -q 'TCP_DENIED' || fail "no denials in the access log"
echo "egress smoke OK"
