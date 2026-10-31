# Workstream `mcp`

Any MCP server the person wants (GitHub, Canvas, Asana, ...) as a source of tools for Clarity,
added from the phone, run on the person's own server, with writes approved, per-server memory,
and a small read-only sweep before the morning call.

Three lanes run in parallel and share the contract below. Do not change the contract on your own.
If it does not work for you, stop and report.

- **`host`**: `computers/mcphost/**` (new), `deploy/container/mcphost.Containerfile` (new),
  `computers/egress/**`, `deploy/container/egress.Containerfile`, `deploy/compose.yaml`,
  `deploy/init-secrets.sh`, `deploy/README.md`, `settings.gradle.kts` (include), `.github/workflows/**`
  (build/publish entries for the new image).
- **`backend`**: `backend/**`, `ARCHITECTURE.md` (MCP sections), additive `shared/**`.
- **`app`**: `android/**`.

`shared/.../shared/mcp/Mcp.kt` (added with this doc) holds the app ↔ backend types. Use it as is.

## Principles that decide the hard cases

- **Requests come from the person's server.** A server that runs locally reaches the internet only
  through the egress proxy, and only the hosts listed for it. A remote server is reached by the host
  container through the egress proxy. Nothing runs on anyone else's infrastructure because of this
  feature, except the remote MCP servers the person chooses to add.
- **Data stays in Postgres.** Tool results go into journals like other tool results. No new place
  data leaves the server except the MCP servers themselves, which are the person's own accounts.
- **Unknown code is untrusted.** A server is third-party code with the person's token. It must not
  reach the backend's database, other servers' tokens or files, the LAN, or hosts it was not given.
- **Writes are approved.** Only tools the server marks read-only run without asking, unless the
  person chooses otherwise per tool.
- **Never post to the chat.** Suggestions live in Settings; the sweep's findings go to the morning call.
- **Resources:** one container however many servers; servers run only while used.

## Contract 1: the `mcp-host` connector and its config schema

`mcp-host` is a connector built on `connector-sdk` (kind `mcp-host`, display name "MCP servers").
It sends a manifest with a `sealingKey`, produces no nouns and handles no actions. Its config uses
the conventions in `connector-accounts.md` (collections, presets, sealed item secrets).

Top level: `{ "servers": <collection> }`, `minItems: 0`, `x-item-title: "label"`,
`x-item-subtitle: "package"`. Item properties, in this order:

| Property | Schema | Notes |
|---|---|---|
| `id` | string, readOnly | app-generated |
| `label` | string, required | "GitHub". Also the tool prefix and memory collection name |
| `description` | string | One line the lead sees: "Your GitHub repos, issues and PRs" |
| `enabled` | boolean, default true | |
| `kind` | enum `npm`, `pypi`, `github-release`, `remote`; titles "npm package", "Python package", "GitHub release binary", "Remote URL" | |
| `package` | string | npm/PyPI name, or `owner/repo` for `github-release` |
| `version` | string | Exact version or tag; ranges and `latest` are rejected |
| `asset` | string | `github-release`: the asset file name |
| `sha256` | string | `github-release`: required; checked before the binary is kept |
| `command` | string | Optional executable to run (npm/PyPI bin name, or the binary inside an archive) |
| `args` | string array | |
| `env` | string array | `KEY=value`, not secret |
| `secretEnv` | string | Env var that receives `secret` ("GITHUB_PERSONAL_ACCESS_TOKEN") |
| `secret` | string, `x-secret` | For `remote`: sent as `Authorization: Bearer` |
| `secretEnv2` | string | |
| `secret2` | string, `x-secret` | |
| `url` | string, `format: uri` | `remote` only, https only |
| `allowHosts` | string array | Hosts the server may reach (`api.github.com`). For `remote`, the URL's host is added |

The schema marks fields that only apply to some kinds with `x-visible-when` (see
`connector-accounts.md`), so the app shows a short form per kind: `package`, `version`, `command`, `args`, `env`,
`secretEnv`, `secretEnv2` and `secret2` for `npm`, `pypi` and `github-release`; `asset` and
`sha256` for `github-release`; `url` for `remote`. Every other field (including `secret`) is shown
for all kinds. `kind` is required. The host's validation does not depend on these hints.

The host validates each item and reports problems per server with a clear `ConfigIncomplete`
style message ("Canvas: version must be exact, not ^1.2"). One bad item never stops the others.

**Presets** (`x-presets`): only packages you have verified exist at that exact version and work
through an HTTP proxy (see Contract 3). Look them up with `npm view`, the PyPI JSON API or the GitHub
releases API; never guess names, versions or hashes. Candidates: GitHub's official
`github/github-mcp-server` release binary (restrict toolsets with its env, e.g. repos, issues,
pull requests, notifications); a maintained Canvas LMS server; a maintained self-run Asana server
using a personal access token. Leave a candidate out and say so in your report when you cannot
verify it. Each preset's `description` tells the person where to create the token and which scopes
to give it (read-only scopes when the service has them).

## Contract 2: host ↔ backend

The host listens on `:8950` on the internal `mcphost` network (backend and host only). Every request needs
`Authorization: Bearer <key>` (`/run/secrets/mcphost_key`, created by `init-secrets.sh`). Compare in
constant time. The backend reads `CLARITY_MCP_HOST` = `http://mcp-host:8950;keyFile=/run/secrets/mcphost_key`
(the compose default, so a fresh deploy has it; `off` or blank means no third-party servers and
everything else keeps working).

- `GET /servers` → `[{id, label, description, state, error, source, allowHosts, tools, listedAt}]`.
  `state` uses `McpServerState` spellings. `tools` is the host's **cached listing**: right after a
  successful install the host starts the server once, calls `tools/list`, stores the result under
  `/data` and stops it again. Each tool is the raw MCP tool object (name, title, description,
  inputSchema, annotations). The settings screen therefore never starts a server.
- `/s/{id}/mcp`: streamable HTTP MCP for that server. The first request starts it if needed.
  For `remote`, the host forwards to the URL itself (through the egress proxy, with the bearer
  secret) so the backend sees every server the same way.
- Labels are unique, case-insensitively. The tool prefix is `mcp_<slug(label)>__` (slug: lowercase,
  `[a-z0-9_]`, at most 24 chars).

## Contract 3: isolation and egress

- **Per-server user.** Each server installs and runs as its own uid with its own home under
  `/data/servers/<id>` (mode 0700). No other server can read its files, its process environment or
  its token. The supervisor holds the connector's private key and opens secrets; a server sees
  only its own decrypted values, in its own environment. Use the smallest set of capabilities that
  makes uid switching work (for example root inside a rootless container with `cap_drop: ALL`
  plus `SETUID`/`SETGID`, children started with `setpriv --no-new-privs --clear-groups` and no
  inherited capabilities) and explain the choice in the code and your report.
- **Per-server egress.** The host container's only route out is the egress proxy (new internal
  network `mcphost-proxy`, alias `egress-mcp`). The proxy allows a server only the hosts listed
  for it, only on public addresses (the existing deny list), ports 80/443 (CONNECT 443 only), for
  example with a proxy username and password per server mapped to a per-server allow-list file.
  Installs use the same mechanism with the registry hosts for that kind (npm, PyPI, GitHub
  releases) and only for the install. A server with no proxy support fails closed. For Node, set
  `NODE_USE_ENV_PROXY=1` and use a Node version that honours it.
- **Pinned installs.** Exact versions only; npm always with `--ignore-scripts`; PyPI into a per-server venv with `uv` or `pip` pinned;
  release binaries checked against `sha256`. Nothing updates on its own; changing `version` reinstalls.
- **No other reach.** The host is on `mcphost` (only the backend is there), `ingest` (connector-sdk) and
  `mcphost-proxy`. Not on `db`, never the LAN.
- **Container limits.** Read-only root filesystem, tmpfs for /tmp, a `/data` volume,
  `mem_limit: 640m` for the whole container, `pids_limit`. The supervisor's JVM is tuned small
  (Serial GC, small heap); target under 100 MB resident when idle.
- **Lifecycle.** At most 3 servers running at once (the least recently used one stops for a
  fourth); a server stops after 10 minutes without a request; a crash marks it `failed` with a
  short error and it is retried on the next request after a 1 minute backoff (doubling to 30 min).
- Logs never contain secrets or proxy passwords (pass them by env or file, never on a command line).

## Contract 4: backend behaviour

- **Sources.** Third-party servers come from `CLARITY_MCP_HOST`, listed at most every 60 s, each
  through the existing `McpToolProvider` at `/s/{id}/mcp`. The static `CLARITY_MCP_SERVERS`
  (sandbox, browser) is unchanged.
- **Who sees what.** The lead never gets third-party tools. It gets a short index ("Your MCP
  servers: GitHub — your repos, issues and PRs; ...") as a system message only when it changes,
  like the collection index. `delegate` gains `servers: [label]`; a helper gets the tools of those
  servers only, never all third-party tools by default. Tools whose mode is `off` are never sent.
- **Modes** (`McpToolMode`): default `auto` when `annotations.readOnlyHint == true`, else `ask`.
  The person's choice is stored per (server id, tool name) in Postgres (new Flyway migration) and
  served/changed by the `McpPaths` routes (device auth). An `ask` call becomes a
  `ProposedAction.ExternalTool` with `server = "mcp:<id>"`, held for approval exactly like other
  approved actions (the same held card, the same `/api/v1/held` flow; the summary names the server,
  the tool and the key inputs). The backend performs the call itself once approved and the result
  reaches the helper (or its inbox) the way the browser's approved steps do. Approval is never
  remembered across calls unless the person sets the tool to `auto`.
- **Untrusted results.** Third-party results are quoted as untrusted and taint the run, like web
  pages. They go through the existing large-result handling.
- **Memory per server.** When a server is first listed, ensure a memory collection named after its
  label ("Notes for using <label>: names, IDs, formatting rules, how to find what is coming up").
  A helper given a server also gets `open_collection` and `search_memory`, and `save_note`
  limited to that server's collection. The existing secret detector applies. The daily memory
  tidy may file facts from the journal into these collections like any other.
- **Morning sweep.** A durable job `mcp-sweep` runs 30 minutes before the morning call prep on
  call days, only when at least one enabled server has an `auto` tool. It starts one helper on the
  helper model with only `auto` tools of all enabled servers (never `ask` tools), the server
  collections, and a per-run cap of $0.03 and 12 tool calls. It looks for what is coming up in the
  next 7 days. It follows each collection's "Sweep recipe" note when present and updates it when it
  learned a better way. It writes its findings with `set_scratch` (key `coming-up`, TTL 20 h, at
  most 1,200 characters, each line naming its server). The prep instruction tells the lead to use
  it. It never posts to the chat. It records spend like other helpers.
- **Suggestions.** A lead tool `suggest_mcp_server(label, why, preset?)` stores an `McpSuggestion`
  (at most one open per label, at most 3 per 7 days). They are served at `McpPaths.SUGGESTIONS` and
  never posted to the chat. Dismissing deletes the row.

## Contract 5: the app

- **Settings → "MCP servers"** row (with the other sources). It opens a screen listing servers as
  cards: label, description, state chip (`installing`, `idle`, `running`, `failed` with the error,
  `disabled`), source line, tool count ("12 tools · 9 read · 3 change things").
- **Add server** uses the existing connector collection editor for `mcp-host` (presets first,
  then the item form). Editing and removing a server use the same editor. If `mcp-host` is not
  running, show the existing "not running on your server" state.
- **Server detail** shows its tools in two groups, "Reads" and "Changes things", each with a
  three-way segmented control Auto / Ask / Off (`PUT McpPaths.toolPolicy`), a "default" hint when
  not overridden, and a "Reset" action when overridden. Destructive tools get a warning icon.
  Turning a tool that changes things to Auto asks for confirmation.
- **Suggestions** appear at the top of the MCP screen ("Clarity suggests: Canvas — ..."), with
  "Set up" (opens the add flow on that preset) and "Dismiss".
- Material 3 expressive like the rest of Settings; no work on the UI thread; strings in resources.
- **Screenshots:** previews in `app/src/screenshotTest` for: the server list (with one of each
  state), add-server preset picker, the server editor, server detail with both groups, a
  suggestion, and the not-running state; light and dark, phone width. Render them with
  `updateDebugScreenshotTest` and keep the reference PNGs.

## Tests (no real services, no real model calls)

- **host:** a tiny fake stdio MCP server (in the test sources) installed as a local `command`;
  start on demand, idle stop, LRU limit, crash and backoff, cached listing, bearer check, item
  validation, secrets never in logs or argv, per-server proxy credentials and allow-list files,
  one bad item isolated. Build the image and run a smoke test in a **separate compose project**
  (never touch the running `clarity_*` containers): the fake server works through the host, a
  server cannot reach a host not on its list, cannot reach a private address, and cannot read
  another server's home or environment. `./gradlew :computers:mcphost:test` passes.
- **backend:** default modes from annotations; overrides; `ask` holds and runs on approval; `off`
  never sent; helper gets only the requested servers; lead gets the index only when it changes;
  collection created once; `save_note` limited; sweep schedule, tools offered (auto only), cap,
  scratch output, no chat post; suggestion limits; routes. Full `./gradlew :backend:test` passes.
- **app:** JVM tests for the presentation (state chips, groups, counts, policy changes, reset,
  confirmation) and `assembleDebug testDebugUnitTest lintDebug` in `android/` pass.

## Reports

Each lane's final report lists: what was built, contract additions, deviations, and for `host`:
image size, measured memory of the container idle and with one fake server running, and every
security decision with its reason.
