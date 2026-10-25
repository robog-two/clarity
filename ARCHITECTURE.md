# Clarity architecture

Clarity is a **single-tenant, self-hosted, proactive personal assistant**. One deployment
(one compose project / one set of Quadlet units) serves exactly one person. The Android app is
the only user interface; the backend is a headless API.

```
        ┌──────────────┐  device-signed JWT (ES256)   ┌───────────────────────────────┐
        │ Android app  │ ───────────────────────────► │ backend (Ktor)                │
        │ (Compose)    │   /api/v1, /mcp              │                               │
        │  └ phone     │ ── /ingest/v1 (as connector)►│  ingest ─► normalize ─► dedup │
        │    connector │                              │     │                 │       │
        └──────────────┘                              │     ▼                 ▼       │
                                                      │  triggers ──►  agent dispatcher│
 ┌───────────────────┐ connector key (Bearer)         │  scheduler ─►  (harness)      │
 │ connector: imap   │ ─────────────────────────────► │                 │  ▲          │
 │ connector: caldav │   push-only /ingest/v1         │                 │  │ tools    │
 │ connector: ...    │                                │   Postgres ◄────┘  │          │
 └───────────────────┘                                └──────┬─────────────┼──────────┘
   each owns its own volume + sync state                     │             │ MCP
   network: ingest (internal) + own egress                   ▼             ▼
                                                        postgres      browser (Playwright MCP)
                                                       (db network)   (tools network)
```

## Principles

1. **Functional core, imperative shell.** Normalization, dedup keys, trigger matching, auth
   claim validation and the agent loop's decision logic are pure functions over immutable
   data. Effects (HTTP, SQL, clock, model calls) live at the edges behind small interfaces.
2. **Typed errors, not exceptions.** Domain operations return results through Arrow's `Raise`
   (context parameters are stable in Kotlin 2.4) with sealed error hierarchies. Exceptions are
   reserved for bugs/infrastructure failure and are mapped to RFC 9457 problems at the edge.
3. **Contracts live in `:shared`.** Every wire type (ingest, manifest, enrollment, triggers)
   is a `@Serializable` class in `net.robog.clarity.shared`, used by backend, connectors and app.
4. **Least privilege everywhere.** Connectors can only *push* nouns they have been granted, can
   only reach the backend (plus their own upstream on the internet), and never see the database
   or other connectors. The agent only sees data through tools the backend chooses to expose.
5. **Scaffold, don't speculate.** Each extension point exists with one minimal implementation.

## Repository layout

| Path | What |
|---|---|
| `gradle/libs.versions.toml` | The one version catalog (root build, build-logic, android build) |
| `build-logic/` | Convention plugins: `clarity.kotlin-jvm`, `clarity.jvm-app`, `clarity.kotlin-multiplatform` |
| `shared/` | KMP module (`jvm()` target) with wire DTOs. Android consumes the JVM variant. |
| `backend/` | Ktor service: API, ingest, persistence, triggers, scheduler, agent harness, MCP |
| `connector-sdk/` | Library every JVM connector uses: push client, config polling, local state |
| `connectors/example/` | Reference connector proving the contract end to end |
| `android/` | Separate Gradle build (includes the root build for `:shared`) |
| `deploy/` | Podman compose, container build file, secrets bootstrap, Postgres init |
| `.github/workflows/` | CI (dev + PRs) and image publishing to GHCR (main) |

The Android app is a **separate Gradle build** so that container and backend builds never need
the Android SDK. Open `android/` in Android Studio; open the root in IntelliJ.

## Backend (`net.robog.clarity.backend`)

Ktor 3.6 (Netty), kotlinx.serialization, Exposed 1.x DSL (JDBC) + Flyway migrations, HikariCP,
Metro for compile-time DI, Arrow for typed errors.

```
net.robog.clarity.backend
├── Main.kt                 entry point: load config, build graph, start server + background jobs
├── config/                 AppConfig data classes, loaded from application.yaml (+ env / *_FILE secrets)
├── di/                     Metro @DependencyGraph (composition root)
├── http/                   Ktor modules: plugins (serialization, status pages→Problem, call id, logging), routing
├── auth/                   device JWT verification, enrollment, connector key auth, principals
├── persistence/            Database (Hikari+Flyway+Exposed), table objects, transaction helper
├── ingest/                 pipeline: validate → authorize (grants) → normalize → dedup → upsert → publish event
├── dedup/                  pure: per-noun normalization + DedupKey
├── connectors/             connector registry, manifests, grants, config (app-facing management API)
├── triggers/               pure RecordQuery evaluator + trigger store + matcher on ingest events
├── scheduler/              in-process periodic jobs (periodic review every 2h by default)
├── agent/                  AgentDispatcher, AgentTask, run persistence, domain tools (list_messages, ...)
└── mcp/                    exposes the domain tools as an MCP server at /mcp
net.robog.clarity.harness   provider-agnostic-ish agent loop over the Anthropic Messages API + MCP client
```

### HTTP surface

| Route | Auth | Purpose |
|---|---|---|
| `GET /healthz`, `GET /readyz` | none | liveness / readiness (DB reachable) |
| `POST /api/v1/enroll` | enrollment token or TOFU | register a device public key |
| `GET /api/v1/instance` | device | instance info |
| `GET /api/v1/connectors` | device | connector summaries (manifest, grants, last seen) |
| `PUT /api/v1/connectors/{id}/grants` | device | grant/revoke nouns for a connector |
| `GET/PUT /api/v1/connectors/{id}/config` | device | connector configuration (secret values sealed) |
| `PUT /ingest/v1/manifest` | connector | connector announces kind, nouns, config schema |
| `GET /ingest/v1/config` | connector | connector polls its config (`ETag` = version) |
| `POST /ingest/v1/batches` | connector | push `IngestBatch`, get per-record `RecordOutcome` |
| `/mcp` | device | MCP (streamable HTTP) exposing the same domain tools the agent uses |

Connector management (`/api/v1/connectors/**`) is a separate route module + service from the
ingest pipeline: the ingest path only ever *reads* grants.

### Authentication

**Devices** generate an EC P-256 key pair in the Android Keystore (non-exportable). Enrollment
sends the SPKI public key. Every request carries `Authorization: Bearer <jwt>` where the JWT is
signed by the device (`alg=ES256`, `kid=<deviceId>`, `sub=<deviceId>`, `aud=clarity`, `iat`, `exp`,
`jti`; `exp - iat ≤ 300 s`). The backend looks up the key by `kid` and verifies. No server-side
secrets to leak, no refresh tokens, revocation = delete the device row.

**Enrollment** modes (`clarity.enrollment.mode`):
- `tofu` (default): while zero devices exist, the first enrollment is accepted without a token.
- `token`: always requires the enrollment token.
In both modes an enrollment token exists: on first boot the backend generates one (or reads
`CLARITY_ENROLLMENT_TOKEN_FILE`), writes it to `${dataDir}/enrollment-token` (0600) and logs it
once. Additional phones enroll with that token.

**Connectors** authenticate with `Authorization: Bearer <key>`; the backend stores only
SHA-256(key). Provisioned connectors get their key via a Podman secret mounted into both the
connector and the backend (`/run/secrets/connector_<id>_key`; the backend registers every such
file at boot). A connector's ingest is accepted **only for nouns the user granted** (state
`pending` until approved in the app; `clarity.connectors.autoGrant=true` in dev).

**The phone as a connector**: an enrolled device may also push to `/ingest/v1` with its device
JWT; it is treated as connector `device-<deviceId>` with its own grants.

### Ingest, normalization and dedup

`POST /ingest/v1/batches` (≤ 500 records) runs in one transaction:

1. **Idempotency**: `(connector_id, noun, source_id, source_version)` already in `source_record`
   → `Unchanged`.
2. **Grant check** per noun → `Rejected` if not granted.
3. **Normalize** (pure): lowercase/trim emails, phone numbers to digits with leading `+`,
   whitespace-collapse bodies, sort recipients.
4. **Dedup key** (pure, per noun, SHA-256 over a canonical tuple):
   - message: `channel | account | sender | sorted(recipients) | sha256(normalized body)`
   - contact: sorted normalized handles
   - calendar event: `icalUid` if present, else `calendar | title | start | end`
5. **Upsert** the canonical row by `dedup_key` (`INSERT … ON CONFLICT (dedup_key) DO UPDATE`)
   → `Created` or `Merged`; record the `source_record` and update `source_link`
   (`connector, noun, source_id → entity`) so tombstones can find the entity.
6. After commit, publish `Ingested(noun, entityId, outcome, payload)` on an in-process event bus.

Canonical tables hold only the minimal identifying metadata plus the content needed by tools.
Connectors keep their full-fidelity data in their own volumes.

### Triggers and proactivity

- **Triggers** (`agent_trigger`): agent-authored `TriggerSpec`s (noun + `RecordQuery` + instruction).
  The matcher subscribes to ingest events, evaluates queries (pure), and asks the dispatcher to
  run an agent with the instruction and the matching record.
- **Scheduler**: in-process periodic jobs with persisted last-run (`job_run`) so restarts do not
  double-fire. Default job: `periodic-review` every 2 h — summarize new data, revisit projects
  waiting on information. No external cron, no Anthropic-side scheduling.
- **Dispatcher**: bounded concurrency, persists `agent_run` (reason, status, transcript, usage).
  Disabled with a log line when no Anthropic API key is configured.

### Agent harness (`net.robog.clarity.harness`)

A small, dependency-light agent loop that owns the conversation, so we control cost, data and
approvals. It does not depend on backend domain code (could become its own module later).

- `Harness.run(request): Flow<HarnessEvent>`: stream model turn → execute requested tools in
  parallel → return **all** results in one user message → repeat until `end_turn`, refusal,
  `max_tokens`, or the turn/budget limit. History is append-only (required for preserved
  thinking on current models).
- `ModelGateway`: the only place that touches the Anthropic Java SDK (`com.anthropic:anthropic-java`).
  Defaults: model `claude-opus-5-5`, adaptive thinking, explicit `effort` (configurable),
  streaming, top-level prompt caching, server-side refusal fallback. Model/effort per task are config.
- `Tool`: name, description, JSON schema, `effect` (`READ` / `WRITE` / `EXTERNAL`), suspend invoke.
  `ToolPolicy` decides per run which tools are visible and which require approval
  (write/external tools default to "requires approval" → surfaced as an event, not executed).
- `McpToolProvider`: connects to MCP servers (MCP Kotlin SDK, streamable HTTP) — e.g. the browser
  container — and adapts their tools to `Tool`.

The backend's domain tools (`list_messages`, `list_calendar_events`, `search_contacts`,
`list_triggers`, `create_trigger`, …) are defined once as `Tool`s and served both to the in-process
harness and to external MCP clients at `/mcp`.

## Connectors

Push-only, separate containers, built on `connector-sdk`:

```kotlin
fun main() = runConnector(manifest) { config ->      // config: latest ConnectorConfig
    // your sync: talk to IMAP/CalDAV/..., keep state in ctx.state (the /data volume)
    push(records)                                     // batched, retried; idempotent by design
}
```

- Reads `CLARITY_URL`, `CLARITY_CONNECTOR_KEY_FILE`, `CLARITY_SYNC_INTERVAL`.
- On start: `PUT manifest`; then loop: poll config (ETag), run sync, sleep. Each connector decides
  its own cadence.
- Local state (`/data`): cursors, caches, full-fidelity copies. Owned by the connector only.
- Secret config values arrive sealed to the connector's own key (scaffolded in the DTOs).

## Deployment

- `deploy/compose.yaml` (Podman compose) for dev and simple installs; Quadlet units are the planned
  production path for systemd hosts.
- Networks: `db` (internal: postgres + backend), `ingest` (internal: backend + connectors),
  `tools` (internal: backend + MCP tool containers), and one non-internal **egress network per
  service** that needs the internet, so connectors cannot reach each other.
- Secrets: Podman secrets from `deploy/secrets/` (generated by `deploy/init-secrets.sh`, gitignored).
- Containers: non-root, read-only rootfs, `cap_drop: ALL`, `no-new-privileges`.
- Optional services via compose profiles: `example` (example connector), `browser` (Playwright MCP).

## CI/CD and branches

- `dev` is the integration branch (checked out locally); `main` is release.
- `ci.yml`: on PRs and pushes to `dev`/`main` — JVM build + tests, Android build + lint + tests,
  container build smoke test.
- `publish.yml`: on push to `main` — multi-arch (amd64/arm64) images to
  `ghcr.io/robog-two/clarity-<service>` with provenance attestations.
- Dependabot keeps Gradle, GitHub Actions and container base images current (targets `dev`).
