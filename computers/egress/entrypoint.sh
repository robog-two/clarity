#!/bin/sh
# Renders the Squid config and runs Squid in the foreground.
#
#   EGRESS_BROWSER_HOST         name that resolves to this container's address on the browser network
#                               (default egress-browser). Squid listens for the browser ONLY there.
#   EGRESS_BACKEND_HOST         same for the backend network (default egress-backend).
#   EGRESS_SANDBOX_HOST         same for the sandbox network (default egress-sandbox); the sandbox
#                               gets the browser's policy (public internet, ports 80/443).
#   CLARITY_BACKEND_EGRESS_ALLOW  extra hosts the backend may reach, comma/space separated
#                               (openrouter.ai, the model API, is always allowed). A leading dot allows subdomains.
#
# Each client network gets its own listener bound to this container's address on that network, so
# a container on one network cannot borrow the other network's policy.
set -eu

browser_host="${EGRESS_BROWSER_HOST:-egress-browser}"
backend_host="${EGRESS_BACKEND_HOST:-egress-backend}"
sandbox_host="${EGRESS_SANDBOX_HOST:-egress-sandbox}"
conf=/tmp/squid.conf

resolve() { # resolve <name>: first IPv4 address, retrying while DNS comes up
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    ip="$(getent ahostsv4 "$1" 2>/dev/null | awk 'NR==1{print $1}')"
    if [ -n "$ip" ]; then echo "$ip"; return 0; fi
    sleep 1
  done
  echo "egress: cannot resolve $1; attach this container to its client network with that alias" >&2
  return 1
}

browser_ip="$(resolve "$browser_host")"
backend_ip="$(resolve "$backend_host")"
sandbox_ip="$(resolve "$sandbox_host")"
[ "$(printf '%s\n' "$browser_ip" "$backend_ip" "$sandbox_ip" | sort -u | wc -l)" -eq 3 ] \
  || { echo "egress: browser, backend and sandbox must resolve to different addresses" >&2; exit 1; }

model_host="openrouter.ai"
allow=""
for h in $(printf '%s' "${CLARITY_BACKEND_EGRESS_ALLOW:-}" | tr ',' ' '); do
  case "$h" in
    *[!A-Za-z0-9.-]*) echo "egress: bad host in CLARITY_BACKEND_EGRESS_ALLOW: $h" >&2; exit 1 ;;
  esac
  # Already allowed; listing it twice (or with its subdomains) would make Squid complain.
  [ "$h" = "$model_host" ] && continue
  [ "$h" = ".$model_host" ] && { model_host=""; }
  allow="$allow $h"
done
allow="$(printf '%s' "$model_host$allow" | sed 's/^ //')"

sed -e "s|@BROWSER_IP@|$browser_ip|g" \
    -e "s|@BACKEND_IP@|$backend_ip|g" \
    -e "s|@SANDBOX_IP@|$sandbox_ip|g" \
    -e "s|@BACKEND_ALLOW@|$allow|g" \
    /usr/local/share/egress/squid.conf.tmpl >"$conf"

echo "egress: browser listener $browser_ip:3128, backend listener $backend_ip:3128, sandbox listener $sandbox_ip:3128, backend allows: $allow" >&2
exec squid -N -f "$conf"
