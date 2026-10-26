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

/opt/firefox/firefox -headless --profile "$profile" about:blank >/tmp/prewarm.log 2>&1 &
pid=$!

# Privacy Badger sets firstRunTimerFinished 60 s after install; give it time to write that out.
for _ in $(seq 1 80); do
  kill -0 "$pid" 2>/dev/null || { echo "prewarm: Firefox exited early" >&2; tail -20 /tmp/prewarm.log >&2; exit 1; }
  sleep 1
done
sleep 3
kill -TERM "$pid"
for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
kill -KILL "$pid" 2>/dev/null || true

# Leave only what a clean start should inherit.
cd "$profile"
rm -rf lock .parentlock cache2 crashes minidumps datareporting sessionstore-backups sessionCheckpoints.json \
       saved-telemetry-pings Marionette* WebDriverBiDiServer.json bookmarkbackups thumbnails \
       places.sqlite* favicons.sqlite* cookies.sqlite* formhistory.sqlite*
rm -rf "$HOME" /tmp/prewarm.log
chmod -R a+rX "$profile"   # the runtime user (10001) copies this template
echo "prewarm: done"
