#!/bin/sh
# Smoke test of the built-in Asana server in the real mcp-host image, in a compose project of its own
# (clarity-asanasmoke-<pid>; the running `clarity` stack is never touched) with a fake Asana behind a
# fake egress proxy (smoke/asana-compose.yaml), so no request leaves the machine. Proves:
#   - the Asana preset's item (kind builtin, sealed token) installs, lists its 12 tools and stops again
#   - /s/{id}/mcp lists the tools and a call reaches the fake Asana through the server's own proxy login
#     (checked against mcp-host's egress files) with the token, and errors read clearly
#   - the server runs as its own uid, and neither the token nor the proxy password reaches the log
# and prints the RSS of the Asana process and of mcp-host with it running.
#
#   computers/mcphost/smoke-asana.sh [mcphost-image]
# Needs podman, podman-compose, openssl, python3 and a JDK (e2e/mcp/Seal.java seals the token).
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
mcphost_image="${1:-ghcr.io/robog-two/clarity-mcphost:local}"
curl_image="${SMOKE_CURL_IMAGE:-docker.io/curlimages/curl:8.11.1}"
project="clarity-asanasmoke-$$"
dir="$(mktemp -d)"
chmod 755 "$dir"
rand() { head -c 24 /dev/urandom | base64 | tr -d '/+=\n'; }
host_key="smoke-$(rand)"
conn_key="smoke-$(rand)"
token="1/4242:smoke-asana-$(rand)"
id=asanasm1

compose() {
  SMOKE_DIR="$dir" SMOKE_MCPHOST_IMAGE="$mcphost_image" SMOKE_ASANA_TOKEN="$token" \
    podman-compose -p "$project" -f "$here/smoke/asana-compose.yaml" "$@"
}
cleanup() {
  compose down -v >/dev/null 2>&1 || true
  podman secret rm clarity_asanasmoke_host_key clarity_asanasmoke_connector_key >/dev/null 2>&1 || true
  rm -rf "$dir"
}
trap cleanup EXIT
cname() { podman ps -a --filter "label=io.podman.compose.project=$project" --filter "label=com.docker.compose.service=$1" --format '{{.Names}}' | head -1; }
fail() {
  echo "FAIL: $*" >&2
  podman logs "$(cname mcp-host)" 2>&1 | tail -30 >&2 || true
  podman logs "$(cname egress)" 2>&1 | tail -10 >&2 || true
  exit 1
}
ok() { echo "ok   $*"; }
expect_has() { case "$3" in *"$2"*) ok "$1" ;; *) fail "$1: wanted '$2' in '$3'" ;; esac; }

mkdir -p "$dir/certs"
python3 -B -c 'import sys; sys.path.insert(0, sys.argv[1]); import fake_asana; fake_asana.make_certs(sys.argv[2])' "$here/builtin/tests" "$dir/certs"
chmod 755 "$dir/certs"
printf '%s' "$host_key" | podman secret create clarity_asanasmoke_host_key - >/dev/null
printf '%s' "$conn_key" | podman secret create clarity_asanasmoke_connector_key - >/dev/null
echo '{"version": 1, "values": {"servers": []}}' >"$dir/config.json"
chmod 644 "$dir/config.json"
compose up -d >/dev/null 2>&1 || fail "compose up"
host="$(cname mcp-host)"

get() {
  path="$1"; shift
  podman run --rm --network "${project}_tools" "$curl_image" -s --max-time 30 "$@" "http://mcp-host:8950$path" 2>/dev/null || true
}
mcp() { # mcp <json-rpc message>: the raw reply
  podman run --rm --network "${project}_tools" "$curl_image" -s --max-time 90 \
    -H "Authorization: Bearer $host_key" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d "$1" "http://mcp-host:8950/s/$id/mcp" 2>/dev/null || true
}
call() { # call <tool> <arguments-json>: "<isError> <text>"
  mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" | python3 -I -c '
import json, sys
r = json.load(sys.stdin)
print(("ERROR " if r["result"].get("isError") else "") + r["result"]["content"][0]["text"]) if "result" in r else print("RPCERROR " + json.dumps(r.get("error")))'
}
state() {
  get /servers -H "Authorization: Bearer $host_key" | python3 -I -c '
import json, sys
try:
    s = [x for x in json.load(sys.stdin) if x["id"] == sys.argv[1]]
except Exception:
    s = []
print("%s %d %s | %s" % (s[0]["state"], len(s[0]["tools"]), s[0].get("source"), s[0].get("error") or "") if s else "missing 0")' "$id"
}
rss() { # rss <pattern>: kB resident of the first process whose command line has it
  podman exec "$host" sh -c 'for p in /proc/[0-9]*; do
      [ "$p" = "/proc/$$" ] && continue
      if tr "\0" " " <"$p/cmdline" 2>/dev/null | grep -q -- "$1"; then awk "/VmRSS/ {print \$2}" "$p/status"; break; fi
    done' sh "$1"
}

for _ in $(seq 1 90); do [ "$(get /healthz)" = ok ] && break; sleep 1; done
[ "$(get /healthz)" = ok ] || fail "mcp-host did not come up"
ok "mcp-host is up"

# --- the Asana preset's item, its token sealed the way the app seals it -----------------------------
for _ in $(seq 1 60); do
  key="$(podman exec "$(cname backend)" python3 -c 'import urllib.request,json;print(json.load(urllib.request.urlopen("http://127.0.0.1:8080/manifest")).get("sealingKey",""))' 2>/dev/null || true)"
  [ -n "$key" ] && break; sleep 1
done
[ -n "$key" ] || fail "no sealing key in the manifest"
sealed="$(printf '%s' "$token" | java "$root/e2e/mcp/Seal.java" "$key" mcphost "servers/$id/secret")"
cat >"$dir/config.json" <<EOF
{"version": 2, "values": {"servers": [
  {"id": "$id", "label": "Asana", "description": "Your Asana tasks, projects and comments", "kind": "builtin", "server": "asana",
   "secretEnv": "ASANA_ACCESS_TOKEN", "secret": $sealed, "allowHosts": ["app.asana.com"],
   "env": ["SSL_CERT_FILE=/opt/asana-test/ca.pem"]}
]}}
EOF
for _ in $(seq 1 120); do case "$(state)" in idle*) break ;; esac; sleep 1; done
expect_has "Asana installs and lists its tools, then stops" "idle 12 builtin asana 1.0.0" "$(state)"
sleep 2
mem_idle="$(podman stats --no-stream --format '{{.MemUsage}}' "$host")"
jvm_idle="$(rss net.robog.clarity.mcphost)"

listed="$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | python3 -I -c 'import json,sys; print(" ".join(sorted(t["name"] for t in json.load(sys.stdin)["result"]["tools"])))')"
expect_has "tools/list through /s/{id}/mcp" "add_comment complete_task create_task get_task list_my_tasks list_project_tasks list_projects list_sections list_workspaces move_task_to_section search_tasks update_task" "$listed"

# --- calls reach the fake Asana through the server's own proxy login ----------------------------------
expect_has "list_my_tasks works through the proxy" "Essay outline · due" "$(call list_my_tasks '{"due_within_days": 7}')"
expect_has "a write works" "Created: Smoke task" "$(call create_task '{"name": "Smoke task", "project_gid": "301"}')"
expect_has "a missing task reads clearly" "ERROR Error: not found in Asana (404)" "$(call get_task '{"task_gid": "999"}')"
expect_has "bad arguments read clearly" "ERROR Error: task_gid 'x' is not valid" "$(call get_task '{"task_gid": "x"}')"
expect_has "Asana is running" "running" "$(state)"
who="$(podman exec "$host" sh -c 'for p in /proc/[0-9]*; do
      [ "$p" = "/proc/$$" ] && continue
    if tr "\0" " " <"$p/cmdline" 2>/dev/null | grep -q asana.py; then awk "/^Uid:/ {print \$2}" "$p/status"; break; fi
  done')"
[ -n "$who" ] && [ "$who" != 0 ] || fail "the Asana process runs as uid '$who'"
ok "the Asana process runs as its own uid ($who)"
sleep 2
asana_rss="$(rss asana.py)"
jvm_run="$(rss net.robog.clarity.mcphost)"
mem_run="$(podman stats --no-stream --format '{{.MemUsage}}' "$host")"

if podman logs "$host" 2>&1 | grep -F "$token"; then fail "the Asana token reached the log"; fi
if podman logs "$host" 2>&1 | grep -E 's-[a-z0-9]+:[A-Za-z0-9_-]{20,}@'; then fail "a proxy password reached the log"; fi
ok "no token or proxy password in the log"

echo "memory idle (container): $mem_idle; mcp-host JVM RSS ${jvm_idle} kB"
echo "memory with Asana running (container): $mem_run; mcp-host JVM RSS ${jvm_run} kB; Asana process RSS ${asana_rss} kB"
echo "asana smoke OK"
