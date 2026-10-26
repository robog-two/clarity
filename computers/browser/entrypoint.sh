#!/bin/sh
# Starts the Firefox DevTools MCP server behind supergateway (stdio -> streamable HTTP).
#
#   BROWSER_PORT                port to listen on (default 8931), path /mcp, health at /healthz
#   BROWSER_KEY_FILE            bearer key file (default /run/secrets/browser_key); required unless
#                               BROWSER_ALLOW_NO_AUTH=true
#   BROWSER_PROFILE_DIR         optional persistent profile directory (a volume). Default: a fresh
#                               copy of the profile template on tmpfs, so every start is clean.
#   BROWSER_VIEWPORT            window size (default 1280x800)
#   BROWSER_TOOL_PRESET         firefox-devtools-mcp --tool-preset (default: the server's own default)
#   BROWSER_IDLE_TIMEOUT        passed to --idle-timeout when set (e.g. 10m)
#   BROWSER_SESSION_TIMEOUT_MS  how long an idle MCP session (and its Firefox) is kept (default 6 hours);
#                               a new session starts a new Firefox, which loses the open pages
#   BROWSER_DOWNLOADS_DAYS      downloads older than this are deleted at start (default 7)
#
# Downloads go to /downloads, a volume the sandbox also mounts (read-only), so the agent can work on
# downloaded files in its shell.
set -eu

port="${BROWSER_PORT:-8931}"
key_file="${BROWSER_KEY_FILE:-/run/secrets/browser_key}"

mkdir -p "$HOME" /downloads
find /downloads -mindepth 1 -mtime +"${BROWSER_DOWNLOADS_DAYS:-7}" -delete 2>/dev/null || true

# Profile: seed from the template (user.js + signed add-ons). The MCP server insists on a profile
# directory carrying its marker file; otherwise it nests a new profile below the one given.
if [ -n "${BROWSER_PROFILE_DIR:-}" ]; then
  profile="$BROWSER_PROFILE_DIR/firefox_devtools_mcp_profile"
else
  profile="/tmp/profile/firefox_devtools_mcp_profile"
fi
if [ ! -f "$profile/.firefox-devtools-mcp" ]; then
  mkdir -p "$profile"
  cp -r /opt/profile-template/. "$profile/"
  : >"$profile/.firefox-devtools-mcp"
fi
# A kept profile still takes this image's preferences, and a Firefox killed with the container
# leaves its lock behind (another process may hold that pid now).
cp /opt/profile-template/user.js "$profile/user.js"
rm -f "$profile/lock" "$profile/.parentlock"

auth=""
if [ -s "$key_file" ]; then
  auth="--apiKeyFile $key_file"
elif [ "${BROWSER_ALLOW_NO_AUTH:-}" != "true" ]; then
  echo "clarity-browser: no bearer key in $key_file. Create the browser_key secret (deploy/init-secrets.sh) or set BROWSER_ALLOW_NO_AUTH=true for local development." >&2
  exit 1
fi

mcp="node /opt/mcp/node_modules/@mozilla/firefox-devtools-mcp/dist/index.js"
mcp="$mcp --headless --firefox-path /opt/firefox/firefox --profile-path $profile --viewport ${BROWSER_VIEWPORT:-1280x800}"
[ -n "${BROWSER_TOOL_PRESET:-}" ] && mcp="$mcp --tool-preset $BROWSER_TOOL_PRESET"
[ -n "${BROWSER_IDLE_TIMEOUT:-}" ] && mcp="$mcp --idle-timeout $BROWSER_IDLE_TIMEOUT"

# --stateful: one MCP server (and one Firefox) per client session, kept alive between requests.
# shellcheck disable=SC2086
exec node /opt/mcp/node_modules/supergateway/dist/index.js \
  --stdio "$mcp" \
  --outputTransport streamableHttp --stateful --sessionTimeout "${BROWSER_SESSION_TIMEOUT_MS:-21600000}" \
  --port "$port" --streamableHttpPath /mcp \
  --healthEndpoint /healthz \
  $auth
