# Workstream `lead`

Lane: `backend/.../backend/agent/**`, `backend/.../backend/feed/**` (new),
`backend/.../backend/scheduler/**`, migration **`V4__lead.sql`** (V5 is reserved for
`connectors`, V6 for `sessions`), DI wiring, `application.yaml`, their tests. Small harness
changes only where listed.

Read "Agent runtime" (all of it), "Governance" and "Credential vault → Scrubbing" in
`ARCHITECTURE.md`, the shared `feed`/`governance`/`session` contracts, and the code you build on:
`harness/**` (journal, interrupts, roles, `RunRequest`), `agent/**` (dispatcher, domain tools,
prompts, run store, MCP tool sources), `governance/ActionGate.kt`, `GovernanceEvents.kt`
(`GovernanceEvents`, `ActionResults`), `NotifyPolicy.kt` (`notifyDecision`), `scheduler/**`,
`triggers/**`, `ingest/IngestEventBus.kt`.

## Build

1. **Persistence** (`V4__lead.sql`): `agent_thread` (the one lead thread: id, created_at,
   compacted_at), `agent_run` extended for helpers (parent run, role, status, result),
   `agent_journal` (run_id, seq, message jsonb; append-only), `inbox_item`, `feed_item`
   (seq bigserial, author, session_id, cards jsonb, streaming), `agent_task`,
   `memory_journal` (date, text, tsvector), `memory_scratch` (text, expires_at),
   `scheduled_job` (name, next_run_at, last_run_at, payload). Postgres `RunJournal`
   implementation of the harness interface.
2. **Feed.** `FeedStore` (append with seq, page after seq, update streaming item), `FeedHub`
   (fan-out to SSE subscribers; replay from `after`), routes `GET /api/v1/feed?after=`,
   `GET /api/v1/events?after=` (Ktor SSE; event name = `FeedEvent` type; heartbeat comment every
   20 s; close on JWT expiry), `POST /api/v1/input` (idempotent by `ClientInputId`: store a USER
   item with a `Said` card, then enqueue an inbox item). Forward `GovernanceEvents` as
   `HeldChanged`/`ConstitutionChanged`, task updates as `TaskChanged`, lead busy state as `Busy`.
3. **Inbox.** `InboxItem` sealed type (UserInput, SessionEvent, TriggerFired, JobDue,
   HelperResult, ActionResult, HeldDecided, ConstitutionChanged, System), each with priority
   and `untrusted` content parts. Pure `planNextTurn(pending, now): TurnPlan`: user input pre-empts;
   everything else is batched into one turn; low priority waits for idle. Rendering (pure):
   user input → user message text; everything else → one mid-conversation system message per
   turn, untrusted parts quoted as `<untrusted source="…">…</untrusted>` (escape any closing tag
   inside the content).
4. **LeadAgent** (a `BackgroundService`): owns the single thread. On boot, resumes from the
   journal (`Harness.resume`). Loop: wait for inbox → plan → `Harness.run` with the lead role's
   `RunRequest` built from `AgentConfig.roles` (model, effort, maxTokens, `CachePolicy(ttl)`,
   `CompactionConfig`), the system prompt blocks (static persona/rules block with the cache
   breakpoint, then the constitution + profile block), loaded vs deferred tools, and an
   interrupts flow fed by new user input. Mark inbox items consumed only after the turn that
   includes them is journaled. Effort `low` while a session of kind `MORNING_CALL` is active
   (via per-message effort if the SDK supports it without breaking the cache; otherwise keep one
   effort and note it). Disabled cleanly with no API key (feed shows a system card saying so).
5. **Prompts** (`AgentPrompts.kt`, static, byte-stable): who the agent is (talks like a capable
   colleague, short and plain, follows the constitution, explains holds), the cheapest-first
   ladder, the untrusted-data rule, how to use `send_to_user` (cards, urgency; silence is fine),
   how to delegate, how memory tiers work. Keep it short; good tool descriptions do the rest.
6. **Tools for the lead** (sorted, byte-stable): `send_to_user(cards, urgency, sessionId?)` (applies
   `notifyDecision`; push delivery itself is a later workstream, record the decision on the item),
   `delegate(task, tools?, role?)`, `list_helpers`, `message_helper`, `cancel_helper`,
   `remember_fact` (adds to the profile through a constitution amendment of kind profile, which
   the person can see; auto-saved since profile facts are not rules; skip anything matching
   `neverStore`), `write_journal`, `read_journal`, `set_scratch`, `search_memory` (Postgres FTS over
   journal + feed + messages), `update_task`. External domain tools that call
   `ActionGate.propose` with `Provenance(runId, untrustedInput = <did this run's context include
   untrusted parts>)`: `save_draft`, `send_email`, `create_event`, `move_event`,
   `respond_to_invite`, `create_reminder`, `set_do_not_disturb`; each returns the gate's text.
   Deferred behind tool search: MCP tools (browser, sandbox) and rarely used domain tools.
7. **Helpers.** A helper is a harness run with the `helper` role, its own journal, a task
   prompt, and a tool subset (reads + MCP tools; never `send_to_user`; external actions still go
   through `ActionGate`). Results come back as `HelperResult` inbox items. Durable: resumed on
   boot. Bounded concurrency (config, default 2).
8. **Durable scheduler.** Replace the in-memory interval scheduler with `scheduled_job` rows
   claimed with `FOR UPDATE SKIP LOCKED`; jobs enqueue inbox items. Jobs: `periodic-review` (as
   today), `evening-batch` (at `Settings.eveningSummaryAt` in the person's zone: summarize BATCH
   items into one feed item), `memory-gc` (daily helper that merges/expires profile facts and
   scratch), `idle-compaction` (if the person has been idle > 3 min and the last lead turn is
   < 55 min old with a 1 h cache, or < 4 min with 5 min: ask the harness to compact now).
   Morning-call prep is the `sessions` workstream; leave a hook.
9. **Large results**: wire the harness `ToolResultSink` to write the full text to the sandbox
   workspace through the sandbox MCP `write_file` tool when configured (else store it in
   Postgres and expose `read_result(id, range)`).
10. **Clean up**: remove the old `REQUIRE_APPROVAL`/`ApprovalRequired` path and the old
    one-run-per-task dispatcher once the lead and triggers go through the inbox. Bind
    `PersonZone` to `Settings.timeZone` if settings exist; else leave UTC and note it.

## Tests

Pure: `planNextTurn`, inbox rendering (untrusted quoting/escaping), idle-compaction timing,
scheduler claim logic. Scripted-gateway scenario tests: user input pre-empts a background turn;
three background events batch into one turn; a helper's result returns to the lead; an external
tool returns "held" text and the held action exists; restart mid-turn resumes from the journal
without duplicating an outbox action. Ktor tests: input idempotency, feed paging, SSE replay
from `after` and live delivery. Testcontainers for journal and scheduler claims.
