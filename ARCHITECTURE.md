# Clarity architecture

Clarity is a **single-tenant, self-hosted, always-on personal agent**. One deployment
(one compose project / one set of Quadlet units) serves exactly one person. The Android app is
the only user interface; the backend is a headless API. The person sets the priorities; the
agent takes small, scoped tasks, checks every action against the person's own written
constitution, and stays quiet unless something is worth their attention.

The product, as designed (see the design canvas "Personal Agent Prototype"):

1. **Onboarding**: server address, Anthropic API key, connectors (mail, calendar, contacts,
   files on the person's own server; messages and reminders on the phone), a spoken
   **values interview** that becomes a versioned **constitution**, and a schedule for the
   **morning call**.
2. **Morning call**: a voice call at a set time (heads-up notification 10 minutes before).
   The person talks; the agent answers in text on screen. Four stages: **clear** (capture
   everything, look up context live), **choose** (one important thing, at most one more),
   **commit** (when/where + the likely obstacle → if-then plans), **hand off** (what the agent
   does alone, what waits for review, what is parked until tomorrow).
3. **The app**: **Today** (plan, what the agent is doing, held items, parked), **Ask** (talk or
   type; context cards; proposals that need an OK), **Held** (actions that hit a constitution
   line; talk them through, keep the line or amend it), **Settings**.

```
                          device-signed JWT (ES256)
 ┌──────────────────┐  /api/v1 (REST + SSE feed)   ┌──────────────────────────────────────────────┐
 │ Android app      │ ───────────────────────────► │ backend (Ktor)                               │
 │ thin client:     │                              │                                              │
 │  renders cards,  │ ◄──── SSE: feed events ───── │  inbox ─► lead agent (one long thread)       │
 │  on-device STT,  │                              │   ▲          │  ├─ helpers (subagents)       │
 │  call scheduling │ ── /ingest/v1 (as connector)►│   │          │  └─ guard (checks actions     │
 │  └ phone conn.   │                              │   │          │       against constitution)   │
 └──────────────────┘                              │   │          ▼                               │
                                                   │ triggers  action outbox ─► held for review   │
 ┌───────────────────┐ push records /ingest/v1     │ scheduler      │                             │
 │ connector: mail   │ ──────────────────────────► │ ingest ─► normalize ─► dedup ─► Postgres     │
 │ connector: dav    │ ◄── poll actions ────────── │                │                             │
 └───────────────────┘   (save draft, send, hold)  └───────┬────────┼───────────┬─────────────────┘
   own creds, own volume                                   │ MCP    │ MCP       │ SQL
                                                           ▼        ▼           ▼
                                                      sandbox    browser     postgres
                                                     (shell +   (Firefox +
                                                      files)     uBO + PB)
```

## Principles

1. **Functional core, imperative shell.** Normalization, dedup keys, trigger matching, auth
   claim validation, guard decisions over verdicts, notification policy, inbox batching and
   the agent loop's decision logic are pure functions over immutable data. Effects (HTTP, SQL,
   clock, model calls) live at the edges behind small interfaces.
2. **Typed errors, not exceptions.** Domain operations return results through Arrow's `Raise`
   (context parameters are stable in Kotlin 2.4) with sealed error hierarchies. Exceptions are
   reserved for bugs/infrastructure failure and are mapped to RFC 9457 problems at the edge.
3. **Contracts live in `:shared`.** Every wire type (ingest, actions, feed, cards, constitution,
   held actions, sessions, settings) is a `@Serializable` class in `net.robog.clarity.shared`,
   used by backend, connectors and app. Change a contract there first.
4. **Least privilege everywhere.** Connectors hold their own credentials, can only push nouns
   they have been granted and execute actions addressed to them, and never see the database or
   other connectors. The agent sees data only through tools the backend exposes; its computers
   (sandbox, browser) have no credentials and no route to the database.
5. **Thin client, thick server.** The agent, its prompts and tools run on the server so they
   can change without an app release. The client renders typed cards and captures voice.
6. **Nothing leaves as the person without their review.** Every action with an external effect
   passes the guard; the constitution decides do / ask / never. Untrusted content (mail bodies,
   web pages, webhooks) is always data, never instructions.
7. **Durable by default.** Any agent work survives a backend restart and resumes where it was,
   without repeating external effects.
8. **Build for the next model.** Keep the harness small; prefer giving the model good tools,
   files and clear error messages over hard-coded flows.

## Repository layout

| Path | What |
|---|---|
| `gradle/libs.versions.toml` | The one version catalog (root build, build-logic, android build) |
| `build-logic/` | Convention plugins: `clarity.kotlin-jvm`, `clarity.jvm-app`, `clarity.kotlin-multiplatform` |
| `shared/` | KMP module (`jvm()` target) with wire DTOs. Android consumes the JVM variant. |
| `backend/` | Ktor service: API, ingest, persistence, triggers, scheduler, agent runtime, governance, MCP |
| `connector-sdk/` | Library every JVM connector uses: push client, config polling, action polling, local state |
| `connectors/example/` | Reference connector proving the contract end to end |
| `connectors/mail/` | IMAP + SMTP (Angus Mail) |
| `connectors/dav/` | CalDAV + CardDAV + WebDAV (dav4jvm, ical4j, ez-vcard) |
| `computers/sandbox/` | `sandboxd`: the agent's Linux workspace, served as an MCP server |
| `computers/browser/` | Firefox ESR + uBlock Origin + Privacy Badger, driven by Mozilla's Firefox DevTools MCP |
| `android/` | Separate Gradle build (includes the root build for `:shared`) |
| `deploy/` | Podman compose, container build files, secrets bootstrap |
| `docs/workstreams/` | Implementation specs, one per workstream (the build plan) |
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
├── http/                   Ktor modules: plugins, status pages→Problem, routing
├── auth/                   device JWT verification, enrollment, connector key auth, principals
├── persistence/            Database (Hikari+Flyway+Exposed), table objects, transaction helper
├── ingest/                 validate → authorize (grants) → normalize → dedup → upsert → publish event
├── dedup/                  pure: per-noun normalization + DedupKey
├── connectors/             registry, manifests, grants, config, action outbox (app + connector APIs)
├── triggers/               pure RecordQuery evaluator + trigger store + matcher on ingest events
├── scheduler/              durable in-process jobs (morning call prep, evening batch, review)
├── agent/                  lead agent, inbox, journal, helpers, memory, domain tools, prompts
├── governance/             constitution store, guard, held actions, action executor, notify policy
├── feed/                   the app's feed: messages + cards, sequence numbers, SSE fan-out
├── sessions/               morning call, values interview, ask, amend: session state machines
├── setup/                  instance setup: model key, schedule settings
└── mcp/                    exposes the domain tools as an MCP server at /mcp
net.robog.clarity.harness   provider-agnostic-ish agent loop over the Anthropic Messages API + MCP client
```

### HTTP surface

| Route | Auth | Purpose |
|---|---|---|
| `GET /healthz`, `GET /readyz` | none | liveness / readiness (DB reachable) |
| `POST /api/v1/enroll` | enrollment token or TOFU | register a device public key |
| `GET /api/v1/instance` | device | instance info + setup status |
| `GET /api/v1/setup` | device | `SetupStatus` (model key, connectors ready, constitution, settings) |
| `PUT /api/v1/setup/model-key` | device | store + validate the Anthropic API key |
| `GET/PUT /api/v1/settings` | device | morning call schedule, notification preferences |
| `GET /api/v1/connectors` | device | connector summaries (manifest, grants, last seen, sync status) |
| `PUT /api/v1/connectors/{id}/grants` | device | grant/revoke nouns for a connector |
| `GET/PUT /api/v1/connectors/{id}/config` | device | connector configuration (secret values sealed) |
| `POST /api/v1/connectors/{id}/test` | device | ask the connector to test credentials and sync |
| `GET /api/v1/feed?after={seq}` | device | feed items after a sequence number |
| `GET /api/v1/events` | device | SSE: feed events (new items, text deltas, held/task/constitution changes) |
| `POST /api/v1/input` | device | user input (typed or spoken), idempotent by client id |
| `POST /api/v1/sessions` · `GET/POST /api/v1/sessions/{id}/…` | device | start/advance/end a call, interview or amendment |
| `GET /api/v1/today` | device | the day's plan, tasks, held summary, parked items |
| `GET /api/v1/constitution` · `POST …/amendments` | device | current constitution, history, amend |
| `GET /api/v1/held` · `POST /api/v1/held/{id}/decision` | device | held actions; approve (optionally edited), decline, talk |
| `GET /api/v1/activity` | device | actions taken alone ("see allowed actions") |
| `PUT /ingest/v1/manifest` | connector | connector announces kind, nouns, actions it handles, config schema |
| `GET /ingest/v1/config` | connector | connector polls its config (`ETag` = version) |
| `POST /ingest/v1/batches` | connector | push `IngestBatch`, get per-record `RecordOutcome` |
| `GET /ingest/v1/actions?wait=30` | connector | long-poll actions addressed to this connector |
| `POST /ingest/v1/actions/{id}/result` | connector | report an action's outcome (idempotent) |
| `/mcp` | device | MCP (streamable HTTP) exposing the same domain tools the agent uses |

The path constants live in `shared/api/Api.kt` (`ApiPaths`).

### Authentication

**Devices** generate an EC P-256 key pair in the Android Keystore (non-exportable). Enrollment
sends the SPKI public key. Every request carries `Authorization: Bearer <jwt>` where the JWT is
signed by the device (`alg=ES256`, `kid=<deviceId>`, `sub=<deviceId>`, `aud=clarity`, `iat`, `exp`,
`jti`; `exp - iat ≤ 300 s`). The backend looks up the key by `kid` and verifies. No server-side
secrets to leak, no refresh tokens, revocation = delete the device row. SSE connections
authenticate the same way and are closed by the server when the JWT expires; the client
reconnects with a fresh one and resumes from its last `seq`.

**Enrollment** modes (`clarity.enrollment.mode`):
- `tofu` (default): while zero devices exist, the first enrollment is accepted without a token.
- `token`: always requires the enrollment token.
In both modes an enrollment token exists: on first boot the backend generates one (or reads
`CLARITY_ENROLLMENT_TOKEN_FILE`), writes it to `${dataDir}/enrollment-token` (0600) and logs it
once. Additional phones enroll with that token. (The onboarding "server" step asks for the
address and, when needed, this token as a "setup code".)

**Connectors** authenticate with `Authorization: Bearer <key>`; the backend stores only
SHA-256(key). Provisioned connectors get their key via a Podman secret mounted into both the
connector and the backend (`/run/secrets/connector_<id>_key`; the backend registers every such
file at boot). A connector's ingest is accepted **only for nouns the user granted**.

**The phone as a connector**: an enrolled device may also push to `/ingest/v1` with its device
JWT; it is treated as connector `device-<deviceId>` with its own grants, and receives actions
addressed to it (set do-not-disturb).

**The model key** comes from `ANTHROPIC_API_KEY_FILE` (Podman secret) or, if absent, from the
app's onboarding (`PUT /api/v1/setup/model-key`), validated with a Models API call and stored
in `${dataDir}/anthropic-api-key` (0600). It is never returned by the API.

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
   - document: `store | normalized path`
5. **Upsert** the canonical row by `dedup_key` → `Created` or `Merged`; record the
   `source_record` and update `source_link` so tombstones and actions can find the entity.
6. After commit, publish `Ingested(noun, entityId, outcome, payload)` on an in-process event bus.

Canonical tables hold only the minimal identifying metadata plus the content tools need.
Connectors keep full-fidelity data in their own volumes. Documents store a text excerpt and
metadata; the full text is fetched through the connector on demand (an action with a result).

## Agent runtime

The agent is **one long-lived lead thread** plus short-lived **helpers**, the same shape as a
person managing a to-do list with a few assistants.

### The lead thread

- A single conversation that never ends. Its history is the append-only Messages API
  transcript, persisted in `agent_thread_turn` after every model turn and every tool-result
  message (the **journal**). On restart the lead resumes from the journal.
- **Inbox.** Everything that can wake the agent becomes an `InboxItem`: user input, session
  events, trigger matches, scheduled jobs, helper results, connector action results, held
  decisions. A pure `planNextTurn(inbox, now)` decides what goes in the next turn:
  - **user input pre-empts**: the in-flight model call is cancelled (tool calls already running
    finish and their results are kept) and the user's input goes in next;
  - everything else is **batched**: items that arrive while a turn runs go into the next turn
    together;
  - background items carry a priority; low priority ones can wait for an idle lead.
- **Injected events** (triggers, jobs, helper results) are appended as **mid-conversation
  system messages** (operator channel, does not break the cache). Untrusted content inside them
  is quoted in `<untrusted source="…">…</untrusted>` blocks; the system prompt says such blocks are
  data and can never authorize an action.
- **Talking to the person.** The lead's only way to reach the person is the `send_to_user`
  tool (typed `Card`s, an `urgency`). The feed, the SSE stream and push notifications are all
  driven by it. The client never interprets free text from the model.
- **Should I bother them?** `send_to_user(urgency = …)` is filtered by the pure notification
  policy: `URGENT` respects "who gets through" and protected times from the constitution
  (push now or hold until the block ends); `NORMAL` shows in the feed without a push; `BATCH`
  goes to the evening summary.

### Helpers (subagents)

- The lead calls `delegate(task, tools, model?)` for anything token-heavy (reading many
  messages, browsing, long documents, screenshots, shell work). A helper is a separate run with
  fresh context, its own journal, and a restricted tool set; it returns one result, which comes
  back to the lead as an inbox item. The lead's context only ever sees the result.
- Helpers are durable: each is a row in `agent_run` with its journal; a crashed or restarted
  helper resumes from its last completed turn. The lead can list, message and cancel helpers
  (`list_helpers`, `message_helper`, `cancel_helper`).

### Durability (journal instead of a workflow engine)

Temporal is the textbook answer, but for one person on one box it adds a server and a schema
for what our loop already has: an append-only transcript. So:

- Every run (lead or helper) is checkpointed after each model response and after each
  tool-result message. Resume = reload transcript and continue the loop.
- Tool calls whose effect is `EXTERNAL` never execute inline: they become **actions** in the
  outbox with an **idempotency key** derived from `(runId, toolUseId)`. Re-running a turn after a
  crash re-derives the same key, so the action executes at most once.
- Scheduled jobs are rows with `next_run_at` claimed with `SELECT … FOR UPDATE SKIP LOCKED`, so
  restarts never double-fire.

### Context engineering

- **Cache-stable prefix**: tools (sorted, byte-stable) → frozen system prompt (persona, rules,
  tool guidance) → constitution (a system block that changes only when amended) → messages.
  One explicit breakpoint on the static system prefix, top-level automatic caching for the tail.
  The person's profile facts are part of the constitution block.
- **Tool search**: domain tools the lead uses constantly are loaded; everything else (MCP tools
  from the browser and sandbox, rarely used domain tools) is `defer_loading` behind the BM25 tool
  search tool, so adding a tool never invalidates the cache.
- **Compaction**: server-side compaction (beta) keeps the lead thread inside its window; the
  harness appends `response.content` verbatim so compaction blocks survive. When the person goes
  quiet and the cache is still warm, the backend proactively compacts (cheap while warm), so the
  next conversation starts from the summary.
- **Big outputs go to files**: tool results above a size limit are written to the sandbox
  workspace and the tool returns a path + summary.
- **Model roles** (config, per role): `lead` (default `claude-opus-5-5`; effort `low` during a
  live call for latency, `medium` otherwise), `helper` (`claude-sonnet-5-5`), `guard` and
  `triage` (`claude-haiku-5-5`). Roles never switch models inside a thread (cache is per model);
  a different model means a helper.
- Refusals: server-side fallback (`fallbacks: "default"`) on supported models; a refusal is
  surfaced as a card, never retried silently.

### Memory

Four tiers, all in Postgres (backed up with everything else), all reachable through tools:

| Tier | What | In the prompt? |
|---|---|---|
| **Constitution + profile** | Lines with reasons, hard lines, autonomy tiers, people who get through, protected times, plus short profile facts | Always (system block) |
| **Journal** | Dated entries the agent writes about what happened and was decided (the morning call writes one) | Today's and yesterday's; older via search |
| **Scratch** | Short-lived notes with a TTL (hours) | Live ones, as a system message when changed |
| **Everything else** | Ingested records, documents, past transcripts, files in the sandbox | Search tools only (`search_memory`, domain tools, shell in sandbox) |

Profile facts are proposed by the agent (`remember_fact`) and garbage-collected by a periodic
helper that merges, expires and drops stale facts. The constitution itself changes only through
an amendment the person approves.

### Domain tools (backend, also served at `/mcp`)

Reads: `list_messages`, `list_calendar_events`, `search_contacts`,
`search_documents`, `read_document`, `search_memory`, `read_journal`, `today`.
Own state (`WRITE`, allowed alone): `create_trigger`, `delete_trigger`, `remember_fact`,
`write_journal`, `set_scratch`, `update_plan`, `update_task`, `send_to_user`, `delegate`, helper
controls. External (`EXTERNAL`, always via guard + outbox): `save_draft`, `send_email`,
`create_event`, `move_event`, `respond_to_invite`, `create_reminder`, `set_do_not_disturb`,
plus every browser tool that submits forms.

## Governance

### Constitution

Versioned document (`constitution_version`), sections as in the design: what I value, hard
lines (never crossed), how much I do alone (three **autonomy tiers**: *do it, then tell me* /
*ask me first* / *never alone*), when things collide, who gets through, when I get it wrong.
Every line keeps its **because** (the reason is what lets the agent use judgment where no rule
applies). Structured parts (autonomy tiers per action kind, people who get through, protected
time blocks) are machine-readable so the guard and the notification policy can apply them
deterministically; the rest is natural language.

### The guard (auto-verification)

Every proposed action (`ProposedAction`) goes through `Guard.check(action, constitution, provenance)`:

1. **Deterministic rules first** (pure): hard-line kinds (money, purchases) → `DENY`; the
   action kind's autonomy tier → `ALLOW` / `ASK`; recipients > 1 for "sent as me" → `ASK`;
   provenance includes untrusted content and the action is external → at least `ASK`.
2. **Model check** (`guard` role, structured output): the action, the constitution and the
   agent's stated reason → `ALLOW` / `ASK` / `DENY` + the line ids it touches + one-sentence
   rationale. The model can only tighten the deterministic result, never loosen it.

`ALLOW` → the action goes to the outbox and is logged in activity ("see allowed actions").
`ASK` → a **held action** with the agent's "what I think" and "I'm asking because …" text.
`DENY` → a held action in status `blocked` the person can still review ("hit one of your lines").

### Held actions and amendments

The person can approve (optionally with an edited draft), decline, or **talk about it**: a
session where they explain, and the agent either revises the action or proposes an
**amendment** (old line → new line, with reason). Saving an amendment bumps the constitution
version; the lead gets a system message with the change.

### Action outbox

Approved/allowed actions are rows in `action_outbox` addressed to a connector (by the
`ActionKind`s its manifest declares it handles). Connectors long-poll, execute with their own
credentials and report `ActionResult`. Results come back to the lead as inbox items. Status is
visible on Today ("drafted", "held", "sent").

## Credential vault

The agent can log in and pay for things without ever seeing a password or card number.

- **Credentials** (`credential` table): a name, a kind (login, payment card, identity, note), a site/purpose, non-secret metadata (username hint, card brand + last 4,
  expiry month), and the secret fields as ciphertext only.
- **Encryption at rest, key on the phone.** Each enrolled device has a vault key pair in the
  Android Keystore (P-256, `PURPOSE_AGREE_KEY`, StrongBox when available, user authentication
  required **for every use**). Secrets are sealed to it (ECDH-ES + HKDF-SHA256 + AES-256-GCM,
  envelope format in `:shared`); the backend stores ciphertext it cannot open. Several devices
  = the data key is wrapped once per device. Lost every device = re-enter the credentials.
- **Adding a credential.** The agent calls `request_credential(kind, fields, purpose)`; the
  person gets a form card with typed fields (username, email, password, card number with Luhn
  check, expiry, CVC, name, address, phone, OTP) validated on the phone (validators are pure
  Kotlin in `:shared`). The phone encrypts before upload. The agent only learns the new
  credential's id and metadata.
- **Using a credential.** The agent never sees a secret. It writes placeholders such as
  `[secret:credit_card_1_cvv]` (credential name + field) into browser tool inputs, e.g. "type
  `[secret:bank_login_password]` into uid e12". The backend's browser tool wrapper finds the
  placeholders, checks there is a live **grant**, substitutes the values, and calls the browser
  MCP; the model's tool call, the journal and logs only ever hold the placeholder.
  A grant is created by an approval: the first placeholder for a credential on a site makes a
  held approval with context (which site, what for, which credential, the agent's reason; the
  guard still applies, payment cards are *never alone* so they always ask). **Yes** requires
  biometric/device auth; the phone opens the secret in the Keystore and returns it over the
  authenticated channel. The grant covers **that credential on that site (registrable domain)
  for 5 minutes**, so the agent can click through a multi-step login or checkout; values are
  held only in backend memory for the grant's lifetime, then dropped. A placeholder for another
  site, another credential, or after expiry asks again.
- **Two-factor codes are not stored.** No TOTP seeds in the vault. When a site asks for a code,
  the agent asks the person (a held item with context; they type the code into a one-field
  form). Reading codes from SMS through the phone connector is a possible later addition.
- **Scrubbing.** Every value released under a grant is registered with that run's scrubber, which
  replaces it (and common variants: spaces/dashes in card numbers, URL-encoded) with its
  placeholder `[secret:<name>_<field>]` in tool results, snapshots, errors and logs before anything
  reaches the model or the database. Screenshots are refused while a filled secret field is on
  the page.

## Sessions and voice

- **Speech-to-text runs on the phone** (Android on-device `SpeechRecognizer`, partial results
  give the word-by-word "live" bubble). Only final text reaches the server, as `UserInput` with
  `source = VOICE`. The agent always answers in text. (A server-side Whisper container is a
  possible later option for devices without on-device recognition.)
- A **session** is a structured episode on the lead thread: `MORNING_CALL`, `VALUES_INTERVIEW`,
  `ASK`, `AMEND`. Starting one appends a system message with the session's playbook (stages,
  goals, what to produce); the stage machine (pure) advances on the person's "that's all / next /
  end call" and on the agent's `advance_stage` tool. Session state is in `agent_session`.
- **Morning call outputs**: `capture` chips, today's two priorities, if-then plans
  (implementation intentions), handed-off tasks with their autonomy, parked items. After
  "end call" the lead executes the hand-off; the call-ended screen and Today show progress.
- **Values interview outputs**: proposed constitution lines (with reasons and tiers) per
  question; "reword" asks the agent for an alternative phrasing; the final doc is v1.
- **Scheduling the call**: the schedule is stored server-side and mirrored on the phone, which
  sets exact alarms locally (heads-up notification, then a full-screen `CallStyle`
  notification). The call works even if push is unavailable. Ten minutes before the call the
  backend runs a prep job (yesterday's plan, today's calendar, new mail) so the lead has
  context ready and the cache is warm when the person answers.

## The agent's computers

Two containers on the internal `tools` network, each an MCP server the backend connects to:

- **sandbox** (`computers/sandbox`, `sandboxd`, Kotlin + MCP Kotlin SDK): a Linux workspace with a
  persistent `/workspace` volume, common CLI tools (bash, coreutils, ripgrep, jq, python3, git,
  pandoc, poppler-utils). Tools: `shell` (timeout, output cap, cwd under /workspace),
  `read_file`, `write_file`, `edit_file`, `search_files`. No network egress, no credentials,
  read-only rootfs apart from `/workspace` and `/tmp`, CPU/memory/pids limits. It can be
  recreated any time; only `/workspace` persists (and the backend can always rebuild it).
- **browser** (`computers/browser`): Firefox ESR (its password manager and form autofill off;
  credentials come only from the vault), headless, with uBlock Origin and Privacy
  Badger preinstalled in a fixed profile (ad/tracker blocking makes pages lighter and the
  accessibility snapshot shorter), driven by Mozilla's Firefox DevTools MCP server over WebDriver
  BiDi; exposed as streamable HTTP. Its own egress network; no access to other internal
  networks. Page reading order: accessibility/DOM snapshot with element uids first, screenshot
  only when needed. Screenshots and long pages go to a helper, not the lead.

The cheapest-first ladder in the system prompt: what the agent already knows → domain tools →
web search/fetch (server tools) → browser → sandbox → ask the person.

## Connectors

Push-only for data, pull for actions, separate containers, built on `connector-sdk`:

```kotlin
fun main() = runConnector(manifest) {
    sync { config -> push(records) }                         // batched, retried; idempotent
    on<ProposedAction.SaveDraft> { action -> /* IMAP APPEND to Drafts */ ActionOutcome.Done(ref) }
}
```

- Reads `CLARITY_URL`, `CLARITY_CONNECTOR_KEY_FILE`, `CLARITY_SYNC_INTERVAL`.
- On start: `PUT manifest`; then loops: poll config (ETag) + run sync + sleep, and long-poll
  actions concurrently. Each connector decides its own cadence.
- Local state (`/data`): cursors, caches, full-fidelity copies. Owned by the connector only.
- Secret config values (passwords) arrive sealed to the connector's own key (X25519 sealed box),
  so the backend stores only ciphertext.

| Connector | Nouns | Actions |
|---|---|---|
| `mail` (IMAP + SMTP, Angus Mail) | message | save_draft, send_email |
| `dav` (dav4jvm + ical4j + ez-vcard) | calendar_event, contact, document | create_event, move_event, respond_to_invite, create_reminder, read_document |
| phone (in the app) | message (SMS) | set_do_not_disturb |

Reminders are not a separate store: `create_reminder` becomes a short event with an alarm
(`VALARM`) on the person's calendar, so it rings through whatever calendar app they use.

## Android app (`net.robog.clarity.app`)

Compose + Material 3, Navigation 3, Metro DI, DataStore. The look follows the prototype in
`docs/design/prototype/` (Roboto Flex UI type, Libre Caslon Text for reading text, the
teal/cream Material 3 palette defined in the `.app` CSS variables there).

- `net/`: Ktor client (OkHttp engine), device JWT signer, SSE feed client with resume by `seq`.
- `data/`: repositories over the API; a local cache of the feed (Room) so the app opens instantly.
- `voice/`: on-device speech recognizer wrapper exposing partial/final results as a Flow.
- `call/`: alarm scheduling, heads-up + `CallStyle` full-screen notification, the call screen.
- `ui/`: onboarding, call, today, ask, held, amend, settings, constitution; one card renderer for
  every `Card` type, shared by call, ask and feed.
- `connector/`: the phone connector (SMS via `Telephony`; executes `set_do_not_disturb` with
  notification-policy access), running in WorkManager. The app keeps no reminders of its own.
- Push without Google or extra containers: the app keeps the `GET /api/v1/events` SSE stream
  open from a foreground service (type `remoteMessaging`, asks for the battery-optimization
  exemption), reconnects with backoff on network changes and resumes from its last `seq`.
  Urgent items arrive as `FeedEvent`s the service turns into notifications. If the person
  turns this off, the app syncs on open and on a periodic WorkManager job. (UnifiedPush can be
  added later as a second transport without backend changes beyond one POST.)
- `vault/`: the phone-held vault key (Android Keystore, per-use biometric), credential forms,
  and approving credential use.

## Deployment

- `deploy/compose.yaml` (Podman compose) for dev and simple installs; Quadlet units are the planned
  production path for systemd hosts.
- Networks: `db` (internal: postgres + backend), `ingest` (internal: backend + connectors),
  `tools` (internal: backend + sandbox + browser), `proxy` (internal: caddy + backend), and one
  non-internal **egress network per service** that needs the internet (backend for the model API,
  each connector for its upstream, caddy). The sandbox has no egress network.
- **Egress proxy** (`egress`, Squid): the browser and the backend have no direct route out; they
  reach the internet only through this proxy, which refuses private, loopback and link-local
  destinations (it resolves names itself, so DNS tricks don't bypass it). The browser may reach
  any public host on 80/443; the backend only `api.anthropic.com`. Connectors keep their own
  egress networks because their servers are usually on the person's LAN.
- Secrets: Podman secrets from `deploy/secrets/` (generated by `deploy/init-secrets.sh`, gitignored).
- Containers: non-root, read-only rootfs, `cap_drop: ALL`, `no-new-privileges`, memory/CPU/pids
  limits.
- Optional services via compose profiles: `example`, `mail`, `dav`, `computers` (sandbox +
  browser). (Push needs no container: it rides the SSE feed.)

## Observability and evals

- Every run's usage (incl. cache read/write tokens) is stored on `agent_run`; a cache miss on the
  lead thread's prefix is logged as a warning (it is a bug).
- `backend/src/test/.../evals`: scenario tests over a scripted `ModelGateway` (deterministic) for
  the inbox, guard, sessions and notification policy; plus an opt-in live eval suite
  (`-Pclarity.liveEvals`) that runs recorded scenarios against the real API and grades with a
  rubric. Mistakes found in use become new scenarios.

## CI/CD and branches

- `dev` is the integration branch (checked out locally); `main` is release.
- `ci.yml`: on PRs and pushes to `dev`/`main`: JVM build + tests, Android build + lint + tests,
  container build smoke test.
- `publish.yml`: on push to `main`: multi-arch (amd64/arm64) images to
  `ghcr.io/robog-two/clarity-<service>` with provenance attestations.
- Dependabot keeps Gradle, GitHub Actions and container base images current (targets `dev`).
