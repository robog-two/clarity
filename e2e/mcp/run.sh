#!/usr/bin/env bash
# End-to-end check of MCP servers: the real backend with the real mcp-host, from the app's side.
#
#   e2e/mcp/run.sh
#
# Under its own compose project (never the `clarity` stack; its own secrets, volumes, networks and
# port), built from this checkout: postgres, egress, mcp-host and the backend as deploy/compose.yaml
# wires them (e2e/mcp/compose_gen.py), plus a scripted fake OpenRouter (e2e/mcp/fake_openrouter.py),
# so no model or third-party API is called (the egress check reaches example.com and www.iana.org
# through the proxy only to see the proxy's verdict). It then acts like the app:
#
#   1. enrolls a device, configures mcp-host through the connector config route with the tests' fake
#      server (kind local; one read tool, one write tool) and a sealed token; GET /api/v1/mcp/servers
#      shows it with its tools, default modes auto/ask, and its memory collection;
#   2. PUTs a tool's mode, then {} to reset it;
#   3. sends the lead a message: it delegates with servers=["Fake"]; the helper's read call runs and
#      reaches it quoted as untrusted; its write call is held (GET /api/v1/held), approved through
#      the held decision route, and runs exactly once on the server;
#   4. the server's proxy login reaches its listed host and not another;
#   5. prints the memory of the backend and of mcp-host idle and with the server running.
#
# Needs podman + podman-compose, curl, jq, python3 with PyYAML, a JDK. Environment:
#   E2E_PROJECT (clarity-e2emcp), E2E_PORT (18180), E2E_KEEP=1 (leave it running),
#   E2E_BUILD=none (reuse the localhost/clarity-*:e2emcp images), E2E_CLEANUP_ONLY=1.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
PROJECT="${E2E_PROJECT:-clarity-e2emcp}"
PORT="${E2E_PORT:-18180}"
TAG=e2emcp
PREFIX=e2emcp_
BASE="http://127.0.0.1:$PORT"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/clarity-e2emcp.XXXXXX")"
chmod 755 "$WORK"
STATE="$WORK/model"
mkdir -p "$STATE"
chmod 777 "$STATE"   # the fake model (uid 10001 in its container) writes the requests it saw here
DEVICE="$WORK/device"
COMPOSE_FILE="$WORK/compose.yaml"
SERVER_ID=e2efake01
LABEL=Fake
TOKEN_PLAIN="e2e-token-$(head -c 9 /dev/urandom | base64 | tr -d '/+=')"

if [ -z "${PODMAN_COMPOSE_PROVIDER:-}" ] && command -v podman-compose >/dev/null; then
    export PODMAN_COMPOSE_PROVIDER="$(command -v podman-compose)"
fi
compose() { podman compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"; }
cli() { java "$root/e2e/DeviceCli.java" "$@"; }
api() { cli request "$DEVICE" "$@"; }
c() { echo "${PROJECT}_$1_1"; }
log() { printf '\n== %s\n' "$*"; }
pass=0
ok() { pass=$((pass + 1)); printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*" >&2; show_logs; exit 1; }
show_logs() {
    for s in backend mcp-host fake-model; do
        echo "---- $s (last 40 lines)" >&2
        podman logs --tail 40 "$(c "$s")" >&2 2>&1 || true
    done
}
SECRETS=""
teardown() {
    local code=$?
    set +e
    if [ "${E2E_KEEP:-}" = 1 ] && [ -z "${E2E_CLEANUP_ONLY:-}" ]; then echo "E2E_KEEP=1: $PROJECT left running ($WORK)"; exit $code; fi
    log "tearing down $PROJECT"
    [ -f "$COMPOSE_FILE" ] && compose down -v --timeout 5 >/dev/null 2>&1
    podman ps -a --filter "label=io.podman.compose.project=$PROJECT" -q | xargs -r podman rm -f >/dev/null 2>&1
    podman volume ls -q --filter "label=io.podman.compose.project=$PROJECT" | xargs -r podman volume rm -f >/dev/null 2>&1
    podman network ls -q --filter "label=io.podman.compose.project=$PROJECT" | xargs -r podman network rm -f >/dev/null 2>&1
    podman secret ls --format '{{.Name}}' | grep "^$PREFIX" | xargs -r podman secret rm >/dev/null 2>&1
    rm -rf "$WORK"
    exit $code
}
trap teardown EXIT INT TERM
[ -n "${E2E_CLEANUP_ONLY:-}" ] && exit 0

# ---- compose file, secrets, images ----------------------------------------------------------------
log "compose file and secrets"
SECRETS="$(python3 -I e2e/mcp/compose_gen.py "$root" "$COMPOSE_FILE" "$PORT" "$TAG" "$PREFIX" "$STATE")"
rand() { head -c 36 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'; }
for s in $SECRETS; do
    podman secret rm "$PREFIX$s" >/dev/null 2>&1 || true
    rand | podman secret create "$PREFIX$s" - >/dev/null
done
ok "secrets: $SECRETS"

if [ "${E2E_BUILD:-}" != none ]; then
    log "images"
    # one Gradle at a time on a shared machine (E2E_GRADLE_LOCK names a lock file; unset: no lock)
    ${E2E_GRADLE_LOCK:+flock "$E2E_GRADLE_LOCK"} ./gradlew :backend:installDist -Dorg.gradle.jvmargs=-Xmx1536m --max-workers=2 \
        -Pkotlin.compiler.execution.strategy=in-process --no-daemon --console=plain -q
    stage="$WORK/stage"
    mkdir -p "$stage/data"
    cp -r backend/build/install/clarity-backend "$stage/app"
    mv "$stage/app/bin/clarity-backend" "$stage/app/bin/app"
    rm -f "$stage/app/bin/clarity-backend.bat"
    podman build -q -f e2e/prebuilt.Containerfile -t "localhost/clarity-backend:$TAG" "$stage" >/dev/null
    podman build -q -f deploy/container/egress.Containerfile -t "localhost/clarity-egress:$TAG" . >/dev/null
    podman build -q -f deploy/container/mcphost.Containerfile -t "localhost/clarity-mcphost:$TAG" . >/dev/null
    ok "backend, egress and mcp-host images built from this checkout"
fi

# ---- up ---------------------------------------------------------------------------------------------
log "starting $PROJECT"
compose up -d postgres egress fake-model backend mcp-host >/dev/null 2>&1 || fail "compose up"
for _ in $(seq 1 90); do curl -fs "$BASE/readyz" >/dev/null 2>&1 && break; sleep 2; done
curl -fs "$BASE/readyz" >/dev/null || fail "backend never ready"
ok "backend ready"
token="$(podman exec "$(c backend)" cat /data/enrollment-token | tr -d '\r\n')"
cli enroll "$DEVICE" "$BASE" "$token" | jq -e '.deviceId' >/dev/null || fail "enroll"
ok "device enrolled"

# ---- 1. configure mcp-host the way the app does ----------------------------------------------------
log "1. mcp-host configured through the connector config route"
manifest() { api GET "$BASE/api/v1/connectors" 2>/dev/null | jq -c '.[] | select(.id == "mcphost") | .manifest // empty'; }
for _ in $(seq 1 60); do [ -n "$(manifest)" ] && break; sleep 2; done
m="$(manifest)"
[ "$(jq -r .kind <<<"$m")" = mcp-host ] || fail "mcp-host never sent its manifest"
sealing_key="$(jq -r .sealingKey <<<"$m")"
ok "manifest: kind mcp-host, $(jq -r '.configSchema.properties.servers["x-presets"] | map(.title) | join(", ")' <<<"$m") presets"
sealed="$(printf '%s' "$TOKEN_PLAIN" | java e2e/mcp/Seal.java "$sealing_key" mcphost "servers/$SERVER_ID/secret")"
config="$(jq -n --argjson secret "$sealed" --arg id "$SERVER_ID" --arg label "$LABEL" '{servers: [{
    id: $id, label: $label, description: "A fake server with one read and one write", enabled: true, kind: "local",
    command: "/opt/mcp-local/fake_mcp.py", env: ["FAKE_TOOLS=pair"], secretEnv: "FAKE_TOKEN", secret: $secret,
    allowHosts: ["example.com"]}]}')"
api PUT "$BASE/api/v1/connectors/mcphost/config" "$config" >/dev/null || fail "config PUT"
ok "config saved (token sealed for servers/$SERVER_ID/secret)"

servers() { api GET "$BASE/api/v1/mcp/servers" 2>/dev/null || true; }
ready() { servers | jq -e --arg id "$SERVER_ID" '.servers[] | select(.id == $id) | (.state == "idle" or .state == "running") and (.tools | length == 2) and .collection != null' >/dev/null 2>&1; }
for _ in $(seq 1 90); do ready && break; sleep 2; done
ready || { servers >&2; fail "the server never showed as listed with its collection"; }
s="$(servers | jq -c --arg id "$SERVER_ID" '.servers[] | select(.id == $id)')"
echo "     $(jq -c '{label, state, source, collection, tools: [.tools[] | {name, readOnly, mode, overridden}]}' <<<"$s")"
[ "$(jq -r '.tools[] | select(.name == "lookup") | .mode' <<<"$s")" = auto ] || fail "lookup should default to auto"
[ "$(jq -r '.tools[] | select(.name == "create_note") | .mode' <<<"$s")" = ask ] || fail "create_note should default to ask"
ok "GET /api/v1/mcp/servers: $LABEL with lookup=auto, create_note=ask, collection \"$(jq -r .collection <<<"$s")\""

# ---- 2. tool modes -----------------------------------------------------------------------------------
log "2. tool modes"
r="$(api PUT "$BASE/api/v1/mcp/servers/$SERVER_ID/tools/lookup" '{"mode":"ask"}')" || fail "PUT mode"
jq -e '.mode == "ask" and .overridden == true' <<<"$r" >/dev/null || fail "PUT {mode: ask}: $r"
ok "PUT lookup {mode: ask} -> ask, overridden"
r="$(api PUT "$BASE/api/v1/mcp/servers/$SERVER_ID/tools/lookup" '{}')" || fail "PUT {}"
jq -e '.mode == "auto" and .overridden == false' <<<"$r" >/dev/null || fail "PUT {}: $r"
ok "PUT lookup {} -> back to auto, not overridden"
api GET "$BASE/api/v1/mcp/servers" | jq -e --arg id "$SERVER_ID" '.servers[] | select(.id == $id) | .tools[] | select(.name == "lookup") | .mode == "auto"' >/dev/null \
    || fail "the reset did not stick"
ok "the listing agrees"

# ---- 3. a helper's read and held write --------------------------------------------------------------
log "3. the lead delegates; the helper reads, then a held write is approved"
api POST "$BASE/api/v1/input" '{"clientId":"e2e-mcp-1","text":"E2E-MCP: what is due this week on Fake? Save a note there."}' >/dev/null || fail "input"
held_item() { api GET "$BASE/api/v1/held?status=held" 2>/dev/null | jq -c --arg s "mcp:$SERVER_ID" '[.[] | select(.action.server == $s and .action.tool == "create_note")][0] // empty'; }
for _ in $(seq 1 90); do [ -n "$(held_item)" ] && break; sleep 2; done
h="$(held_item)"
[ -n "$h" ] || fail "no held create_note call appeared"
ls "$STATE" | grep -q -- '-lead.json' || fail "the lead was never asked"
lead_delegate="$(grep -l '"delegate"' "$STATE"/*-lead.json | head -1)"
[ -n "$lead_delegate" ] || fail "no lead request"
ok "the lead delegated with servers=[\"$LABEL\"] (helper requests: $(ls "$STATE" | grep -c -- '-helper.json'))"
read_result="$(for f in "$STATE"/*-helper.json; do jq -r '.messages[] | select(.role == "tool") | .content' "$f"; done | grep -m1 'lookup result' || true)"
[ -n "$read_result" ] || fail "the read tool's result never reached the helper"
case "$read_result" in "<untrusted source=\"mcp:$LABEL\">"*"</untrusted>") ok "the read ran and reached the helper quoted as untrusted: ${read_result:0:90}…" ;;
    *) fail "the read result is not quoted as untrusted: $read_result" ;; esac
token_hash="$(printf '%s' "$TOKEN_PLAIN" | sha256sum | cut -c1-12)"
echo "     $(jq -c '{id, status, summary: .action.summary, tool: .action.tool, rationale: .verdict.rationale}' <<<"$h")"
ok "GET /api/v1/held shows the write as held: $(jq -r .action.summary <<<"$h")"
mem_running="$(podman stats --no-stream --format '{{.MemUsage}}' "$(c mcp-host)")"

uid="$(podman exec "$(c mcp-host)" stat -c %u "/data/servers/$SERVER_ID")"
notes() { podman exec --user "$uid" "$(c mcp-host)" sh -c "cat /data/servers/$SERVER_ID/notes.log 2>/dev/null | wc -l" | tr -d ' '; }
[ "$(notes)" = 0 ] || fail "the write ran before approval"
ok "nothing written before approval"
hid="$(jq -r .id <<<"$h")"
api POST "$BASE/api/v1/held/$hid/decision" '{"type":"approve"}' >/dev/null || fail "approve"
for _ in $(seq 1 60); do [ "$(notes)" -ge 1 ] && break; sleep 1; done
[ "$(notes)" = 1 ] || fail "the approved write did not run (notes: $(notes))"
approved_msg=""
for _ in $(seq 1 30); do
    approved_msg="$(for f in "$STATE"/*-helper.json; do jq -r '.messages[] | select(.role == "tool") | .content' "$f"; done | grep -m1 'The person approved it' || true)"
    [ -n "$approved_msg" ] && break
    sleep 1
done
[ -n "$approved_msg" ] || fail "the helper never got the approved call's result"
ok "approved: the write ran and its result reached the helper (${approved_msg:0:80}…)"
sleep 10
[ "$(notes)" = 1 ] || fail "the write ran more than once ($(notes))"
st="$(api GET "$BASE/api/v1/held/$hid" | jq -r .status)"
[ "$st" = executed ] || fail "held status is $st, not executed"
ok "it ran exactly once; the held item is executed"
case "$read_result" in *"token $token_hash"*) ;; *) fail "the server did not see the token sealed in the app: $read_result" ;; esac
ok "the sealed token reached the server's own environment"

# ---- 4. egress -------------------------------------------------------------------------------------------
log "4. the server's egress"
host_key="$(podman exec "$(c mcp-host)" cat /run/secrets/mcphost_key)"
via_host() {
    podman run --rm --network "${PROJECT}_mcphost" docker.io/curlimages/curl:8.11.1 -s --max-time 60 \
        -H "Authorization: Bearer $host_key" -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"lookup\",\"arguments\":{\"url\":\"$1\"}}}" \
        "http://mcp-host:8950/s/$SERVER_ID/mcp" | jq -r '.result.content[0].text // .error.message // .error'
}
r="$(via_host https://example.com/)"; [ "$r" = "status 200" ] || fail "its own host: $r"
ok "the server reaches its listed host (example.com): $r"
r="$(via_host https://www.iana.org/)"; case "$r" in *403*) ok "the server cannot reach an unlisted host (www.iana.org): ${r:0:80}" ;; *) fail "unlisted host: $r" ;; esac
r="$(via_host http://10.0.0.1/)"; case "$r" in *403*) ok "nor a private address: $r" ;; *) fail "private: $r" ;; esac

# ---- 5. memory ---------------------------------------------------------------------------------------
log "5. memory"
podman exec "$(c mcp-host)" true
mem_backend="$(podman stats --no-stream --format '{{.MemUsage}}' "$(c backend)")"
echo "     backend:                         $mem_backend"
echo "     mcp-host with the server running: $mem_running"
echo "     waiting for the idle stop is 10 minutes; restarting mcp-host to measure it idle instead"
podman restart "$(c mcp-host)" >/dev/null
for _ in $(seq 1 60); do podman exec "$(c mcp-host)" wget -q -O /dev/null http://127.0.0.1:8950/healthz 2>/dev/null && break; sleep 2; done
sleep 20
echo "     mcp-host idle (after a restart):  $(podman stats --no-stream --format '{{.MemUsage}}' "$(c mcp-host)")"

log "all $pass checks passed"
