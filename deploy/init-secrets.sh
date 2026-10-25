#!/bin/sh
# Creates the secrets the compose stack mounts. Idempotent: existing values are never overwritten.
#
#   ./deploy/init-secrets.sh [connector-id ...]     (connector_example_key is always created)
#
# 1. Values live as 0600 files in deploy/secrets/ (gitignored): the source of truth you can back up.
# 2. They are loaded into Podman's secret store under the same name; compose references them as
#    external secrets. (Plain bind-mounted files would be unreadable by the containers' non-root
#    users under rootless Podman and blocked by SELinux labels.)
set -eu

dir="$(cd "$(dirname "$0")" && pwd)/secrets"
mkdir -p "$dir"
chmod 700 "$dir"

random_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 36 | tr -d '\n=' | tr '+/' '-_'
    else
        head -c 36 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'
    fi
}

ensure_file() { # ensure_file <name>: generate a random value unless the file is non-empty
    f="$dir/$1"
    if [ -s "$f" ]; then
        echo "keep    $1"
    else
        (umask 077 && random_secret >"$f")
        echo "created $1"
    fi
    chmod 600 "$f"
}

load_into_podman() { # load_into_podman <name>: create or refresh the Podman secret <name>
    command -v podman >/dev/null 2>&1 || { echo "podman not found; skipped loading $1"; return 0; }
    f="$dir/$1"
    # Podman rejects empty secrets; a lone newline is "unset" for the backend (values are trimmed).
    [ -s "$f" ] || { podman secret exists "$1" 2>/dev/null || printf '\n' | podman secret create "$1" - >/dev/null; return 0; }
    if podman secret exists "$1" 2>/dev/null; then
        current="$(podman secret inspect --showsecret --format '{{.SecretData}}' "$1" 2>/dev/null || true)"
        [ "$current" = "$(cat "$f")" ] && return 0
        podman secret rm "$1" >/dev/null
    fi
    podman secret create "$1" "$f" >/dev/null
    echo "loaded  $1 into podman"
}

ensure_file postgres_password
ensure_file sandbox_key   # bearer keys for the agent computers (sandbox, browser)
ensure_file browser_key
for id in example "$@"; do ensure_file "connector_${id}_key"; done

# Provided by you. An empty file keeps the backend running with the agent disabled.
if [ ! -e "$dir/anthropic_api_key" ]; then
    (umask 077 && : >"$dir/anthropic_api_key")
    echo "created anthropic_api_key (EMPTY: put your Anthropic API key in deploy/secrets/anthropic_api_key, then re-run this script, to enable the agent)"
else
    echo "keep    anthropic_api_key"
fi
chmod 600 "$dir/anthropic_api_key"

for f in "$dir"/*; do
    [ -f "$f" ] && load_into_podman "$(basename "$f")"
done
exit 0
