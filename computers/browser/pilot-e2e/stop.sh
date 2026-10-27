#!/bin/sh
# Removes the pilot test browser started by start.sh.
podman rm -f "${PILOT_BROWSER_NAME:-clarity-browser-pilot-e2e}" >/dev/null 2>&1 || true
