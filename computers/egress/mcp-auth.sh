#!/bin/sh
# Squid basic-auth helper for the mcp-host listener: one "user password" line in, OK or ERR out.
#
#   egress-mcp-auth <dir>
#
# <dir>/users/<user> holds the SHA-256 (hex) of that user's password, written by mcp-host for each
# running server (and each install) and removed when it stops. The file is read on every lookup, so a
# new or removed login needs no reload. Only names and passwords mcp-host can generate are accepted.
dir="$1"
while read -r user pass rest; do
  ok=ERR
  case "$user" in
    '' | *[!a-z0-9-]*) ;;
    *)
      case "$pass" in
        '' | *[!A-Za-z0-9_-]*) ;;
        *)
          f="$dir/users/$user"
          if [ ${#user} -le 40 ] && [ -f "$f" ] && read -r want <"$f"; then
            # printf is a shell builtin: the password is never on a command line
            got="$(printf '%s' "$pass" | sha256sum)"
            [ "${got%% *}" = "$want" ] && ok=OK
          fi
          ;;
      esac
      ;;
  esac
  echo "$ok"
done
