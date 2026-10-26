# Workstream `computers`

Lane: `computers/**` (new), `settings.gradle.kts` (add `:computers:sandbox`), catalog,
`deploy/compose.yaml` (replace the Playwright `browser` service, add `sandbox`, profile
`computers`), `deploy/container/*`, `deploy/README.md`, CI/publish workflows (add the images),
backend config for MCP servers only if the existing `CLARITY_MCP_SERVERS` is not enough.

Read "The agent's computers" in `ARCHITECTURE.md` and the current `browser` service in compose.

## Sandbox (`computers/sandbox`, Kotlin)

- `sandboxd`: an MCP server (MCP Kotlin SDK, streamable HTTP, Ktor) on `:8940`, path `/mcp`.
  Auth: a bearer key from a Podman secret (`sandbox_key`), mounted into backend and sandbox;
  extend the backend's MCP client config to send it (`CLARITY_MCP_SERVERS` entry with a key file,
  e.g. `sandbox=http://sandbox:8940/mcp;keyFile=/run/secrets/sandbox_key`, keep the old format working).
- Tools (descriptions written for the model, errors that say what to do next):
  `shell` (bash -lc, cwd under `/workspace`, timeout default 60 s max 600 s, combined output cap
  32 KB with head+tail and a note where the full log was saved under `/workspace/.logs/`),
  `read_file` (line ranges), `write_file`, `edit_file` (exact replace, fails if the text is not
  unique or the file changed since last read), `search_files` (ripgrep), `list_files`.
  Every path is resolved and must stay inside `/workspace` (reject symlink escapes).
- Process hygiene: each `shell` call runs in its own process group, killed on timeout; at most
  4 concurrent commands; environment scrubbed (no secrets).
- Image: Fedora or Debian slim + JRE 25 + bash, coreutils, findutils, ripgrep, jq, git, python3,
  pandoc, poppler-utils, sqlite. Non-root uid, read-only rootfs, `/workspace` volume, `/tmp`
  tmpfs, `pids_limit`, memory 1 GB, CPU 1. **No egress network**: only `tools`.
- Tests: path containment (incl. symlinks and `..`), output truncation, timeout kill, edit
  conflict detection, MCP tool listing over an in-process client.

## Browser (`computers/browser`, container only)

- Firefox ESR (pinned) + Mozilla's Firefox DevTools MCP server (`@mozilla/firefox-devtools-mcp`,
  pinned exact version; read its README first for flags: headless, profile path, BiDi/Marionette)
  on Node (pinned LTS).
- **Profile** built at image build time with **uBlock Origin** and **Privacy Badger** installed
  (signed XPIs from addons.mozilla.org at pinned versions, verified by sha256 in the
  Containerfile; installed as `extensions/<addon-id>.xpi` in the profile, plus `user.js`
  prefs: no telemetry, no first-run pages, no password saving, `extensions.autoDisableScopes=0`
  so sideloaded add-ons are enabled, a fixed downloads dir). Copy the profile to a tmpfs on
  start so every container start is clean (`--isolated`-like); allow an optional persistent
  profile volume behind an env flag.
- **Transport**: if the server speaks streamable HTTP, use it; otherwise wrap stdio with a small,
  maintained bridge (`supergateway` or similar, pinned) or a tiny Kotlin stdio↔HTTP bridge
  using the MCP Kotlin SDK. Bind `0.0.0.0:8931`, path `/mcp`, same bearer-key scheme as sandbox
  if the bridge supports it (else the `tools` network isolation is the boundary; say so).
- Verify inside the container (a script under `computers/browser/smoke.sh` run in CI after
  build): Firefox starts headless, both add-ons are active (check `about:support` JSON or the
  extensions in the session), the MCP `tools/list` works, a snapshot of a local `file://` test
  page returns element uids.
- Hardening: non-root, read-only rootfs with tmpfs for profile/cache/tmp, `shm_size`, memory
  1.5 GB, `browser-egress` + `tools` networks only.
- Document in `deploy/README.md`: what the browser can reach, how to watch it (optional later:
  a noVNC live view profile, not in this workstream).

## Compose / CI

- Profile `computers` brings up both; default `CLARITY_MCP_SERVERS` documented in `.env.example`.
- Add both images to `publish.yml` and a build smoke test to `ci.yml` (browser smoke script).
- Build each image once locally to prove it works; run the smoke script; report image sizes.
