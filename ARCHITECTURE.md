# Clarity architecture

Clarity is a **single-tenant, self-hosted, always-on personal agent**. One deployment
(one compose project / one set of Quadlet units) serves exactly one person. The Android app is
the only user interface; the backend is a headless API. The person sets the priorities; the
agent takes small, scoped tasks, checks every action against the person's own written
constitution, and stays quiet unless something is worth their attention.

The product, as designed (see the design canvas "Personal Agent Prototype"):

1. **Onboarding**: server address, a check that the server has its model key, connectors (mail, calendar, contacts,
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
├── scheduler/              durable in-process jobs (morning call prep, evening batch, review, nudges)
├── agent/                  lead agent, inbox, journal, helpers, memory, domain tools, prompts
├── governance/             constitution store, guard, held actions, action executor, notify policy
├── feed/                   the app's feed: messages + cards, sequence numbers, SSE fan-out
├── sessions/               morning call, values interview, ask, amend: session state machines
├── setup/                  instance setup: model key, schedule settings
└── mcp/                    exposes the domain tools as an MCP server at /mcp
net.robog.clarity.harness   agent loop over OpenRouter's Chat Completions API (the only model provider) + MCP client
```

### HTTP surface

| Route | Auth | Purpose |
|---|---|---|
| `GET /healthz`, `GET /readyz` | none | liveness / readiness (DB reachable) |
| `POST /api/v1/enroll` | enrollment token or TOFU | register a device public key |
| `GET /api/v1/instance` | device | instance info + setup status |
| `GET /api/v1/setup` | device | `SetupStatus` (model key, connectors ready, constitution, settings) |
| `PUT /api/v1/setup/model-key` | device | always 409: the model key is the server's own (kept for older apps) |
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
| `GET /api/v1/nudges?since={iso}` | device | pending nudges and the timeline of fired/skipped/missed/cancelled ones |
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
addressed to it (set do-not-disturb). It is never key-provisioned (`provisioned = false`, no
`key_hash`: the JWT is its credential). A manifest PUT by the device itself grants the nouns it
announces as if `autoGrant` were on (lifting `pending`, never overriding `revoked`): the person
consented with the Android permission, and the same JWT could grant them on the app API anyway.
The app also grants `message` explicitly when the person allows reading texts in Settings.

**The model key** is the server's OpenRouter key: `OPENROUTER_API_KEY_FILE` (the
`openrouter_api_key` Podman secret) or `OPENROUTER_API_KEY`. The backend does not start without
it (a configuration error names the variable). It is never returned by the API and cannot be set
from the app; `SetupStatus.missingKeys` is `["openrouter"]` only when a hand-built test config
has none. Every model call (all roles, embeddings, the decision model) goes to OpenRouter with
`data_collection: deny` and zero data retention; nothing else leaves the server.

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
  transcript, persisted in `agent_journal` after every model turn and every tool-result
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
  system messages** (operator channel, sent in place as `system` messages). Untrusted content inside them

  is quoted in `<untrusted source="…">…</untrusted>` blocks; the system prompt says such blocks are
  data and can never authorize an action.
- **Talking to the person.** The lead's only way to reach the person is the `send_to_user`
  tool (typed `Card`s, an `urgency`). The feed, the SSE stream and push notifications are all
  driven by it. The client never interprets free text from the model. Plain assistant text is
  only logged. When the person wrote during a turn and the model ends it without a successful
  `send_to_user` (open models sometimes do the work and stop), the lead appends one operator
  nudge at the end of the thread and runs once more; never twice, and never for turns made only
  of events, where silence is fine. A helper that finishes with no result text is likewise asked
  once for it.
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

### The review gate (decision model)

The `periodic-review` job (every `CLARITY_REVIEW_INTERVAL`, 2h) used to wake the lead every time.
Now, when an OpenRouter key is set, a **decision model** (`CLARITY_DECISION_MODEL`, default
`microsoft/microsoft-decision-1`; OpenRouter `POST /api/alpha/decisions`, "System One" format,
zero-data-retention preferences when `OPENROUTER_ZDR`) decides first. It answers fixed questions
with probabilities, never text. The gate (`scheduler/ReviewGate.kt`) reads what changed since the
lead last reviewed (the newest `job:periodic-review:*` inbox item): new messages, calendar events
added, moved or cancelled, events starting before the next review plus an hour, and unconsumed inbox
items. Held actions and open tasks go along as context. Nothing changed → skip with no call; the
previous check already judged the same changes → skip; otherwise one call (`state` is bounded
JSON with all outside text under `untrusted` keys, a `noul` "act now?" and a logged `choice` of
urgency). It wakes the lead when p ≥ `CLARITY_REVIEW_GATE_THRESHOLD` (0.3), and always once
`CLARITY_REVIEW_MAX_QUIET` (8h) has passed since the last review, if there is anything to look at.
It **fails open**: gate off (`CLARITY_REVIEW_GATE=false`, `CLARITY_DECISIONS=false`), no key, an
error, a refusal or a missing answer wakes the lead as before. A woken review covers everything
since the last review, skipped windows included. Each call is a `model_call` row with role
`decision`; each verdict is one info log line. The model has no tools, so injected text can at
most flip the answer. That is why it is never used for the constitution guard.

### The helper's browser (pilot)

A helper does not see the browser MCP's raw tools. It gets a small planner surface
(`agent/BrowserPilot.kt`): `look(scope?)`, `act(intent, expect, value?)`, `read(scope?)`,
`tabs(select?|close?)`, `go(url)`, `back()`, `wait(text|seconds)`, `sign_in(credential)`,
`screenshot` and `downloads`. The text model plans in words; **Jev** (`CLARITY_JEV_MODEL`, default
the dated `typesafe/jev-1.14-20261014`, the same decisions endpoint and zero-data-retention
preferences as the review gate) grounds, double-checks and verifies each step on a screenshot. What
Jev acts on is what a person sees: hidden text, aria labels, off-screen and tiny print never reach it.

- **look** runs Clarity's observation script (`resources/browser/observe.js`, `evaluate_script` in
  the isolated `clarity` sandbox realm) instead of the MCP snapshot, which stops at DOM depth 10.
  It walks every element, open shadow roots and same-origin frames. It keeps visible controls,
  headings and short text with role, name (80 chars), value or state, autocomplete and link
  target, stamps a stable ref on each, and stays within about 1.5k tokens ("N more – narrow with
  scope"). Its header shows the tabs with their openers, any dialog, whether the page is still
  loading, the sign-in state of each site in the run (phase, codes sent and when) and how much of
  the page is below; scroll areas follow as their own entries. It never returns password values or
  the values of fields Clarity typed a secret into.
- **act** takes one screenshot and makes one Jev call: a `noul` "is <intent> visible?" and a `box`.
  A box always comes back, so the noul decides. The target is the element under the box centre
  (`elementFromPoint`, through shadow roots). In live runs, targets that were on the page scored
  0.84–1.0, absent ones 0.03–0.04, and a link drawn as a button 0.51–0.59, so
  `CLARITY_JEV_VISIBLE_THRESHOLD` is 0.7, with a second step. When p is below it, or the box hit
  something that fits the words worse than a control the text view names (a box between "Accept
  all" and "Reject all"), Jev is asked once more about "the control labelled "<name>"". The step
  goes ahead only if that box lands on the named element, or covers at least a quarter of it while
  the element is on top at its own centre, so a field behind a dialog is never typed into.
  Otherwise the answer is "not found" plus the text view, and nothing is clicked. With `CLARITY_BROWSER_TEXT_GROUNDING`,
  the text decision model can confirm an exact name match from the text view instead (a Decision-1
  `choice` over up to 20 refs); the screenshot path stays the default. The target is clicked or
  filled with trusted input: by the snapshot uid of a one-time `data-clarity-ref` token, or, in a
  shadow root or frame the selector cannot reach, by focusing it and sending keys.
- **Gate.** Every pilot step goes through `BrowserGate.perform`. Placeholders, the deterministic
  rules (`BrowserRules`), held approvals, never-alone payments, code waits and scrubbing work as
  before. Jev adds a `choice` of `browse | sign_in | commit | unsure`, asked on the screenshot with
  the target outlined in red (`combineVerdict`). Rules that ask always win. A Jev error asks the
  person, and so does a commit (p ≥ 0.5). A step the rules pass also needs Jev to be sure
  (p ≥ 0.9) that it is `browse` (or `sign_in` under a released login); otherwise it goes to the
  triage. With `CLARITY_JEV_MAY_APPROVE` (default false), a sure `browse` also passes steps the
  rules leave to the triage.
- **Change report.** Built in code after every step: address and title, tabs opened or closed (a
  `list_pages` diff), dialogs, new visible text (from a MutationObserver the script installs), the
  target's new state, a code field appearing, and Jev's `noul` "did this happen: <expect>?" on the
  after-screenshot. It says "No visible change" outright. After two of those in a row, `act`
  refuses until the helper calls `look`. A helper's browser task stops after
  `CLARITY_BROWSER_MAX_ACTIONS` (25) actions.
- **Settling.** After every action, navigation and sign-in step, and before `look`, `read` and
  `sign_in`, the pilot waits until the page has settled before it builds the change report or asks
  Jev anything. Settled means: the document is not loading; no request finished, no DOM node was
  added or changed, and Clarity's own last step was at least 500 ms ago; and no loading indicator
  (`aria-busy`, `role=progressbar`, a spinner, loader, skeleton or shimmer class) is in view. The
  script's `settle` op reports those times from a MutationObserver and Resource Timing (a
  `PerformanceObserver`); the Kotlin side polls it every 250 ms (`settleWait`). The page's own `fetch`
  and XHR are never wrapped: the probe runs in the isolated sandbox, and patching the page realm
  would be visible to its bot detection. A request still in flight is invisible to Resource Timing,
  so the quiet window also counts from Clarity's last step, which gives a step 500 ms to start one.
  After 8 s the report says "Still loading after 8 s (why)", and a failed Jev check on such a page
  reads "not there yet, but the page is still loading", never "did not happen". This is the 500 ms
  quiet window of Playwright's `networkidle` ([load states](https://playwright.dev/docs/api/class-page#page-wait-for-load-state);
  Playwright discourages it as the only signal, hence the DOM and indicator checks), browser-use's
  `wait_for_network_idle_page_load_time` of 0.5 s ([profile](https://github.com/browser-use/browser-use/blob/main/browser_use/browser/profile.py))
  and Stagehand's DOM-and-network quiet wait with a capped budget ([PR 1284](https://github.com/browserbase/stagehand/pull/1284)).
- **Signed in sticks.** Per run and site, `SignInState` keeps `signedInAt`. A site counts as signed
  in on firm signals only (`signedInVerdict`): no password or code field, not a sign-in address
  (`login.`/`auth.`/`accounts.` hosts, `/login`, `/signin`, `/oauth`… paths), and two of: a sign-out
  control or greeting on screen, new names among the site's readable cookies since it was first seen
  (names only, never values; HttpOnly cookies cannot be seen), and Jev's `sign_in` choice. Jev alone
  still counts after `sign_in` submits. This follows Playwright's advice to wait for the final URL
  after redirects and then for an element only signed-in users see
  ([authentication](https://playwright.dev/docs/auth)). When a sign-in ends on another site (an
  identity provider sending the person back), every site of that sign-in is marked. Once signed in:
  `look` says "Signed in to ulta.com (since 14:16)"; `sign_in` refuses to start again unless the page
  shows a login or code field; `act` refuses a control named like "Sign in", or one that started
  this run's sign-in on that page (recorded when a step led from an ordinary page into a sign-in),
  before any model call; and `go`/`back` to such a page get "you are already signed in; look for the
  page you need". Signed in only ends when the page says the session expired. A code field after
  sign-in (a "verify it's you" dialog) is a second verification: `sign_in` asks the person for a new
  code through `ask_code`, the code guard lets the site send that one code, and a code placeholder
  already typed is refused on a new code field.
- **Scrolling.** `look` lists each scroll area (an element that scrolls on its own, such as an
  inner list in a fixed layout) as its own entry: `[c40] scroll area "Available times": showing ~8 of
  ~30 rows, more below (880 px)`. The name comes from its label or the heading before it, and the
  total rows are estimated from its height, so a virtualized list counts all of them. The header says
  "Page continues: 3,200 px below". Elements scrolled out of an area are marked "(scrolled out of view
  in c40)". `act("scroll the available times down")` scrolls that area: by its name in the text view,
  else found on the screenshot by Jev. A bare "scroll down" scrolls the area under the middle of the
  viewport that can still move, else the page (browser-use's scroll also takes an element for
  "dropdowns/custom UI", [tools](https://github.com/browser-use/browser-use/blob/main/browser_use/tools/service.py)).
  A target the text view names but that is off screen, in the page or in an area, is scrolled into view
  (`scrollIntoView`, which scrolls the areas around it) before the screenshot and grounding, as
  Playwright scrolls before acting ([actionability](https://playwright.dev/docs/actionability)).
  `read` scrolls the page (at most 8 steps) and each area (at most 4 areas, 25 steps each) to the end,
  waits after each step for rows that load, collects the text per block (one line per row) and drops
  the overlap between steps (`mergeLines`). A list that loads more on scroll and one that renders only
  the rows in view are both read whole. Scroll positions are put back afterwards.
- **Script size.** `evaluate_script` takes at most 16 KiB, so `observe.js` is split into
  `//#if op …` sections and each call sends only the shared part and its op's sections.
- **Tabs.** A tab that a step opens is selected at once ("Opened tab 1 (opener 0)"). When a popup
  closes, its opener is selected again. While a popup that a tab opened is still open, `go` and
  `back` in that tab are refused ("finish or close the sign-in tab first").
- **Code guard.** Kept per run and site: the codes sent, when, the labels that sent them, and the
  phase. Once a code was sent, a step is refused for 10 minutes ("a code was sent at HH:MM – use
  ask_code") when the rules flag it (a resend-like label, or a label that already sent a code) or
  Jev does (a `noul` "would this send another code or restart sign-in?" with p ≥
  `CLARITY_JEV_RESEND_THRESHOLD`, 0.3). After two codes for a site such a step is refused for good,
  and the helper hands back to the person.
- **sign_in** fills the fields it finds by autocomplete and type with the credential's
  placeholders, submits, and asks Jev where the sign-in stands (`signed_in | needs_code |
  choose_method | needs_login | error | other`). On a code page it asks the person through
  `ask_code`, types the code placeholder (the fill waits for the person) and verifies. If the code
  page is still there afterwards, the site did not take the code. The next `sign_in` asks the person
  to type it again and never asks the site for a new code; after three rejected codes it hands back.
  Its own steps go through the rules and the triage without a Jev step check.
- **Secrets on screen.** For 8 actions on a site after a secret was typed (the screenshot quiet
  period), `screenshot` is still refused to the model. Jev gets those frames with the
  secret-filled fields painted black, and they are never stored.
- **Audit trail** (`browser_step`, V20). Per step: the run, sequence, page, intent and expect, the
  before screenshot and Jev's box, the element hit, every Jev and Decision answer with its
  probabilities, latency and cost, the gate outcome, the action sent (placeholders only), the after
  screenshot and the change report. Frames from the quiet period are not stored (`*_withheld`). The
  app can read it, read-only and device-authenticated: `GET /api/v1/browser/steps?run=<id>`,
  `GET …/{id}/before.png`, `…/{id}/after.png`. Each Jev and Decision call is also a `model_call`
  row (role `decision`). The `browser-frame-retention` job (every 6 hours) clears the two
  screenshot columns of steps older than `CLARITY_BROWSER_FRAME_RETENTION` (30 days) and sets
  `frames_dropped_at` (V21). The rows stay, and nothing else is deleted.
- **Fails closed.** With no decision model, an error or a refusal, the step is not taken ("I never
  click blind") or it goes to the person.
- **The lead never browses.** `BrowserGate` and the pilot refuse every call from a lead scope, and
  the lead's tool set has no `browser__*` tools and neither `ask_code` nor `list_credentials`.
- **One run at a time (the lease).** The browser is shared, so a run holds a lease while it uses it
  (`agent/BrowserLease.kt`, V22). The lease is a single `browser_lease` row changed under `FOR UPDATE`,
  with a FIFO `browser_lease_queue`.
  - A run asks for the lease on its first browser call, not when it is spawned. While it waits, an
    audit step says "waiting for the browser – another task is using it". After
    `CLARITY_BROWSER_LEASE_WAIT` (30 minutes) it is told to report back.
  - A new lease starts fresh: every tab is closed, one `about:blank` tab is opened, and the run's
    code guard, sign-in state and change history are reset. Cookies and logins stay.
  - The lease is released when the run finishes, fails or is cancelled. It is also released after
    `CLARITY_BROWSER_LEASE_IDLE` (10 minutes) without a browser call, which includes waiting for a
    person or a code. The run then queues again on its next call, starts fresh and is told so; a
    step approved after the lease lapsed is not taken. A heartbeat older than
    `CLARITY_BROWSER_LEASE_STALE` (2 minutes) also releases it, which covers crashes. At boot, leases
    and queue places of runs that are no longer running are cleared.
  - On release the run's last address and title are kept in `browser_resume`, with one line on where
    it was in a sign-in. The first `look` of a later run of the same task (the same task text)
    shows them; they are never reopened.

Follow-ups. The harness keeps tool results append-only, because the prompt cache depends on it, so
older `look` results are not collapsed. The screenshot is always of the selected tab: the MCP has
no per-tab capture and no coordinate click, and `send_bidi_command` is not in its default tool
preset.

### Nudges (check-ins the lead schedules for itself)

Triggers only fire on new data, and `create_reminder` only alerts the person's phone. A **nudge**
wakes Clarity itself at a time, so it can ask how something is going ("nudge me throughout the
day…"). Lead-only tools (`agent/Nudges.kt`):

- `nudge(at, note, taskId?)`: `at` is the person's local time (ISO, or `HH:MM` for today); `note`
  says what to ask. Refused when it is not in the future, more than 7 days ahead, 10 are already
  pending, its day already has 12, or one for the same task (or, with no task, the same note) is
  within 10 minutes. A task that is already done or cancelled is refused too.
- `list_nudges`, `cancel_nudge(id)`.

Each nudge is a row in `nudge` (`V24`): `due_at`, `note`, `task_id`, `status`
(`pending` → `fired` | `skipped` | `missed` | `cancelled`), `reason`, `resolved_at`. The
`nudges` job (every 30 s, `scheduler/NudgeJob.kt`) runs a pass in the background: it claims due
pending rows with `FOR UPDATE SKIP LOCKED` and resolves each in the same transaction, so two
instances never resolve one twice. Per nudge, in code and without a model call: more than 2 h late
(the backend was down) → `missed`; linked task done or cancelled → `skipped`; 12 already fired
today → `skipped`. When the person wrote in the last 20 minutes or a session is live, one
Decision-1 question ("has the person already given the update this check-in asks for?", the
conversation since the nudge was set under `untrusted`) skips it at p ≥ 0.8; any failure fires it.
Otherwise it **fires**: a `nudge_due` inbox item (normal priority, dedup key `nudge:<id>`) tells
the lead "Nudge you set at <time>: <note>" plus the task's title and status. A fired nudge posts
nothing by itself; the lead decides whether to ask one short question through `send_to_user`, so
the notify policy and protected-time holds apply as for anything else.

Every resolved row is a timeline entry for the app (the Agent's Path): `GET /api/v1/nudges`
returns `Nudges(pending, history)` (shared `feed/Nudges.kt`; `history` newest first, `?since=`
an ISO instant, default the last 7 days), and Today carries the pending ones as `nudges`.

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

- **One provider**: every model runs on OpenRouter (`OpenRouterModelGateway`, Chat Completions,
  streamed). Claude is reached there as `anthropic/...`; the direct Anthropic API is gone.
- **Cache-stable prefix**: tools (sorted, byte-stable) → frozen system prompt (persona, rules,
  tool guidance) → constitution (a system block that changes only when amended) → messages.
  The person's profile facts are part of the constitution block. Models that cache only at
  explicit breakpoints (Claude, `usesCacheBreakpoints`) get them through OpenRouter's
  `cache_control` (prompt caching guide): the system message as text parts with
  `{"type":"ephemeral"}` (plus `"ttl":"1h"` for a 1 hour role) on the static and constitution
  blocks, the same on the user text where the previous request ended, and a top-level
  `cache_control` for automatic caching of the tail; at most four. A breakpoint that falls on tool
  results is left out (the guide documents system and user content only). Other models (MiMo,
  DeepSeek, Gemini) cache on their own and get no `cache_control`.
- **Tools**: all of a run's tools are sent with every request (no tool search); the lead has no
  MCP tools, so the list is small and stable.
- **Compaction**: the lead compacts client-side: after a turn whose last call's prompt reached
  `compactAtTokens`, one call on the same model (same system blocks and tools, so it reads the
  cached prefix on Claude) writes a summary, appended to the journal as a user message starting
  with `[clarity:summary]`. The journal stays append-only; requests start at the latest summary.
  `compactAtTokens` is the main cost lever for a model without a prompt cache: every turn resends
  the whole context since the last summary.
- **Stored Anthropic history**: journals written while the lead ran on the Anthropic API keep
  Anthropic's blocks (`Block.Raw`: thinking with signatures, redacted thinking, `compaction`,
  `server_tool_use`, `tool_search_tool_result`). They are never rewritten; they are read at request
  time: the latest `compaction` block is a compaction point (`contextSince`, the conversation
  archive) and is sent as a `[clarity:summary]` user message with its readable `content`
  (`UNREADABLE_SUMMARY` when it has only encrypted content); every other Anthropic-only block is
  left out of the request.
- **Time on the person's clock**: storage keeps UTC instants, but every time an agent sees or
  gives is in the person's home zone (`Settings.homeZone`), through one formatter/parser
  (`shared/time/LocalTimes`): out as `2026-10-26T19:00-04:00 (Mon)` (all-day events as dates), in
  as ISO with an offset/`Z`, a local date-time without offset (wall clock in the home zone, gaps
  and overlaps resolved like `ScheduleClock`), or a plain date where a day is enough. The current
  local time is never in the cached system prompt: it heads each `[inbox, now …]` message, and a
  turn of only the person's words gets a `[now …]` line when the last one is over 10 minutes or a
  local day old. Helpers get it at the top of their task; the guard model gets a `<local_times>` note.
- **Big outputs go to files**: tool results above a size limit are written to the sandbox
  workspace and the tool returns a path + summary.
- **Model roles** (config, per role, OpenRouter model ids): `lead` (default
  `xiaomi/mimo-v2.6-pro`; effort `low` during a live call for latency, `medium` otherwise),
  `helper`, `guard` and `triage` (`deepseek/deepseek-v4.1-flash-1017`). Env/yaml set the
  deploy-time defaults (`CLARITY_AGENT_<ROLE>_MODEL`); the app can override a role's model and
  effort at runtime (`model_role_override`, `/api/v1/settings/models`; the wire still carries a
  fixed `provider: "openrouter"` for older apps, and a `provider` they send is ignored). A run
  never switches model halfway: the lead picks up a change at its next turn, a helper keeps the
  settings it started with, guard and triage read them per call. A switch costs one cold prompt
  cache (the cache is per model). Effort is sent per request as `reasoning.effort` (the last
  effort message in the thread, else the role's); Claude takes `low` to `max`, other models are
  capped at `high`. OpenRouter's `reasoning_details` are kept (tagged with provider and model) and
  replayed only to the model that wrote them.
- **Cost**: each `model_call` row's cost is what OpenRouter reports (`usage.cost`); there are no
  price tables.
- Refusals (`finish_reason: content_filter`) are surfaced as a card, never retried silently.

### Memory

All in Postgres (backed up with everything else), all reachable through tools. The prompt holds
only small, stable pieces; everything else is recalled on demand (the skills pattern: an index in
the prompt, contents loaded when needed), which matters more the sooner the lead compacts.

| Tier | What | In the prompt? |
|---|---|---|
| **Constitution + profile** | Lines with reasons, hard lines, autonomy tiers, people who get through, protected times, plus short profile facts | Always (system block) |
| **Collections** | Named topics of notes (`memory_collection`, `memory_note`): people, places, projects, detailed preferences, how-tos | Only the index (name + one-line description, capped at 40), as a system message when it changes; `open_collection` reads one, `save_note` adds/edits/removes, `manage_collection` re-describes, renames, merges |
| **Journal** | Dated entries the agent writes about what happened and was decided (the morning call writes one) | Today's and yesterday's; older via search |
| **Scratch** | Short-lived notes with a TTL (hours) | Live ones, as a system message when changed |
| **Conversation archive** | The lead thread from before its latest compaction point (a client-side summary, or a stored Anthropic compaction block), as readable chunks (`conversation_chunk`) | Search only |
| **Everything else** | Ingested records, documents, files in the sandbox | Search tools only (`search_memory`, domain tools, shell in sandbox) |

The memory context and the collection index are each re-sent only when they differ from the last
copy the model can still see (the thread since its latest compaction point), so they never churn
the cache, survive restarts without repeating, and come back after a compaction.

- **Search** (`search_memory`, `read_memory`): journal, collection notes, archived conversation,
  feed items, messages, calendar events and helper results. Hybrid: Postgres full-text plus,
  when available, vector similarity, fused by reciprocal rank. Each hit has a time and a ref
  (`conversation:12`); `read_memory(ref)` reads it in full (bounded) and names the neighbouring
  conversation chunks. Text from outside (messages, events, helper results, tool output in the
  archive) is quoted as `<untrusted>`, and both tools taint the run like other readers of outside data.
- **Archive**: the `memory-index` job (every 10 minutes; the first run backfills) renders the span
  between the last archived message and the latest compaction point: the person's words, the
  agent's text, what it sent the person, tool names with short arguments, truncated tool results.
  Thinking and other raw blocks, summaries, the memory context and every vault/credential/code tool
  (`request_credential`, `list_credentials`, `ask_code`) are left out. Idempotent by `(run_id, from_seq)`.
- **Embeddings**: OpenRouter `POST /embeddings`, `qwen/qwen3-embedding-8b` (open weights, $0.01/M
  tokens), only on ZDR endpoints that do not collect data, from DeepInfra then Nebius with no other
  fallback. Vectors are cut to 1024 values and renormalized (Matryoshka; the endpoints take no
  `dimensions` parameter) and stored as float32 `bytea` in `memory_embedding` (no pgvector); the
  backend keeps them int8-quantized in memory (~1 KB per record) and ranks by brute-force cosine.
  The same job embeds new and edited rows in batches and drops vectors of deleted ones; every
  request is a `model_call` row with role `embedding`. On only with an OpenRouter key
  (`CLARITY_MEMORY_EMBEDDINGS=false` turns it off, `CLARITY_MEMORY_EMBEDDINGS_MODEL`,
  `CLARITY_MEMORY_EMBEDDINGS_DIMENSIONS`); any error or rate limit pauses it (5 min, doubling to
  1 h) and search falls back to full-text, so a turn never fails over it.
- **Secrets**: a simple detector (`SecretDetector`: passwords, API keys and tokens, private keys,
  one-time codes, random-looking keys) rejects such notes and redacts such text from the archive
  and from anything sent for embedding. Never-store topics from the constitution are refused too.

Profile facts are proposed by the agent (`remember_fact`). A daily helper (`memory-gc`) tidies
memory: merges and expires profile facts, merges duplicate collections, keeps descriptions
accurate, and files durable facts from the week's journal into collections. The constitution
itself changes only through an amendment the person approves.

### Domain tools (backend, also served at `/mcp`)

Reads: `list_messages`, `list_calendar_events`, `search_contacts`,
`search_documents`, `read_document`, `search_memory`, `read_memory`, `open_collection`, `read_journal`, `today`.
Own state (`WRITE`, allowed alone): `create_trigger`, `delete_trigger`, `remember_fact`, `save_note`, `manage_collection`,
`write_journal`, `set_scratch`, `update_plan`, `update_task`, `send_to_user`, `delegate`, helper
controls. External (`EXTERNAL`, always via guard + outbox): `save_draft`, `send_email`,
`create_event`, `move_event`, `respond_to_invite`, `create_reminder`, `set_do_not_disturb`,
plus the helper's acting browser steps (`act`, `sign_in`), which pass the browser gate (rules,
Jev on the screenshot, the fast triage) with page context; anything that sends, submits, buys or deletes is held. Sandbox tools are ungated (no
network, no credentials).

## Governance

### Constitution

Versioned document (`constitution_version`), sections as in the design: what I value, hard
lines (never crossed), how much I do alone (**autonomy tiers** per action kind: *do it, then
tell me* / *ask me first* / *never alone* (only with a yes on the phone, fingerprint or face) /
*never* (refused even if asked, until amended)), when things collide, who gets through, when I get it wrong.
Every line keeps its **because** (the reason is what lets the agent use judgment where no rule
applies). Structured parts (autonomy tiers per action kind, people who get through, protected
time blocks) are machine-readable so the guard and the notification policy can apply them
deterministically; the rest is natural language.

### The guard (auto-verification)

Every proposed action (`ProposedAction`) goes through `Guard.check(action, constitution, provenance)`:

1. **Deterministic rules first** (pure): the action kind's autonomy tier from the person's own
   interview → `ALLOW` / `ASK` / `DENY` (*never*). Nothing is hard-coded: one person may let the
   agent pay for things with a yes on the phone, another may rule out sending email entirely.
   Kinds the interview didn't cover default to *ask first*. The only fixed floor is mechanical:
   using a stored credential or a two-factor code always needs the phone (*never alone*), since
   only the phone can open the vault; recipients > 1 for "sent as me" → `ASK`;
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
Proposals do not pile up: proposing an equal change (same line, normalized text, tier change and
held action) returns the one already waiting; saving one supersedes waiting proposals with the same
text or for the same line (as does any change to that line); a proposal expires when the amend talk
that made it ends unsaved (only that talk can save it) or after 7 days. A startup pass and a
10-minute job apply the same rules. Closed rows keep their history (`superseded`/`expired` with a
`closed_reason`) and reach the app as `discarded` with `closedReason`.

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
  One form per site (registrable domain) and kind is open at a time: asking again returns it.
  Saving a credential closes the other open forms for that site and kind (`superseded`), and
  forms nobody fills in close after 7 days (`expired`); the app sees them as cancelled with a
  `closedReason`.
- **Using a credential.** The agent never sees a secret. It writes placeholders such as
  `[secret:credit_card_1_cvv]` (credential name + field) into browser tool inputs, e.g.
  `act(intent="the password field", value="[secret:bank_login_password]")`, or lets `sign_in`
  write them. The backend's browser tool wrapper finds the
  placeholders, checks there is a live **grant**, substitutes the values, and calls the browser
  MCP; the model's tool call, the journal and logs only ever hold the placeholder.
  A grant is created by an approval: the first placeholder for a credential on a site makes a
  held approval with context (which site, what for, which credential, the agent's reason;
  the guard still applies, and any stored credential needs the phone). **Yes** requires
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
  accessibility snapshot shorter). Fewer obstacles: Firefox's own cookie-banner handling rejects
  banners it has rules for (`cookiebanners.service.mode` 1, including its generic rules for common
  consent managers such as OneTrust), and uBlock Origin runs its default lists plus four
  cookie-notice lists, set by enterprise policy (`3rdparty` → `toOverwrite.filterLists`):
  `adguard-cookies`, `ublock-cookies-adguard`, `fanboy-cookiemonster` and `ublock-cookies-easylist`.
  The image build serves pinned, sha256-checked copies of those lists to uBO during the prewarm run,
  so they work from the first page after a cold start; uBO updates them itself through the egress
  proxy afterwards. The annoyance lists (uBO Annoyances, EasyList Annoyances, AdGuard Popup
  Overlays and Other Annoyances, Social Widgets) are left out on purpose. They hide sites'
  login modals and Google's sign-in prompt, and AdGuard Popup Overlays blocks Google's
  sign-in script on some sites. The browser is driven by Mozilla's Firefox DevTools MCP server over WebDriver
  BiDi; exposed as streamable HTTP. Reaches the public internet only through the egress proxy;
  no access to other internal networks. Only helpers browse, through the pilot (see "The
  helper's browser"): Clarity's observation script for text, screenshots for Jev. The lead never
  browses. `computers/browser/pilot-e2e/` starts a throwaway copy with a test login site for the
  pilot's live test.

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
  non-internal **egress network per service** that needs the internet (each connector for its
  upstream, caddy; the browser and backend go through the egress proxy instead). The sandbox has no egress network.
- **Egress proxy** (`egress`, Squid): the browser and the backend have no direct route out; they
  reach the internet only through this proxy, which refuses private, loopback and link-local
  destinations (it resolves names itself, so DNS tricks don't bypass it). The browser may reach
  any public host on 80/443; the backend only `openrouter.ai` (plus `CLARITY_BACKEND_EGRESS_ALLOW`). Connectors keep their own
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
  (`-Pclarity.liveEvals`, `OPENROUTER_API_KEY`) that runs recorded scenarios against the real models and grades with a
  rubric. Mistakes found in use become new scenarios.

## CI/CD and branches

- `dev` is the integration branch (checked out locally); `main` is release.
- `ci.yml`: one job, on pushes to `dev` and PRs from other branches (a PR from `dev` is skipped,
  its commits were tested on the push): JVM + Android build, lint and tests. Docs-only changes
  don't run CI. The container smoke tests (`computers/*/smoke.sh`) and the end-to-end stack test
  (`e2e/run.sh`) are too slow for CI and are run locally before a release.
- `android-release.yml` ("Release"): on push to `main`, two jobs: multi-arch (amd64/arm64) images
  to `ghcr.io/robog-two/clarity-<service>` with provenance attestations, and the signed APK as a
  GitHub Release.
- Dependabot keeps Gradle, GitHub Actions and container base images current (targets `dev`).
