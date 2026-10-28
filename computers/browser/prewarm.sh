#!/bin/sh
# Build-time only. Starts Firefox once on the profile template so the add-ons are installed and have
# run their first-run logic. Without this, Privacy Badger opens a "Thank you for installing" tab in
# every fresh profile (it has no setting to skip it), which would show up as a stray page for the
# agent. Privacy Badger marks first run as finished a minute after install, so we run for 80 s.
set -eu
profile="${1:?usage: prewarm.sh <profile-dir>}"
export HOME=/tmp/prewarm-home
mkdir -p "$HOME"
# Build containers cannot grant the capability Firefox's own sandbox needs; the sandbox is not under
# test here and this profile never serves pages.
export MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GPU_SANDBOX=1 MOZ_DISABLE_RDD_SANDBOX=1 \
       MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 MOZ_CRASHREPORTER_DISABLE=1

# uBlock Origin's cookie-notice lists: served from the pinned copies (prewarm-lists.mjs) through a
# build-only proxy that only this run trusts, so the template carries them from the first start.
lists_dir="${PREWARM_LISTS:-/tmp/prewarm}"
policies=/opt/firefox/distribution/policies.json
srv=""
if [ -f "$lists_dir/prewarm-lists.mjs" ]; then
  node "$lists_dir/prewarm-lists.mjs" "$lists_dir/lists" "$lists_dir/tls" >/tmp/prewarm-lists.log 2>&1 &
  srv=$!
  cp "$profile/user.js" /tmp/prewarm-user.js
  cp "$policies" /tmp/prewarm-policies.json
  sed -e 's/"network.proxy.http", "egress"/"network.proxy.http", "127.0.0.1"/' -e 's/"network.proxy.ssl", "egress"/"network.proxy.ssl", "127.0.0.1"/' \
      -e 's/"network.proxy.http_port", 3128/"network.proxy.http_port", 3199/' -e 's/"network.proxy.ssl_port", 3128/"network.proxy.ssl_port", 3199/' \
      /tmp/prewarm-user.js >"$profile/user.js"
  node -e 'const fs=require("fs"); const p=JSON.parse(fs.readFileSync(process.argv[1])); p.policies.Certificates={Install:[process.argv[2]]}; fs.writeFileSync(process.argv[1], JSON.stringify(p));' \
    "$policies" "$lists_dir/tls/ca.pem"
fi

/opt/firefox/firefox -headless --profile "$profile" about:blank >/tmp/prewarm.log 2>&1 &
pid=$!

# Privacy Badger sets firstRunTimerFinished 60 s after install; give it at least 80 s to write that
# out. Then wait for uBlock Origin's lists, which can take minutes under emulation (arm64 images on
# an amd64 builder); give up after 10 min.
lists_served() {
  [ -z "$srv" ] && return 0
  for list in annoyances-cookies.txt easylist-cookies.txt adguard-18.txt; do
    grep -q "^200 $list " /tmp/prewarm-lists.log 2>/dev/null || return 1
  done
}
waited=0
while [ "$waited" -lt 80 ] || { ! lists_served && [ "$waited" -lt 600 ]; }; do
  kill -0 "$pid" 2>/dev/null || { echo "prewarm: Firefox exited early" >&2; tail -20 /tmp/prewarm.log >&2; exit 1; }
  sleep 1
  waited=$((waited + 1))
done
echo "prewarm: Firefox ran ${waited} s"
sleep 3
kill -TERM "$pid"
for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
kill -KILL "$pid" 2>/dev/null || true

if [ -n "$srv" ]; then
  kill "$srv" 2>/dev/null || true
  cp /tmp/prewarm-user.js "$profile/user.js"
  cp /tmp/prewarm-policies.json "$policies"
  # The build-only CA went into the profile's certificate store: drop the store (Firefox makes a new one).
  rm -f "$profile/cert9.db" "$profile/key4.db" /tmp/prewarm-user.js /tmp/prewarm-policies.json
  for list in annoyances-cookies.txt easylist-cookies.txt adguard-18.txt; do
    grep -q "^200 $list " /tmp/prewarm-lists.log || { echo "prewarm: uBlock Origin did not fetch $list" >&2; cat /tmp/prewarm-lists.log >&2; grep -i "ublock\|uBlock0\|cert" /tmp/prewarm.log | tail -20 >&2; exit 1; }
  done
  echo "prewarm: uBlock Origin lists served:"; grep '^200' /tmp/prewarm-lists.log | sort | uniq -c
  rm -f /tmp/prewarm-lists.log
fi

# Leave only what a clean start should inherit.
cd "$profile"
# (No sessionstore: user.js restores the last session, and a fresh profile has none to restore.)
rm -rf lock .parentlock cache2 crashes minidumps datareporting sessionstore.jsonlz4 sessionstore-backups sessionCheckpoints.json \
       saved-telemetry-pings Marionette* WebDriverBiDiServer.json bookmarkbackups thumbnails \
       places.sqlite* favicons.sqlite* cookies.sqlite* formhistory.sqlite*
rm -rf "$HOME" /tmp/prewarm.log
chmod -R a+rX "$profile"   # the runtime user (10001) copies this template
echo "prewarm: done"
