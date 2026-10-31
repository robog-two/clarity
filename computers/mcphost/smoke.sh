#!/bin/sh
# Smoke test for mcp-host against real containers, in a compose project of its own
# (clarity-mcpsmoke-<pid>; the running `clarity` stack is never touched). Needs podman, podman-compose
# and internet access. The stack: the egress proxy, mcp-host, and a stand-in backend serving a config
# with two copies of the tests' fake MCP server (kind `local`, enabled by MCPHOST_LOCAL_DIR). Proves:
#   - the cached listing appears after install, and a tool call works through the host
#   - each server reaches its own hosts through the proxy, not another host, not a private address,
#     and nothing without the proxy
#   - a server runs as its own uid with no capabilities and no groups, and cannot read another
#     server's home or environment, the keys, the supervisor's state or the proxy files
#   - every route but /healthz needs the bearer key
# and prints the image size and the container's memory idle and with one server running.
#
#   computers/mcphost/smoke.sh [mcphost-image] [egress-image]
#   SMOKE_PRESETS=1 computers/mcphost/smoke.sh ...   # also install the presets (no tokens) and list their tools
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
mcphost_image="${1:-ghcr.io/robog-two/clarity-mcphost:local}"
egress_image="${2:-ghcr.io/robog-two/clarity-egress:local}"
curl_image="${SMOKE_CURL_IMAGE:-docker.io/curlimages/curl:8.11.1}"
project="clarity-mcpsmoke-$$"
dir="$(mktemp -d)"
chmod 755 "$dir"
rand() { head -c 24 /dev/urandom | base64 | tr -d '/+=\n'; }
host_key="smoke-$(rand)"
conn_key="smoke-$(rand)"

compose() {
  SMOKE_DIR="$dir" SMOKE_MCPHOST_IMAGE="$mcphost_image" SMOKE_EGRESS_IMAGE="$egress_image" \
    podman-compose -p "$project" -f "$here/smoke/compose.yaml" "$@"
}
cleanup() {
  compose down -v >/dev/null 2>&1 || true
  podman secret rm clarity_mcpsmoke_host_key clarity_mcpsmoke_connector_key >/dev/null 2>&1 || true
  rm -rf "$dir"
}
trap cleanup EXIT
cname() { podman ps -a --filter "label=io.podman.compose.project=$project" --filter "label=com.docker.compose.service=$1" --format '{{.Names}}' | head -1; }
fail() {
  echo "FAIL: $*" >&2
  podman logs "$(cname mcp-host)" 2>&1 | tail -30 >&2 || true
  exit 1
}
ok() { echo "ok   $*"; }

printf '%s' "$host_key" | podman secret create clarity_mcpsmoke_host_key - >/dev/null
printf '%s' "$conn_key" | podman secret create clarity_mcpsmoke_connector_key - >/dev/null

fake='"kind":"local","command":"/opt/mcp-local/fake_mcp.py"'
write_config() { # write_config <version> <extra items, comma-led or empty>
  cat >"$dir/config.json" <<EOF
{"version": $1, "values": {"servers": [
  {"id": "smokea01", "label": "Alpha", "description": "fake A", $fake, "allowHosts": ["example.com"], "env": ["CANARY=alpha-canary-0193"]},
  {"id": "smokeb01", "label": "Beta", "description": "fake B", $fake, "allowHosts": ["www.iana.org"], "env": ["CANARY=beta-canary-7731"]}
  $2
]}}
EOF
  chmod 644 "$dir/config.json"
}
write_config 1 ""
compose up -d >/dev/null 2>&1 || fail "compose up"
host="$(cname mcp-host)"

get() { # get <path> [curl args...]: the body, or the status with -w
  path="$1"; shift
  podman run --rm --network "${project}_tools" "$curl_image" -s --max-time 30 "$@" "http://mcp-host:8950$path" 2>/dev/null || true
}
servers() { get /servers -H "Authorization: Bearer $host_key"; }
mcp() { # mcp <id> <json-rpc message>
  podman run --rm --network "${project}_tools" "$curl_image" -s --max-time 90 \
    -H "Authorization: Bearer $host_key" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d "$2" "http://mcp-host:8950/s/$1/mcp" 2>/dev/null || true
}
call() { # call <id> <tool> <arguments-json>: the tool's text (or RPCERROR / HTTPERROR)
  mcp "$1" "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" | python3 -I -c '
import json, sys
raw = sys.stdin.read()
if not raw.lstrip().startswith("{"):  # a server-sent event stream (a remote server): its last message
    data = [l[5:].strip() for l in raw.splitlines() if l.startswith("data:")]
    raw = data[-1] if data else raw
try:
    r = json.loads(raw)
except Exception:
    print("HTTPERROR " + raw[:200]); sys.exit()
if "result" in r: print(r["result"]["content"][0]["text"])
elif "error" in r and isinstance(r["error"], dict): print("RPCERROR " + r["error"]["message"])
else: print("HTTPERROR " + str(r.get("error")))'
}
state_of() { # state_of <id>: "<state> <tool count> <error>"
  servers | python3 -I -c '
import json, sys
try:
    s = [x for x in json.load(sys.stdin) if x["id"] == sys.argv[1]]
except Exception:
    s = []
print("%s %d %s" % (s[0]["state"], len(s[0]["tools"]), s[0].get("error") or "") if s else "missing 0")' "$1"
}
wait_state() { # wait_state <id> <state> <seconds>
  for _ in $(seq 1 "$3"); do
    case "$(state_of "$1")" in "$2"*) return 0 ;; esac
    sleep 1
  done
  fail "$1 did not reach $2: $(state_of "$1")"
}
expect_has() { # expect_has <description> <needle> <haystack>
  case "$3" in *"$2"*) ok "$1" ;; *) fail "$1: wanted '$2' in '$3'" ;; esac
}

# --- up, listed once, stopped again --------------------------------------------------------------
for _ in $(seq 1 90); do [ "$(get /healthz)" = ok ] && break; sleep 1; done
[ "$(get /healthz)" = ok ] || fail "mcp-host did not come up"
ok "mcp-host is up"
wait_state smokea01 idle 120
wait_state smokeb01 idle 120
expect_has "Alpha's tools are listed after install" "idle 12" "$(state_of smokea01)"
expect_has "the manifest names the connector" '"kind": "mcp-host"' "$(podman exec "$(cname backend)" python3 -c 'import urllib.request;print(urllib.request.urlopen("http://127.0.0.1:8080/manifest").read().decode())' | python3 -I -c 'import json,sys; print(json.dumps(json.load(sys.stdin)))')"
sleep 2
mem_idle="$(podman stats --no-stream --format '{{.MemUsage}}' "$host")"

# --- the bearer key -----------------------------------------------------------------------------
expect_has "/servers without the key is refused" 401 "$(get /servers -o /dev/null -w '%{http_code}')"
expect_has "/servers with a wrong key is refused" 401 "$(get /servers -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer wrong')"
expect_has "/s/{id}/mcp without the key is refused" 401 "$(get /s/smokea01/mcp -o /dev/null -w '%{http_code}' -X POST -d '{}')"

# --- a call through the host --------------------------------------------------------------------
expect_has "a tool call works through the host" "through the host" "$(call smokea01 echo '{"text":"through the host"}')"
expect_has "Alpha is running" running "$(state_of smokea01)"
sleep 3
mem_one="$(podman stats --no-stream --format '{{.MemUsage}}' "$host")"

# --- egress per server --------------------------------------------------------------------------
expect_has "Alpha reaches its own host (example.com)" "status 200" "$(call smokea01 fetch '{"url":"https://example.com/"}')"
expect_has "Alpha cannot reach a host not on its list" "403" "$(call smokea01 fetch '{"url":"https://www.iana.org/"}')"
expect_has "Beta reaches its own host (www.iana.org)" "status 200" "$(call smokeb01 fetch '{"url":"https://www.iana.org/"}')"
expect_has "Beta cannot reach Alpha's host" "403" "$(call smokeb01 fetch '{"url":"https://example.com/"}')"
expect_has "Alpha cannot reach a private address" "403" "$(call smokea01 fetch '{"url":"http://10.0.0.1/"}')"
expect_has "Alpha cannot reach a name resolving to a private address" "403" "$(call smokea01 fetch '{"url":"https://private.test/"}')"
expect_has "Alpha cannot reach the host gateway" "403" "$(call smokea01 fetch '{"url":"http://host.containers.internal/"}')"
expect_has "Alpha has no route around the proxy" "error" "$(call smokea01 direct '{"host":"1.1.1.1","port":443}')"
# (mcp-host shares the `ingest` network (and in production `mcphost`) with the backend, so a server can open a
# connection to the backend's port; everything there needs a key it does not have.)
echo "note a server's direct connection to the backend's port: $(call smokea01 direct '{"host":"backend","port":8080}')"

# --- isolation ----------------------------------------------------------------------------------
who_a="$(call smokea01 whoami '{}')"
who_b="$(call smokeb01 whoami '{}')"
expect_has "Alpha runs as its own uid" "uid=200" "$who_a"
uid_a="$(printf '%s' "$who_a" | sed -n 's/^uid=\([0-9]*\).*/\1/p')"
uid_b="$(printf '%s' "$who_b" | sed -n 's/^uid=\([0-9]*\).*/\1/p')"
[ -n "$uid_a" ] && [ -n "$uid_b" ] && [ "$uid_a" != "$uid_b" ] && [ "$uid_a" != 0 ] || fail "uids: $uid_a $uid_b"
ok "Alpha ($uid_a) and Beta ($uid_b) have different uids"
expect_has "no supplementary groups" "groups=[]" "$who_a"
expect_has "no effective capabilities" "CapEff:	0000000000000000" "$who_a"
expect_has "no permitted capabilities" "CapPrm:	0000000000000000" "$who_a"
expect_has "no ambient capabilities" "CapAmb:	0000000000000000" "$who_a"
expect_has "no new privileges" "NoNewPrivs:	1" "$who_a"
home_a="/data/servers/smokea01"
expect_has "Alpha writes in its home" "ok" "$(call smokea01 write_file "{\"path\":\"$home_a/notes.txt\",\"text\":\"alpha private\"}")"
expect_has "Alpha reads it back" "alpha private" "$(call smokea01 read_file "{\"path\":\"$home_a/notes.txt\"}")"
expect_has "Beta cannot read Alpha's file" "PermissionError" "$(call smokeb01 read_file "{\"path\":\"$home_a/notes.txt\"}")"
expect_has "Beta cannot list Alpha's home" "PermissionError" "$(call smokeb01 list_dir "{\"path\":\"$home_a\"}")"
expect_has "Beta cannot list the homes" "PermissionError" "$(call smokeb01 list_dir '{"path":"/data/servers"}')"
pid_a="$(call smokea01 pid '{}')"
expect_has "Beta cannot read Alpha's environment" "PermissionError" "$(call smokeb01 read_environ "{\"pid\":$pid_a}")"
expect_has "Beta's environment is its own" "beta-canary-7731" "$(call smokeb01 env '{"name":"CANARY"}')"
expect_has "Beta cannot read the supervisor's environment" "PermissionError" "$(call smokeb01 read_environ '{"pid":1}')"
expect_has "Beta cannot read the backend key" "PermissionError" "$(call smokeb01 read_file '{"path":"/run/secrets/clarity_mcpsmoke_host_key"}')"
expect_has "Beta cannot read the connector key" "PermissionError" "$(call smokeb01 read_file '{"path":"/run/secrets/clarity_mcpsmoke_connector_key"}')"
expect_has "Beta cannot read the sealing key" "PermissionError" "$(call smokeb01 read_file '{"path":"/data/supervisor/sealing-key"}')"
expect_has "Beta cannot read the proxy logins" "PermissionError" "$(call smokeb01 list_dir '{"path":"/egress"}')"
expect_has "Beta's proxy login is its own" "http://s-smokeb01:" "$(call smokeb01 env '{"name":"HTTPS_PROXY"}')"
expect_has "a server cannot write outside its home" "error:" "$(call smokeb01 write_file '{"path":"/opt/x","text":"x"}')"

# --- a crash, and the logs -----------------------------------------------------------------------
expect_has "a crash is reported to the caller" "Beta stopped: exited with code 4" "$(call smokeb01 crash '{}')"
expect_has "a crashed server is failed" "failed" "$(state_of smokeb01)"
if podman logs "$host" 2>&1 | grep -E 's-[a-z0-9]+:[A-Za-z0-9_-]{20,}@'; then fail "a proxy password reached the log"; fi
if podman logs "$host" 2>&1 | grep -F "$host_key"; then fail "the bearer key reached the log"; fi
ok "no proxy password or key in the log"

# --- optional: the presets, installed through the proxy (no tokens) ------------------------------
if [ "${SMOKE_PRESETS:-}" = 1 ]; then
  write_config 2 ',
  {"id": "presetgh", "label": "GitHub", "kind": "github-release", "package": "github/github-mcp-server", "version": "v2.0.3",
   "asset": "github-mcp-server_Linux_x86_64.tar.gz", "sha256": "f797c20fe02a755865d0e2d46184ab47e31bf1619b0855b8d89c531e45a651c2",
   "command": "github-mcp-server", "args": ["stdio"], "env": ["GITHUB_TOOLSETS=context,repos,issues,pull_requests,notifications", "GITHUB_PERSONAL_ACCESS_TOKEN=not-a-real-token"],
   "allowHosts": ["api.github.com", "raw.githubusercontent.com"]},
  {"id": "presetcv", "label": "Canvas", "kind": "pypi", "package": "canvas-mcp", "version": "1.16.0", "command": "canvas-mcp-server",
   "env": ["CANVAS_API_URL=https://canvas.instructure.com/api/v1", "CANVAS_API_TOKEN=not-a-real-token"], "allowHosts": ["canvas.instructure.com"]},
  {"id": "remotedw", "label": "DeepWiki", "kind": "remote", "url": "https://mcp.deepwiki.com/mcp"}'
  for id in presetgh presetcv remotedw; do
    for _ in $(seq 1 300); do
      case "$(state_of "$id")" in idle*|failed*) break ;; esac
      sleep 2
    done
    echo "preset $id: $(state_of "$id")"
  done
  echo "preset GitHub get_me (fake token, through the proxy): $(call presetgh get_me '{}' | head -c 300)"
  echo "preset Canvas list_courses (fake token, through the proxy): $(call presetcv list_courses '{}' | head -c 300)"
  echo "remote DeepWiki, forwarded through the proxy: $(call remotedw read_wiki_structure '{"repoName":"modelcontextprotocol/python-sdk"}' | head -c 200)"
  echo "the proxy's view of the presets:"
  podman logs "$(cname egress)" 2>/dev/null | grep -E 'api.github.com|canvas.instructure.com|mcp.deepwiki.com|registry.npmjs.org|pypi|github.com' | awk '{print "  " $4, $6, $7, $8}' | sort | uniq -c
  podman stats --no-stream --format 'with presets: {{.MemUsage}}' "$host"
fi

echo "image $(podman image inspect "$mcphost_image" --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1000/1000}')"
echo "memory idle: $mem_idle"
echo "memory with one fake server running: $mem_one"
echo "mcphost smoke OK"
