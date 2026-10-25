# Workstream `sessions`

Lane: `backend/.../backend/sessions/**` and `backend/.../backend/setup/**` (new), migration
**`V6__sessions.sql`**, session tools registered with the lead (a new file
`agent/SessionTools.kt` + a minimal registration change), DI wiring, their tests.

Read "Sessions and voice", "Agent runtime" and "Governance" in `ARCHITECTURE.md`; the shared
`session`, `feed` and `governance` contracts; the prototype flows in
`docs/design/prototype/Onboarding.dc.html` (values interview: the seven questions in `qs()`,
reword, captured lines, the constitution doc sections) and `Call.dc.html` (the four stages, what
each produces, the call-ended setup list); and the code you build on: `agent/LeadAgent.kt`,
`InboxItem.kt` (`SessionEvent`, `System`), `InboxStore.enqueue`, `AgentToolbox.kt`,
`OwnStateTools.kt`, `ExternalTools.kt`, `ThreadAndTasks.kt`, `ToolSupport.kt` (`SettingsProvider`),
`feed/**`, `governance/ConstitutionStore.kt`, `governance/ActionGate.kt` (`PersonZone`),
`scheduler/Scheduler.kt` (`ScheduledJob`), `agent/ModelGatewayFactory.kt`.

## Build

1. **Settings + setup.** `settings` (single row, `Settings` JSON) with a `SettingsStore` that
   replaces the default `SettingsProvider` and binds `PersonZone` to `Settings.timeZone`. Routes:
   `GET/PUT /api/v1/settings`, `GET /api/v1/setup` (`SetupStatus`), `PUT /api/v1/setup/model-key`
   (validate with one Models API call through the SDK, store per `ARCHITECTURE.md`, never echo the
   key; return `SetupStatus`). Setting a key at runtime must **enable the lead without a restart**
   (make the gateway factory/lead pick up a newly stored key; a key from the Podman secret wins).
2. **Sessions core.** `agent_session` table; `SessionStore`; pure state machines per kind:
   - `MORNING_CALL`: CLEAR → CHOOSE → COMMIT → HAND_OFF → ENDED; `Snooze(min)` → SNOOZED
     (records the next call time); `Next` while BUSY is rejected (409).
   - `VALUES_INTERVIEW`: question 0..6, each ACTIVE → BUSY (agent drafting the line) → ACTIVE with
     `capturedLine`; `Reword` → BUSY → new `capturedLine`; `Next` saves the line and advances;
     after the last question → ENDED with constitution v1 written.
   - `AMEND`: from a held action or from the constitution screen; ends when an amendment is
     proposed and the person saves/discards it (governance routes), or on `End`.
   Routes: `POST /api/v1/sessions` (`StartSession`), `GET /api/v1/sessions/{id}`,
   `POST /api/v1/sessions/{id}/control` (`SessionControl`), `GET /api/v1/sessions?active=true`.
   Every transition publishes `FeedEvent.SessionChanged` and enqueues an `InboxItem.SessionEvent`
   whose text is the **playbook** for the new stage/question (static text per stage, see 4).
   Only one active session at a time; starting a new one ends the previous one.
3. **Session tools for the lead** (loaded only while a session is active is NOT allowed, the
   tool list must stay byte-stable: always loaded, and they return a clear error when no
   matching session is active):
   `capture(items)` (CLEAR: emits/updates a `Chips("captured · N")` card),
   `set_priorities(important, also?)` (CHOOSE: "today's two"), `set_plan(ifThens)` (COMMIT: `Plan`
   card), `hand_off(alone: [...], review: [...], parked: [...])` (HAND_OFF: creates `AgentTask`s,
   "I'll do" + "parked until tomorrow" chips; the actual actions are then proposed with the
   external tools as usual), `propose_line(section, text, because, tiers?, hard?)`
   (interview: sets `capturedLine`, emits the "captured as" card), `finalize_constitution(lines,
   autonomy, getsThrough, protectedTimes, neverStore, profile)` (writes v1 via
   `ConstitutionStore`), `propose_amendment(lineId?, text, because, reason)` (AMEND),
   `stage_done()` (marks BUSY → ACTIVE so the app's "next" button enables). Cards they emit carry
   the session id. Store the day's outputs in `day_plan` (date, priorities, plan, parked, called).
4. **Playbooks** (`sessions/Playbooks.kt`, static strings): what the agent does in each stage,
   following the prototype: CLEAR ("what's on your mind? say everything"; look up each mention
   with the domain tools and show a `Context` card per lookup; capture), CHOOSE ("ignoring
   deadlines, which one would you regret not moving forward?"; at most one more), COMMIT (when
   and where; the likely obstacle; if-then plans; check the calendar), HAND_OFF (what can I do to
   clear the way; propose actions; say "nothing goes out as you without your review"; park the
   rest). Values interview: the seven themes and questions from `qs()` in the prototype, asked
   one at a time; each answer becomes one line with its reason in the person's words; question 3
   yields the three autonomy tiers. Keep answers short: the person is listening on a phone.
5. **Live call latency.** While a `MORNING_CALL` is active, run lead turns at effort `low` using
   the per-message effort beta (`mid-conversation-output-config-2026-07-01`, an effort-only
   system message with empty content; check the SDK supports it, see the harness KDoc), and
   switch back after the call. If the SDK can't, keep one effort and report it.
6. **Jobs.** `morning-call-prep` `ScheduledJob` at call time − (headsUp or 10) min on the call
   days in the person's zone: enqueue a low-priority `System` item asking the lead to prepare
   (yesterday's plan and parked items, today's calendar, new mail) and say nothing to the person;
   this also warms the cache. `snooze-recall` when snoozed.
7. **Today.** `GET /api/v1/today` (`Today`): from `day_plan`, open `agent_task`s, held count,
   parked items; empty before the first call of the day.

## Tests

Pure: every state machine (incl. illegal transitions, snooze, reword, BUSY gating), call-time and
prep-time computation across zones/DST/day sets, playbook selection. Scripted-gateway scenarios:
a full morning call (four stages → plan, tasks, an action held, parked items, Today reflects it);
a values interview (seven lines → constitution v1 with tiers/people/protected times); an amend
session from a held action → proposed amendment. Ktor tests for settings/setup (key validation
via a fake models endpoint, runtime enable) and session routes. Testcontainers for the stores.
