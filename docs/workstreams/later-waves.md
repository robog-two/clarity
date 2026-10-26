# Waves 2 and 3 (outline)

These get full specs after wave 1 merges, so they can name the real interfaces. Scope is fixed.

## `lead` (wave 2)
Postgres `RunJournal`; the lead thread service (one row in `agent_thread`, resumes on boot);
`Inbox` + pure `planNextTurn` (user input pre-empts, background batched, priorities);
injected events as system messages with `<untrusted>` quoting; helpers (`delegate`,
`list_helpers`, `message_helper`, `cancel_helper`) as durable runs; memory tiers (profile facts
via `remember_fact` → proposed into constitution profile, journal, scratch with TTL,
`search_memory` over Postgres FTS); `send_to_user` → feed store (seq) + SSE `GET /api/v1/events`
+ `GET /api/v1/feed` + `POST /api/v1/input`; durable scheduler (`next_run_at`, `SKIP LOCKED`)
replacing the periodic review job; idle compaction while the cache is warm; prompts
(persona, cheapest-first ladder, untrusted-data rule); external domain tools calling `ActionGate`;
large tool results to the sandbox workspace.
Harness follow-ups from wave 1: provide the Postgres `RunJournal`; build `RunRequest`s from
`AgentConfig.roles` (model, effort, maxTokens, `CachePolicy(ttl)`, `CompactionConfig`); remove the
old `REQUIRE_APPROVAL` / `ApprovalRequired` path once external tools go through `ActionGate`.

## `connectors` (wave 2)
Noun `document` (payload, normalize, dedup keys, tables, reader, domain tools);
`connectors/mail` (Angus Mail IMAP IDLE/poll + SMTP; save_draft = IMAP APPEND to Drafts;
send_email with Message-ID from the idempotency key; threading headers) and `connectors/dav`
(dav4jvm via JitPack with a content filter, ical4j, ez-vcard; calendars, contacts,
WebDAV documents with text extraction; create/move event, create_reminder as an event with a VALARM, respond to invite via RFC 6638 when
the server supports it, else ask); sealed secret config (X25519 sealed box, libsodium-compatible);
`POST /api/v1/connectors/{id}/test` round trip; compose services + profiles.

## `app-core` (wave 2)
Material 3 theme from the prototype's `.app` variables (light; derive dark), Roboto Flex +
Libre Caslon Text (downloadable fonts or bundled OFL fonts); Ktor client + device JWT + SSE feed
client with resume; Room feed cache; one `CardView` renderer for every `Card`; voice module
(on-device `SpeechRecognizer`, partial/final Flow, mic state chips "listening / mic on / muted");
onboarding exactly as `docs/design/prototype/Onboarding.dc.html` (server, model key, connector
setup with config forms from manifest JSON schema and sealed secrets, grants, sync/test status).

## `sessions` (wave 3)
Session state machines (morning call stages, values interview with 7 questions and reword,
amend), their playbooks as system messages, stage tools for the lead (`advance_stage`,
`capture`, `set_priorities`, `set_plan`, `hand_off`, `propose_line`, `propose_amendment`),
`Today` projection, settings + setup routes, morning-call prep job, snooze.

## `app-screens` (wave 3)
Call flow as `Call.dc.html` (exact alarms for heads-up + call, `CallStyle` full-screen
notification, incoming / snoozed / call / ended screens); app as `App.dc.html` (Today, draft
review, Ask, Held, Amend, Settings, Constitution) with the bottom navigation; phone connector
(SMS read, do-not-disturb action via notification policy access; no reminders in the app).

## `vault` (wave 3, backend + app)
Credential vault per "Credential vault" in `ARCHITECTURE.md`: envelope format and field
validators in `:shared`; backend store, `request_credential` tool, `[secret:<name>_<field>]` placeholder substitution in
the browser tool wrapper, 5-minute per-site grants created by held approvals, per-run scrubber wired into the harness's
tool-result path; app Keystore vault key with per-use `BiometricPrompt`, the credential form card,
and the approval screen's authenticated "yes".

**Browser gate** (same wrapper as the placeholder substitution; today the lead runs every MCP
tool with `ToolPolicy.AllowAll`): classify the browser MCP tools. Read-only (navigate, snapshot,
screenshot, scroll, list/select page, wait) run freely. Acting tools (click, type, fill, select
option, press key, upload, drag) carry the page URL and the target element's role/name from
the last snapshot into a fast guard check (`triage` role, constitution + what the agent says it
is doing): ordinary navigation clicks and form typing pass; anything that sends, submits,
buys, books, deletes, accepts terms or changes account settings becomes a held action with
context; payment pages are never alone. `evaluate_script` and other raw-script tools are hidden.
Sandbox tools stay ungated (no network, no credentials). Then remove the unused
`REQUIRE_APPROVAL`/`ApprovalRequired` path from the harness.

## `push-evals` (wave 3)
Push over the SSE feed from a foreground service (see "Android app"); scenario evals over the scripted gateway
(inbox pre-emption, guard tables, call happy path, held → amend, credential scrubbing); an end-to-end compose test;
README/deploy docs.
