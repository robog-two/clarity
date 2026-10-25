# Deploying Clarity

One person, one stack: Postgres, the backend, optional connectors and an optional browser tool,
wired with least-privilege networks and Podman secrets. Rootless Podman is the reference runtime.

## Quick start

```sh
./deploy/init-secrets.sh                          # generates deploy/secrets/* and loads them as Podman secrets (idempotent)
echo -n 'sk-ant-...' > deploy/secrets/anthropic_api_key   # optional: without it the agent is disabled
./deploy/init-secrets.sh                          # re-run to load the key
cp deploy/.env.example deploy/.env                # optional overrides

podman compose --env-file deploy/.env -f deploy/compose.yaml up -d
curl http://127.0.0.1:8080/healthz
```

Images are pulled from `ghcr.io/robog-two/clarity-*:${CLARITY_TAG:-latest}`; to build locally instead
use `up -d --build` (the `build:` sections use `deploy/container/jvm-app.Containerfile`).

| Profile | Adds | Enable |
|---|---|---|
| (none) | `postgres`, `backend`, `caddy` (TLS) | |
| `example` | `connector-example` (pushes one sample message when `emitSample` is on) | `--profile example` |
| `browser` | `browser` (Playwright MCP, headless Chromium) | `--profile browser`, and set `CLARITY_MCP_SERVERS=browser=http://browser:8931/mcp` |

## TLS (Caddy)

`caddy` fronts the backend and provisions/renews its own certificates; nothing else to run.
Clients use `https://<CLARITY_DOMAIN>[:CLARITY_HTTPS_PORT]`. The backend's plain-HTTP port stays on loopback.

| `CLARITY_DOMAIN` | Certificate |
|---|---|
| `localhost` (default), an IP, `*.local`/`*.lan` | Caddy's built-in local CA. Works offline, but clients must trust its root (below). |
| a public DNS name (`clarity.example.com`) | Let's Encrypt / ZeroSSL via ACME. Needs the name pointing at this host and ports 80 and 443 reachable from the internet. Trusted everywhere, including Android release builds. |

Pick the address family with `CLARITY_PUBLIC_BIND`: `[::]` (default; IPv6, and whether it also accepts IPv4
depends on the host's dual-stack setting), `0.0.0.0` for IPv4 only, or a specific address like `[2001:db8::5]`. The
ACME egress network has IPv6 enabled so issuance works on IPv6-only hosts.

Certificates, the ACME account and the local CA live in the `caddy-data` volume (back it up, or you
re-issue on loss). Rootless Podman can't publish ports below 1024 by default: either
`sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80` (persist in `/etc/sysctl.d/`) or set
`CLARITY_HTTP_PORT`/`CLARITY_HTTPS_PORT` (ACME then only works if those are what the internet reaches).

Trusting the local CA (only for the internal-CA case):

```sh
podman compose -f deploy/compose.yaml cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

Install that file on the client (Android: Settings > Security > Encryption & credentials > Install a certificate > CA certificate).
Release builds of the app only trust the system store plus user CAs if the app's network security config allows it; for anything
beyond local testing use a real domain.

## Enrolling the phone

The backend generates an enrollment token on first boot, writes it to `/data/enrollment-token` (mode
0600) and logs it once:

```sh
podman compose -f deploy/compose.yaml logs backend | grep -i enrollment
podman compose -f deploy/compose.yaml exec backend cat /data/enrollment-token
```

In `tofu` mode (default) the first device to enroll while none exists needs no token; use
`CLARITY_ENROLLMENT_MODE=token` to always require it. Reach the backend from the phone through Caddy (above); the plain-HTTP port is published on loopback only (`CLARITY_BIND`).

## Adding a connector

1. Build the connector (see `connectors/README.md`) and publish or build its image.
2. `./deploy/init-secrets.sh <id>` creates `secrets/connector_<id>_key`.
3. In `compose.yaml` copy `connector-example`: its own volume, its own `connector-<id>-egress`
   network, the secret mounted as `connector_key`, networks `[ingest, connector-<id>-egress]`.
4. Mount the **same secret** into `backend` as `connector_<id>_key`: the backend registers every
   `/run/secrets/connector_<id>_key` at boot.
5. Approve the connector's nouns in the app (or set `CLARITY_CONNECTORS_AUTO_GRANT=true` for dev).

## Security model

- Networks: `db`, `ingest`, `tools`, `proxy` are `internal` (no route off the host). Each service that needs the
  internet gets its own egress network. Postgres is only on `db`; connectors only on `ingest` plus
  their own egress, so they cannot reach the database, the browser or each other.
- Containers run as non-root numeric users with a read-only root filesystem (writable `/tmp` tmpfs and
  their data volume), `cap_drop: ALL` and `no-new-privileges`.
- Secrets are Podman secrets (`podman secret ls`), mounted read-only at `/run/secrets/<name>`;
  nothing secret appears in environment variables or `inspect` output. The plaintext copies live in
  `deploy/secrets/` (mode 0600, gitignored) as the source of truth for backups.

## Rootless Podman notes

- Secrets are declared `external: true` and created by `init-secrets.sh`. podman-compose only
  honours `uid/gid/mode` and custom targets for non-external secrets, and bind-mounting the 0600 files
  fails for non-root container users (user-namespace remapping, SELinux labels). Podman secrets mount
  as `0444` at `/run/secrets/<name>`, which is why the names carry no prefix (the backend discovers
  `connector_<id>_key` by file name). Real `docker compose` does not support this; use Podman.
- The published backend port needs the backend to be on at least one non-internal network
  (`backend-egress`), otherwise port forwarding has nothing to attach to.

## Teardown

```sh
podman compose -f deploy/compose.yaml down        # keep data
podman compose -f deploy/compose.yaml down -v     # also delete volumes (database, enrollment token, connector state)
```

## Production: Quadlet (planned)

For a systemd host, translate the stack to Quadlet units in `~/.config/containers/systemd/`:
`clarity.network`/`clarity-db.network` (`Internal=true`) and egress `.network` files, `*.volume`
files, one `.container` per service (`Image=`, `Network=`, `Secret=name,type=mount,uid=10001,mode=0400`,
`ReadOnly=true`, `DropCapability=ALL`, `NoNewPrivileges=true`, `HealthCmd=`, `Notify=healthy`,
`Requires=`/`After=postgres.service`), plus `AutoUpdate=registry` and `podman-auto-update.timer` for
image rollouts. `podlet compose deploy/compose.yaml` can generate a first draft.
