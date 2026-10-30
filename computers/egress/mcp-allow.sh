#!/bin/sh
# Squid external ACL helper for the mcp-host listener: one "user host" line in, OK or ERR out.
#
#   egress-mcp-allow <dir>
#
# <dir>/allow/<user> lists the hosts that user (one MCP server, or its install) may reach, one per
# line; a line starting with a dot also allows its subdomains (".example.com" allows example.com and
# www.example.com). The file is read on every lookup (Squid caches answers for a few seconds), so a
# changed list needs no reload. Private and loopback destinations are refused before this runs.
dir="$1"
while read -r user host rest; do
  ok=ERR
  host="$(printf '%s' "$host" | tr 'A-Z' 'a-z')"
  case "$user" in
    '' | *[!a-z0-9-]*) ;;
    *)
      f="$dir/allow/$user"
      case "$host" in
        '' | *[!a-z0-9.-]*) ;;
        *)
          if [ ${#user} -le 40 ] && [ -f "$f" ]; then
            while IFS= read -r line || [ -n "$line" ]; do
              case "$line" in
                '') ;;
                .*) case "$host" in *"$line" | "${line#.}") ok=OK; break ;; esac ;;
                *) [ "$host" = "$line" ] && { ok=OK; break; } ;;
              esac
            done <"$f"
          fi
          ;;
      esac
      ;;
  esac
  echo "$ok"
done
