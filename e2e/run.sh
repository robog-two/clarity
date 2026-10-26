#!/usr/bin/env bash
# End-to-end test of the compose stack, from a user's point of view.
#
#   e2e/run.sh
#
# Brings up postgres, backend, egress, the example connector and (unless skipped) the computers
# (sandbox + Firefox) under their own compose project with throwaway secrets and no Anthropic key,
# then drives it like the app would (e2e/DeviceCli.java signs ES256 device JWTs):
#
#   /readyz, device enrollment (token mode), setup status ("model key missing"), connector manifest
#   + config + grant, a record ingested through the example connector (checked in Postgres), the
#   feed SSE stream delivering the "no API key" note, the backend logging the sandbox and browser
#   MCP tool listings at boot, and the egress proxy refusing LAN addresses from the browser's network.
#
# Everything is torn down at the end (also on failure or Ctrl-C). A real `clarity` stack on the same
# machine is not touched: the project name, volumes, networks, secrets (e2e_*) and port all differ.
#
# Needs: podman with a compose provider, curl, jq, a JDK (java) on PATH.
#
# Environment:
#   E2E_PROJECT        compose project name (default clarity-e2e)
#   E2E_PORT           loopback port for the backend (default 18080)
#   E2E_BUILD          how the backend and example images are built:
#                        gradle    (default) `./gradlew installDist` on the host, then a thin image
#                        container the real Containerfile builds it inside the container (slow, no host JDK build)
#                        none      use the existing ghcr.io/robog-two/clarity-{backend,connector-example}:e2e images
#   E2E_SKIP_BROWSER=1 leave the browser out (it needs about 1.5 GB); the sandbox is still tested
#   E2E_SKIP_COMPUTERS=1 leave sandbox and browser out
#   E2E_KEEP=1         do not tear down (debugging; clean up with `E2E_CLEANUP_ONLY=1 e2e/run.sh`)
#   E2E_GRADLE_ARGS    extra Gradle arguments (default: low-memory settings)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

PROJECT="${E2E_PROJECT:-clarity-e2e}"
PORT="${E2E_PORT:-18080}"
BUILD="${E2E_BUILD:-gradle}"
TAG="e2e"
BASE="http://127.0.0.1:$PORT"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/clarity-e2e.XXXXXX")"
DEVICE="$WORK/device"
SECRETS=(postgres_password anthropic_api_key connector_example_key connector_mail_key connector_dav_key sandbox_key browser_key)
COMPOSE_FILE="deploy/compose.e2e.generated.yaml"   # next to compose.yaml: relative paths in it keep working

if [ "${E2E_SKIP_COMPUTERS:-}" = 1 ]; then SKIP_SANDBOX=1; SKIP_BROWSER=1; else SKIP_SANDBOX=0; SKIP_BROWSER="${E2E_SKIP_BROWSER:-0}"; fi

compose() { podman compose -p "$PROJECT" -f "$COMPOSE_FILE" --env-file "$WORK/env" "$@"; }
cli() { java "$root/e2e/DeviceCli.java" "$@"; }
log() { printf '\n== %s\n' "$*"; }
pass=0
ok() { pass=$((pass + 1)); printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*" >&2; show_logs; exit 1; }

show_logs() {
    echo "---- container states" >&2
    podman ps -a --filter "label=io.podman.compose.project=$PROJECT" --format '{{.Names}} {{.Status}}' >&2 || true
    for c in backend egress connector-example; do
        echo "---- $c (last 40 lines)" >&2
        podman logs --tail 40 "${PROJECT}_${c}_1" >&2 2>&1 || true
    done
}

teardown() {
    local code=$?
    set +e
    if [ "${E2E_KEEP:-}" = 1 ] && [ -z "${E2E_CLEANUP_ONLY:-}" ]; then
        echo "E2E_KEEP=1: leaving project $PROJECT running (state in $WORK)"; exit $code
    fi
    log "tearing down $PROJECT"
    compose --profile example --profile computers down -v --timeout 5 >/dev/null 2>&1
    podman ps -a --filter "label=io.podman.compose.project=$PROJECT" -q | xargs -r podman rm -f >/dev/null 2>&1
    podman volume ls -q --filter "label=io.podman.compose.project=$PROJECT" | xargs -r podman volume rm -f >/dev/null 2>&1
    podman network ls -q --filter "label=io.podman.compose.project=$PROJECT" | xargs -r podman network rm -f >/dev/null 2>&1
    for s in "${SECRETS[@]}"; do podman secret rm "e2e_$s" >/dev/null 2>&1; done
    rm -rf "$WORK" "$root/$COMPOSE_FILE"
    exit $code
}
trap teardown EXIT INT TERM

[ -n "${E2E_CLEANUP_ONLY:-}" ] && exit 0

for tool in podman curl jq java; do command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }; done

# ---- 1. throwaway secrets and settings ------------------------------------------------------------
log "secrets and settings"
rand() { head -c 36 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'; }
for s in "${SECRETS[@]}"; do
    podman secret rm "e2e_$s" >/dev/null 2>&1 || true
    if [ "$s" = anthropic_api_key ]; then printf '\n' | podman secret create "e2e_$s" - >/dev/null   # a lone newline = no key
    else rand | podman secret create "e2e_$s" - >/dev/null; fi
done
mcp=""
[ "$SKIP_SANDBOX" = 1 ] || mcp="sandbox=http://sandbox:8940/mcp;keyFile=/run/secrets/sandbox_key"
[ "$SKIP_BROWSER" = 1 ] || mcp="${mcp:+$mcp,}browser=http://browser:8931/mcp;keyFile=/run/secrets/browser_key"
cat >"$WORK/env" <<EOF
CLARITY_TAG=$TAG
E2E_PORT=$PORT
CLARITY_ENROLLMENT_MODE=token
CLARITY_CONNECTORS_AUTO_GRANT=false
CLARITY_EXAMPLE_SYNC_INTERVAL=5s
CLARITY_MCP_SERVERS=$mcp
EOF

# The compose file the stack is started from: deploy/compose.yaml with (1) every external secret
# renamed e2e_<name> and mounted under its original name, so a real stack's Podman secrets on this
# machine are never touched, and (2) the backend's published port moved to loopback E2E_PORT. A
# plain text transformation (no YAML tooling needed); the checks make sure it really applied.
names="$(IFS='|'; echo "${SECRETS[*]}")"
sed -E \
    -e "s/^( +)- ($names)( *(#.*)?)\$/\\1- {source: e2e_\\2, target: \\2}/" \
    -e "s/^  ($names):\$/  e2e_\\1:/" \
    -e 's/"\$\{CLARITY_BIND:-127\.0\.0\.1\}:8080:8080"/"127.0.0.1:${E2E_PORT}:8080"/' \
    deploy/compose.yaml >"$COMPOSE_FILE"
[ "$(grep -c 'target: ' "$COMPOSE_FILE")" -ge 8 ] || { echo "secret rewrite did not apply to deploy/compose.yaml" >&2; exit 2; }
[ "$(grep -c '^  e2e_[a-z_]*:$' "$COMPOSE_FILE")" = "${#SECRETS[@]}" ] || { echo "top-level secret rewrite did not apply" >&2; exit 2; }
grep -q '127.0.0.1:${E2E_PORT}:8080' "$COMPOSE_FILE" || { echo "port rewrite did not apply" >&2; exit 2; }

# ---- 2. images --------------------------------------------------------------------------------------
img() { echo "ghcr.io/robog-two/clarity-$1:$TAG"; }

build_prebuilt() { # <gradle path> <app name> <module dir> <image name>
    local stage="$WORK/stage-$4"
    mkdir -p "$stage/data"
    cp -r "$3/build/install/$2" "$stage/app"
    mv "$stage/app/bin/$2" "$stage/app/bin/app"
    rm -f "$stage/app/bin/$2.bat"
    cp e2e/prebuilt.Containerfile "$stage/Containerfile"
    podman build -q -f "$stage/Containerfile" -t "$(img "$4")" "$stage" >/dev/null
}

build_if_missing() { # <image name> <containerfile>
    if podman image exists "$(img "$1")"; then return; fi
    local local_tag="ghcr.io/robog-two/clarity-$1:local"
    if podman image exists "$local_tag"; then podman tag "$local_tag" "$(img "$1")"; return; fi
    podman build -q -f "$2" -t "$(img "$1")" . >/dev/null
}

log "images ($BUILD)"
case "$BUILD" in
    gradle)
        ./gradlew :backend:installDist :connectors:example:installDist ${E2E_GRADLE_ARGS:--Dorg.gradle.jvmargs=-Xmx1536m --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process} --no-daemon --console=plain -q   # no daemon: its memory is free again before the stack starts
        build_prebuilt backend clarity-backend backend backend
        build_prebuilt connectors:example clarity-connector-example connectors/example connector-example ;;
    container)
        podman build -q -f deploy/container/jvm-app.Containerfile --build-arg MODULE=backend --build-arg APP_NAME=clarity-backend --build-arg MODULE_DIR=backend -t "$(img backend)" . >/dev/null
        podman build -q -f deploy/container/jvm-app.Containerfile --build-arg MODULE=connectors:example --build-arg APP_NAME=clarity-connector-example --build-arg MODULE_DIR=connectors/example -t "$(img connector-example)" . >/dev/null ;;
    none) ;;
    *) echo "unknown E2E_BUILD=$BUILD" >&2; exit 2 ;;
esac
build_if_missing egress deploy/container/egress.Containerfile
[ "$SKIP_SANDBOX" = 1 ] || build_if_missing sandbox deploy/container/sandbox.Containerfile
[ "$SKIP_BROWSER" = 1 ] || build_if_missing browser deploy/container/browser.Containerfile

# ---- 3. up -------------------------------------------------------------------------------------------
log "starting $PROJECT"
services=(postgres backend egress connector-example)
[ "$SKIP_SANDBOX" = 1 ] || services+=(sandbox)
[ "$SKIP_BROWSER" = 1 ] || services+=(browser)
compose --profile example --profile computers up -d "${services[@]}"

log "waiting for the backend"
for _ in $(seq 1 90); do curl -fs "$BASE/readyz" >/dev/null 2>&1 && break; sleep 2; done
curl -fs "$BASE/readyz" >/dev/null || fail "/readyz never became ready"
ok "/readyz"
[ "$(curl -s "$BASE/healthz" -o /dev/null -w '%{http_code}')" = 200 ] || fail "/healthz"
ok "/healthz"

# ---- 4. enroll a device and use the app API ----------------------------------------------------------
log "device enrollment"
token="$(podman exec "${PROJECT}_backend_1" cat /data/enrollment-token | tr -d '\r\n')"
[ -n "$token" ] || fail "no enrollment token in the backend's data volume"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/enroll" -H 'Content-Type: application/json' -d '{"publicKey":"AAAA","deviceName":"x"}')" != 200 ] \
    || fail "enrolling without the token should be refused"
ok "enrolling without the token is refused"
cli enroll "$DEVICE" "$BASE" "$token" | jq -e '.deviceId' >/dev/null || fail "enroll"
ok "enrolled device $(cat "$DEVICE/device-id")"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/setup")" = 401 ] || fail "the app API should refuse unauthenticated requests"
ok "the app API refuses unauthenticated requests"

cli request "$DEVICE" GET "$BASE/api/v1/setup" | jq -e '.modelKey == "missing"' >/dev/null || fail "setup status should say the model key is missing"
ok "setup status: model key missing"

# ---- 5. connector: manifest, config, grant, ingest ---------------------------------------------------
log "example connector"
manifest_ok() { cli request "$DEVICE" GET "$BASE/api/v1/connectors" | jq -e '.[] | select(.id == "example") | .manifest.kind == "example"' >/dev/null 2>&1; }
for _ in $(seq 1 60); do manifest_ok && break; sleep 2; done
manifest_ok || fail "the example connector never announced its manifest"
ok "connector manifest announced (kind example)"
cli request "$DEVICE" PUT "$BASE/api/v1/connectors/example/config" '{"emitSample":true}' >/dev/null || fail "set connector config"
cli request "$DEVICE" PUT "$BASE/api/v1/connectors/example/grants" '{"message":"granted"}' >/dev/null || fail "grant message"
cli request "$DEVICE" GET "$BASE/api/v1/connectors" | jq -e '.[] | select(.id == "example") | .grants.message == "granted"' >/dev/null || fail "grant not recorded"
ok "config set and message noun granted"
sql() { podman exec "${PROJECT}_postgres_1" psql -U clarity -d clarity -tAc "$1"; }
count() { sql "select count(*) from message where subject = 'Hello from the example connector'" 2>/dev/null || echo 0; }
for _ in $(seq 1 60); do [ "$(count)" -ge 1 ] && break; sleep 2; done
[ "$(count)" -ge 1 ] || fail "the sample message never arrived in Postgres"
ok "sample record ingested through the connector"

# ---- 6. feed SSE ---------------------------------------------------------------------------------------
log "feed"
events="$(cli sse "$DEVICE" "$BASE/api/v1/events" 6 || true)"
grep -qi 'no Anthropic API key' <<<"$events" || { echo "$events" >&2; fail "the feed did not deliver the 'no API key' note"; }
ok "SSE connected and delivered the 'no API key' note"

# ---- 7. computers ----------------------------------------------------------------------------------------
if [ "$SKIP_SANDBOX" = 1 ]; then
    echo "skip sandbox and browser checks (E2E_SKIP_COMPUTERS)"
else
    log "computers"
    listed() { podman logs "${PROJECT}_backend_1" 2>&1 | grep -E "MCP server '$1': [0-9]+ tools" >/dev/null; }
    for _ in $(seq 1 60); do listed sandbox && break; sleep 2; done
    listed sandbox || fail "the backend never logged the sandbox's MCP tool listing"
    ok "backend listed the sandbox MCP tools at boot"
    if [ "$SKIP_BROWSER" = 1 ]; then
        echo "skip browser checks (E2E_SKIP_BROWSER)"
    else
        for _ in $(seq 1 60); do listed browser && break; sleep 2; done
        listed browser || fail "the backend never logged the browser's MCP tool listing"
        ok "backend listed the browser MCP tools at boot"
        via_egress() { # <request line target>: the status the proxy answers on the browser's network
            podman exec "${PROJECT}_browser_1" node -e "
const net = require('net');
const s = net.connect(3128, 'egress', () => s.write(process.argv[1] + ' HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n'));
let d = '';
s.on('data', (c) => (d += c));
s.on('close', () => { console.log(d.split('\r\n')[0]); process.exit(0); });
setTimeout(() => { console.log('timeout'); process.exit(1); }, 15000);" "$1"
        }
        for target in "GET http://192.168.1.1/" "GET http://10.0.0.1/" "GET http://169.254.169.254/" "CONNECT 172.16.0.1:443"; do
            answer="$(via_egress "$target" || true)"
            grep -q ' 403 ' <<<"$answer" || fail "egress should refuse '$target' from the browser, got: $answer"
        done
        ok "egress refuses LAN, link-local and metadata addresses from the browser's network"
    fi
fi

log "all $pass checks passed"
