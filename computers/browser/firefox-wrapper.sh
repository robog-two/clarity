#!/bin/sh
# What geckodriver launches as "firefox" (entrypoint.sh passes it as --firefox-path), so every
# Firefox start, also a relaunch inside a running container, first prepares the saved session
# (session-prep.mjs: the real tabs come back, the tab WebDriver takes over is a spare empty one).
profile=""
prev=""
for arg in "$@"; do
  case "$prev" in -profile|--profile) profile="$arg" ;; esac
  prev="$arg"
done
if [ -n "$profile" ]; then
  node /opt/clarity-browser/session-prep.mjs "$profile" || echo "clarity-browser: session prep failed; tabs may not be restored" >&2
fi
exec /opt/firefox/firefox "$@"
