# Deploying Clarity

One person, one stack: Postgres, the backend, optional connectors and the agent's optional computers (sandbox and browser),
wired with least-privilege networks and Podman secrets. Rootless Podman is the reference runtime.

## Quick start

```sh
./deploy/init-secrets.sh                          # generates deploy/secrets/* and loads them as Podman secrets (idempotent)
echo -n 'sk-or-...' > deploy/secrets/openrouter_api_key  # required: every model runs on OpenRouter
./deploy/init-secrets.sh                          # re-run to load the key
cp deploy/.env.example deploy/.env                # optional overrides

podman compose --env-file deploy/.env -f deploy/compose.yaml up -d
curl http://127.0.0.1:8080/healthz                # process is up
curl http://127.0.0.1:8080/readyz                 # database reachable
```

Images are pulled from `ghcr.io/robog-two/clarity-*:${CLARITY_TAG:-latest}`; to build locally instead
use `up -d --build` (the `build:` sections use `deploy/container/jvm-app.Containerfile`).

## First run with the app

1. Start the stack (above) and make the backend reachable from the phone through Caddy (see "TLS").
2. Open the app and enter the server address. If the instance is in the default `tofu` mode the first device
   enrolls without a code; otherwise enter the enrollment token (see "Enrolling the phone").
3. The app checks that the server has its OpenRouter key (`deploy/secrets/openrouter_api_key` or
   `OPENROUTER_API_KEY`). The key is never entered on the phone; the backend does not start without it.
4. Connect sources: the connectors you started (`--profile mail`, `dav`) show up in the app with a settings form;
   approve the nouns they may push. Calendar, reminders and messages on the phone itself are a connector inside the app.
5. Do the values interview (it becomes constitution v1) and set the morning call time.

## Secrets

`./deploy/init-secrets.sh [connector-id ...]` writes the values to `deploy/secrets/` (mode 0600, gitignored; the
source of truth for backups) and loads each into Podman's secret store under the same name. Compose declares them
`external` and mounts them read-only at `/run/secrets/<name>`.

| Secret | Used by | Notes |
|---|---|---|
| `postgres_password` | postgres, backend | generated |
| `openrouter_api_key` | backend | yours; required (an empty file falls back to `OPENROUTER_API_KEY`). Every model call goes to OpenRouter, zero data retention |
| `connector_<id>_key` | backend and that connector | generated; the backend registers every one it finds. `example`, `mail`, `dav`, `mcphost` always |
| `sandbox_key`, `browser_key` | backend and the matching computer | bearer keys for the `computers` profile |
| `render_key` | backend and `render` | generated; bearer key for the report renderer |
| `mcphost_key` | backend and `mcp-host` | generated; bearer key for the MCP host (`CLARITY_MCP_HOST`) |

The script is idempotent and only replaces a Podman secret when its file changed; containers pick the new value up
the next time they start.

| Profile | Adds | Enable |
|---|---|---|
| (none) | `postgres`, `backend`, `egress` (outbound proxy), `caddy` (TLS), `render` (reports: pandoc + Typst), `mcp-host` (the MCP servers you add in the app) | |
| `example` | `connector-example` (pushes one sample message when `emitSample` is on) | `--profile example` |
| `mail` | `connector-mail` (IMAP sync, SMTP send, drafts) | `--profile mail` |
| `dav` | `connector-dav` (CalDAV events and reminders, CardDAV contacts, WebDAV files) | `--profile dav` |
| `computers` | `sandbox` (Linux workspace) and `browser` (Firefox), the agent's tools | `--profile computers`, and set `CLARITY_MCP_SERVERS` (see below) |

### Mail and calendar accounts

One `mail` container and one `dav` container serve every account you add: in the app, open the source and add
accounts (a name, the servers, your login). Presets fill in the servers for iCloud, Fastmail and Gmail (mail) and for
iCloud, Fastmail and Nextcloud (calendar and contacts). iCloud, Fastmail and Gmail need an app-specific password, not
your account password (Gmail only offers them with 2-step verification on). Each account syncs on its own, so a
wrong password on one does not stop the others, and "test connection" reports every account by name.
A new email is sent from the account whose address is the sender, else from the account that received the message
it answers, else from the first account. A new event goes to `<account name>/<calendar>`, to the first account that
has `<calendar>`, or to the first account's default calendar.

A configuration saved before accounts existed (flat fields such as `imapHost` and `password`) keeps working as one
account named `default`.

## The agent's computers (`--profile computers`)

Two MCP servers on the internal `tools` network that the backend connects to:

| Service | What it is | Reaches | Persists |
|---|---|---|---|
| `sandbox` | `sandboxd` (Kotlin): `shell`, `read_file`, `write_file`, `edit_file`, `search_files`, `list_files` in a Linux workspace (bash, coreutils, ripgrep, jq, git, python3 with pip/venv, pandoc, poppler-utils, sqlite3, curl, wget) | the public internet through the `egress` proxy (never your LAN), and `tools` | the `sandbox-workspace` volume (`/workspace`); reads the browser's downloads at `/workspace/downloads` |
| `browser` | Headless Firefox ESR with uBlock Origin and Privacy Badger, driven by Mozilla's Firefox DevTools MCP server (snapshots with element uids, clicks, typing, screenshots) | the public internet through the `egress` proxy (never your LAN), and the backend through `tools`; no database, connectors or sandbox | downloads only (the `browser-downloads` volume, kept 7 days); the profile is rebuilt on tmpfs at every start |

Setup:

```sh
./deploy/init-secrets.sh        # also creates the sandbox_key and browser_key bearer keys
# deploy/.env:
CLARITY_MCP_SERVERS=sandbox=http://sandbox:8940/mcp;keyFile=/run/secrets/sandbox_key,browser=http://browser:8931/mcp;keyFile=/run/secrets/browser_key
podman compose --env-file deploy/.env -f deploy/compose.yaml --profile computers up -d
```

Each entry is `name=url[;keyFile=/path]`: the backend reads the key file and sends it as `Authorization: Bearer ...`
on every call; both servers refuse unauthenticated requests. The key secrets are mounted into the backend and
into the one server that owns them.

Things worth knowing:

- **The sandbox reaches public websites through `egress`**, under the browser's policy (ports 80/443, never your
  LAN, the host or other containers), via `http(s)_proxy` in each command's environment (`curl`, `wget`, `pip`,
  `git`). To take it offline, remove `SANDBOX_HTTP_PROXY` and the `sandbox-proxy` network from its service.
- **Browser downloads reach the sandbox.** Firefox saves every download without asking to the `browser-downloads`
  volume, which the sandbox mounts read-only at `/workspace/downloads` (so files needing a browser login can be
  processed in the shell). Files older than `BROWSER_DOWNLOADS_DAYS` (7) are deleted when the browser starts. Commands run in their own process group (killed on
  timeout, default 60 s, max 600 s), at most 4 at a time, with a scrubbed environment (no secrets). The full output
  of long commands is kept in `/workspace/.logs/`. Limits: 1 GB RAM, 1 CPU, 512 pids.
- **What the browser can reach**: any public website on port 80/443, since that is its job, and nothing else:
  your LAN, the host and your other containers are refused by the `egress` proxy (see below). Its profile is kept on the `browser-profile` volume
  (`BROWSER_PROFILE_DIR`), so cookies and site logins survive a restart; saved passwords, form data and session
  restore stay off. Delete the volume to start with an empty browser. uBlock Origin and Privacy Badger are baked
  in at pinned versions, with telemetry, updates, password saving and first-run pages switched off.
- **Firefox's own sandbox stays on.** It needs the `SYS_CHROOT` capability, which the browser service adds back
  (to nothing else); every other capability is dropped and `no-new-privileges` is set.
- **Transport**: the Firefox DevTools MCP server only speaks stdio, so `supergateway` (pinned) exposes it as
  streamable HTTP and enforces the bearer key. Sessions are stateful: one Firefox per MCP session, released after
  6 idle hours (`BROWSER_SESSION_TIMEOUT_MS`), so open pages survive a helper waiting for an approval.
- **Watching the browser** live (noVNC) is not included; use `screenshot_page` through the agent for now.
- Images are built from `deploy/container/sandbox.Containerfile` and `deploy/container/browser.Containerfile`
  (`up -d --build`). The browser build runs Firefox once for about 80 s to pre-warm the profile.
  Smoke tests: `computers/sandbox/smoke.sh` and `computers/browser/smoke.sh` (they need podman and curl).

## MCP servers

`mcp-host` installs and runs the MCP servers you add in the app (Settings → MCP servers, presets first) and serves
each one to the backend. It is part of the default stack and needs no setup: `init-secrets.sh` creates its two keys
(`mcphost_key`, `connector_mcphost_key`) and the backend's `CLARITY_MCP_HOST` points at it by default. With no servers
added it idles at about 70–100 MB and makes no outbound requests.

To turn it off, set `CLARITY_MCP_HOST=off` in `deploy/.env` (the backend then lists no third-party servers) and,
to skip the container too, stop it after each `up` with `podman compose -f deploy/compose.yaml stop mcp-host`.

- **Kinds**: an npm package, a Python package, a GitHub release binary (checked against its `sha256` before it is
  kept), or a remote URL (https; the host forwards to it with the token as `Authorization: Bearer`). Versions are
  exact; nothing updates on its own, and changing `version` reinstalls. npm installs run with `--ignore-scripts`,
  Python ones with `uv pip install --no-build` (wheels only), so no package code runs while installing.
- **Built in**: kind `builtin` runs a first-party server shipped in the image (`computers/mcphost/builtin`, Python
  standard library only, versioned with the image). Today that is Asana (the "Asana" preset): it takes a personal
  access token (Asana → profile photo → My settings → Apps → Manage developer apps → Create new token) in
  `ASANA_ACCESS_TOKEN`, optionally `ASANA_WORKSPACE=<gid>`, and reaches only `app.asana.com`, through the proxy. It runs
  like any other server (own uid, home, proxy login, lifecycle). The token can do anything the Asana account can;
  its reads run on their own, its writes (create, update, complete, comment, move; no delete) ask first by default.
- **Presets**: GitHub (release binary), Canvas (PyPI, set up for a student and read only: `CANVAS_ROLE=student` and
  `ALLOWED_WRITE_TOOLS=none` remove every tool that writes; set `TIMEZONE` to your zone) and Asana (built in).
- **One uid each**: every server installs and runs as its own uid with its own home (`/data/servers/<id>`, 0700). It
  sees only its own token, in its own environment; it cannot read another server's files or environment, the
  host's keys or its state. The supervisor runs as root inside the rootless container with only `SETUID`, `SETGID`,
  `CHOWN` and `KILL`; children start through `setpriv --no-new-privs --clear-groups` with no capabilities.
- **Egress per server**: `mcp-host` is on `mcphost` (shared only with the backend), `ingest` (it is a connector) and
  `mcphost-proxy`, never `db` or your LAN. Each server reaches the internet only through `egress`, logged in as its
  own proxy user, and only to the hosts in its "Hosts it may reach" list (installs get the registry's hosts for the
  install only). The logins (SHA-256 only) and allow-lists are files in the `mcphost-egress` volume, which `egress`
  mounts read-only and re-reads on every lookup. A server that ignores `HTTPS_PROXY` simply has no way out.
- **Lifecycle**: right after an install the host starts the server once, lists its tools (cached; the app's
  settings screen never starts a server), and stops it. A server then starts on its first request, stops after 10
  minutes without one, at most 3 run at once (the least recently used stops for a fourth), and a crash is retried on
  the next request after 1 minute, doubling to 30. The whole container is capped at 640 MB.
- Logs: `podman logs <project>_mcp-host_1` (a server's stderr appears with its name; tokens and proxy passwords are
  replaced by `•••`).
- Smoke test: `computers/mcphost/smoke.sh [mcphost-image] [egress-image]` runs its own compose project with a fake
  server and checks the isolation and the egress rules; `SMOKE_PRESETS=1` also installs the presets.
  `computers/mcphost/smoke-asana.sh [mcphost-image]` runs the built-in Asana server in the image against a fake
  Asana behind a fake proxy (nothing leaves the machine) and prints its memory.

## Reports (`render`)

The agent writes documents ("reports", the app's Goods) as Markdown plus a style; they download as PDF, Word / Pages,
OpenDocument, plain text or Markdown from each Good's secret link (below). The backend renders each format on first download through `render` and keeps
the file under `/data/reports` (the `backend-data` volume).

| Service | What it is | Reaches | Persists |
|---|---|---|---|
| `render` | `renderd` (Python standard library) with pinned pandoc 3.12.1 and Typst 0.15.1 (sha-256 verified) and the Liberation fonts, on Alpine; `POST /render` with the bearer key, `GET /healthz` | nothing: only the internal `render` network, which only the backend shares | nothing (each render works in `/tmp`, a tmpfs) |

- **Fonts**: Word and OpenDocument files name the family the agent chose (Times New Roman by default), so Word and
  Pages use the real font. Containers cannot ship Times New Roman, so PDFs use Liberation Serif (metric-compatible:
  same line breaks and page count), Liberation Sans for Arial/Helvetica and Liberation Mono for Courier New.
- **Safety**: no network at all; a Lua filter drops raw Word/ODF/Typst/HTML code, images (pandoc would read the file
  they name) and front-matter keys other than title, subtitle, author, date, abstract, lang and keywords. Each render
  runs in its own process group and is killed after `RENDER_TIMEOUT` (60 s); at most 2 run at once; 768 MB RAM,
  128 pids, read-only root.
- **Cache**: `CLARITY_REPORTS_CACHE_MAX_MB` (500) caps the rendered files; an hourly job drops files of an older
  renderer version, then the least recently downloaded. Only rendered files go, never the reports; deleting
  `/data/reports` is always safe.
- **Settings**: `CLARITY_RENDER_URL` (`http://render:8950`), `CLARITY_RENDER_KEY_FILE` (`/run/secrets/render_key`),
  `CLARITY_RENDER_TIMEOUT` (60s; the backend waits 10 s longer than that).
- **Upgrading an existing stack**: re-run `./deploy/init-secrets.sh` first. It creates any key the new version
  mounts (`render_key`, `mcphost_key`, `connector_mcphost_key`), and the backend does not start without them.
- **Fonts**: the image bundles Libre Caslon Text (OFL, `computers/render/fonts/`; static faces are cut at build with
  fonttools) for the default title and headings. Word and OpenDocument downloads embed it (about 200 KB per file).
- **Secret links**: each Good can have one public download page, `https://<your domain>/g/<token>`, made when the
  person first taps Download or Share in the app and kept until they turn it off. It is the only report route that
  needs no device: Caddy forwards everything to the backend, which serves the page and its files for a live token
  and a plain 404 for anything else. Turning a link off (in the app) makes it stop working at once. Request logs
  never include `/g/` paths.
- Image: `deploy/container/render.Containerfile`. Smoke test: `computers/render/smoke.sh [image]` (podman and curl):
  checks the key is required and renders every format from a sample that also tries to embed the key file.

### Web search

Helpers can search the web through OpenRouter's `openrouter:web_search` server tool, but only when the lead names
`web_search` in `delegate`'s tools; the lead itself never searches. Such a helper loses every personal-data and
outbound tool (mail, contacts, calendar, memory, journal, profile, drafts, guarded actions, browser, vault), keeping
only the sandbox and the report tools. The search runs at OpenRouter (zero data retention stays on) and needs no
egress rule. Settings in `deploy/.env`:

| Variable | Default | |
|---|---|---|
| `CLARITY_WEB_SEARCH` | `true` | `false` grants no search at all |
| `CLARITY_WEB_SEARCH_ENGINE` | `parallel` | `parallel`, `exa`, `perplexity`, `native`, `auto`; `firecrawl` is refused (OpenRouter rejects it under zero data retention) |
| `CLARITY_WEB_SEARCH_MODE` | `fast` | the engine's mode (Parallel `fast` is about $0.001 a search); blank = the engine's default |
| `CLARITY_WEB_SEARCH_MAX_SEARCHES` | `10` | per helper run |
| `CLARITY_WEB_SEARCH_MAX_RESULTS` | `5` | per search (1-25) |
| `CLARITY_WEB_SEARCH_EXCLUDED_DOMAINS` | (none) | comma list, sent as the engine's block list |

## Outbound traffic (`egress`)

Compose networks cannot filter destinations, and host firewall rules need root and differ per engine, so the
browser, the sandbox and the backend have **no route to the internet at all**. Their only way out is `egress`, a small Squid
proxy (Alpine, pinned; non-root, read-only rootfs, 128 MB) that enforces the policy itself:

| From | Can reach | Everything else |
|---|---|---|
| `browser` (network `browser-proxy`) | any public host on 80 (HTTP) and 443 (CONNECT) | refused with 403 |
| `backend` (network `backend-proxy`) | `openrouter.ai` and hosts in `CLARITY_BACKEND_EGRESS_ALLOW` (443) | refused with 403 |
| `sandbox` (network `sandbox-proxy`) | any public host on 80 (HTTP) and 443 (CONNECT) | refused with 403 |
| `mcp-host` (network `mcphost-proxy`) | per MCP server, with that server's proxy login: only the hosts listed for it | 407 without a login, 403 for other hosts |
| connectors, `caddy` | their own egress networks (connectors usually talk to servers on your LAN) | |

Refused for both: loopback, RFC 1918, CGNAT, link-local (including the 169.254.169.254 metadata address),
multicast/reserved, IPv6 loopback/ULA/link-local, `localhost`, `host.containers.internal`/`host.docker.internal`
and `*.local`/`*.internal`/`*.lan`/`*.home.arpa`. Squid resolves names itself, so a hostname whose DNS answer is a
private address is refused too; nothing is cached and no `Via`/`X-Forwarded-For` header is added.

- The browser's Firefox profile pins its proxy to `egress:3128` with no bypass list and WebRTC off. The backend
  sends the model API through the proxy with `CLARITY_HTTPS_PROXY=http://egress:3128` (unset = direct, for local dev).
- Each client network gets its own listener, bound to the proxy's address on that network (the `egress-browser` /
  `egress-backend` network aliases), so the backend cannot borrow the browser's policy.
- **Allowing another host for the backend** (for example a self-hosted model gateway): set
  `CLARITY_BACKEND_EGRESS_ALLOW=gateway.example.com,.corp.example.com` in `deploy/.env` (a leading dot allows
  subdomains) and recreate `egress`. It must still resolve to a public address.
- **Allowing a LAN host for the browser** is deliberately not a setting. If you ever need it, edit the `private_dst`
  ACLs in `computers/egress/squid.conf.tmpl`, rebuild the image, and understand that the agent's browser (which reads
  untrusted web pages) will then be able to reach that host.
- Logs: `podman logs <project>_egress_1` shows every request with its verdict (`TCP_DENIED/403` for refusals).
- Smoke test: `computers/egress/smoke.sh [image]` starts the proxy and throwaway client networks and checks all of
  the above (set `SMOKE_BROWSER_IMAGE=...` to also drive the real browser through it). It needs podman, curl-image
  pulls and internet access.

## TLS (Caddy)

`caddy` fronts the backend and provisions/renews its own certificates; nothing else to run.
Clients use `https://<CLARITY_DOMAIN>[:CLARITY_HTTPS_PORT]`. The backend's plain-HTTP port stays on loopback.

| `CLARITY_DOMAIN` | Certificate |
|---|---|
| `localhost` (default), an IP, `*.local`/`*.lan` | Caddy's built-in local CA. Works offline, but clients must trust its root (below). |
| a public DNS name (`clarity.example.com`) | Let's Encrypt / ZeroSSL via ACME. Needs the name pointing at this host and ports 80 and 443 reachable from the internet. Trusted everywhere, including Android release builds. |
| a name you have a certificate for | Your certificate (see "Your own certificate" below); no ACME for that name. |

List several names separated by commas: `CLARITY_DOMAIN="clarity.example.com, clarity.example.net"`. Each one
gets its certificate the way the table says.

### Your own certificate (e.g. behind Cloudflare)

A name proxied through Cloudflare (orange cloud) points at Cloudflare, so ACME cannot validate it through
Caddy, and Cloudflare reports **525** if Caddy has no certificate for the name. Create a Cloudflare Origin
Certificate for it (SSL/TLS -> Origin Server), set the SSL mode to *Full (strict)*, and:

```sh
# certificate and key as two files with the same name (anything ending in .pem / .key)
$EDITOR deploy/secrets/clarity-origin.pem deploy/secrets/clarity-origin.key
./deploy/init-secrets.sh            # bundles them into deploy/secrets/tls/ for Caddy
# add the name to CLARITY_DOMAIN in deploy/.env, then restart caddy
```

Caddy serves a loaded certificate for every name it covers and skips ACME for those names. `deploy/secrets/` is
gitignored. The same works for any certificate you already have (an internal CA, a wildcard).

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
3. In `compose.yaml` copy `connector-mail` (or `connector-example`): its own volume, its own `connector-<id>-egress`
   network, the secret `connector_<id>_key` mounted and `CLARITY_CONNECTOR_KEY_FILE` pointing at it, networks `[ingest, connector-<id>-egress]`.
4. Mount the **same secret** into `backend` as `connector_<id>_key`: the backend registers every
   `/run/secrets/connector_<id>_key` at boot.
5. Approve the connector's nouns in the app (or set `CLARITY_CONNECTORS_AUTO_GRANT=true` for dev).

## Security model

- Networks: `db`, `ingest`, `tools`, `mcphost`, `render`, `proxy`, `browser-proxy`, `backend-proxy`, `sandbox-proxy`, `mcphost-proxy` are `internal` (no route off the
  host). `render` joins only the backend and the report renderer, which has no other network. The browser and the backend reach the internet only through the `egress` proxy; the other services that
  need it (caddy, connectors) get their own egress network. Postgres is only on `db`; connectors only on `ingest` plus
  their own egress, so they cannot reach the database, the browser or each other. The sandbox sits on `tools` and `sandbox-proxy` (its door to `egress`).
  `mcp-host` sits on `mcphost` (only the backend is there), `ingest` and `mcphost-proxy`, so its servers cannot reach
  the sandbox or the browser. They can open connections to the backend's API and ingest ports, which need a device or
  connector key they do not have.
- Containers run as non-root numeric users with a read-only root filesystem (writable `/tmp` tmpfs and
  their data volume), `cap_drop: ALL` and `no-new-privileges`. Two exceptions keep one capability set each:
  `caddy` (`NET_BIND_SERVICE`) and `mcp-host` (root in its user namespace with `SETUID`, `SETGID`, `CHOWN`, `KILL`,
  to give each MCP server its own uid).
- Secrets are Podman secrets (`podman secret ls`), mounted read-only at `/run/secrets/<name>`;
  nothing secret appears in environment variables or `inspect` output. The plaintext copies live in
  `deploy/secrets/` (mode 0600, gitignored) as the source of truth for backups.

## Rootless Podman notes

- Secrets are declared `external: true` and created by `init-secrets.sh`; bind-mounting the 0600 files
  fails for non-root container users (user-namespace remapping, SELinux labels). Podman secrets mount
  as `0444` at `/run/secrets/<name>` by default, which is why the names carry no prefix (the backend discovers
  `connector_<id>_key` by file name). `mcp-host` sets `mode: "0400"` on its two (podman-compose passes `mode`
  on for external secrets; quote it, or YAML reads 0400 as the number 256), because its servers run as other
  uids in the same container; it refuses to start when a key file is readable by others. Real `docker compose` does not support this; use Podman.
- The backend is only on internal networks (its outbound traffic goes through `egress`). Publishing
  `127.0.0.1:8080` works for it on rootless Podman 5 (pasta); if your engine cannot forward ports to a container
  that is only on internal networks, reach the backend through Caddy instead.

## Checking that it works

- `podman compose -f deploy/compose.yaml ps` shows `healthy` for postgres, backend and egress.
- With `CLARITY_MCP_SERVERS` set, the backend log lists each tool server's tools at boot
  (`MCP server 'sandbox': N tools (...)`) or warns that it is unreachable.
- `e2e/run.sh` does all of this for you on a throwaway copy of the stack: its own compose project (`clarity-e2e`),
  throwaway `e2e_*` secrets (a throwaway OpenRouter key, with `OPENROUTER_BASE_URL` on a closed loopback port so no model
  call leaves the machine), loopback port 18080. It enrolls a device, reads the setup status,
  registers and grants the example connector and checks the record arrives, reads the feed over SSE, checks the
  sandbox and browser tool listings and that the egress proxy refuses LAN addresses from the browser's network,
  then removes everything. It does not touch a running `clarity` stack, and does not start Caddy. It is the `e2e`
  job in CI. `E2E_SKIP_BROWSER=1` leaves out the browser (about 1.5 GB of RAM).

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
