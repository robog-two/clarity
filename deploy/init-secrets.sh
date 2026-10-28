#!/bin/sh
# Creates the secrets the compose stack mounts. Idempotent: existing values are never overwritten.
#
#   ./deploy/init-secrets.sh [connector-id ...]     (the example, mail and dav connector keys are always created)
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
ensure_file render_key    # bearer key for the render service (reports)
for id in example mail dav "$@"; do ensure_file "connector_${id}_key"; done

# Provided by you: OpenRouter runs every model, and the backend does not start until this holds a key
# (or OPENROUTER_API_KEY is set in deploy/.env).
if [ ! -e "$dir/openrouter_api_key" ]; then
    (umask 077 && : >"$dir/openrouter_api_key")
    echo "created openrouter_api_key (EMPTY: put your OpenRouter API key in deploy/secrets/openrouter_api_key, then re-run this script; the backend needs it to start)"
else
    echo "keep    openrouter_api_key"
fi
chmod 600 "$dir/openrouter_api_key"


# Your own TLS certificates: each <name>.pem with a matching <name>.key becomes the bundle
# tls/<name>.pem, which Caddy loads (deploy/caddy/Caddyfile). Bundles whose pair is gone are removed.
mkdir -p "$dir/tls"
chmod 700 "$dir/tls"
for bundle in "$dir"/tls/*.pem; do
    [ -f "$bundle" ] || continue
    name="$(basename "$bundle" .pem)"
    [ -f "$dir/$name.pem" ] && [ -f "$dir/$name.key" ] || { rm -f "$bundle"; echo "removed tls/$name.pem"; }
done
for cert in "$dir"/*.pem; do
    [ -f "$cert" ] || continue
    name="$(basename "$cert" .pem)"
    key="$dir/$name.key"
    [ -f "$key" ] || { echo "skip    $name.pem (no $name.key next to it)"; continue; }
    chmod 600 "$cert" "$key"
    tmp="$(mktemp "$dir/tls/.bundle.XXXXXX")"
    # a trailing newline after the certificate keeps the two PEM blocks apart
    { cat "$cert"; echo; cat "$key"; } | sed '/^[[:space:]]*$/d' >"$tmp"
    if cmp -s "$tmp" "$dir/tls/$name.pem"; then rm -f "$tmp"; else mv "$tmp" "$dir/tls/$name.pem"; echo "bundled tls/$name.pem"; fi
done

for f in "$dir"/*; do
    case "$f" in *.pem|*.key) continue ;; esac   # TLS files go to Caddy as a folder, not as Podman secrets
    [ -f "$f" ] && load_into_podman "$(basename "$f")"
done
exit 0
