# Workstream `app-screens`

Lane: `android/**`. Read "Android app", "Sessions and voice", "Governance" in `ARCHITECTURE.md`,
the shared `feed`, `session`, `governance` contracts, the existing app (`android/app/src/**`:
theme, `ClarityApi`, `FeedSync`, Room cache, `CardView`, voice, onboarding, `MainScaffold`), and the
prototypes `docs/design/prototype/Call.dc.html` and `App.dc.html` (layout, copy, flow). Material 3,
styled after the prototype, as the existing theme does. The backend for sessions/settings/today
is being built in parallel: code against the contracts and `ApiPaths`, test with fakes, treat
404 as "not available yet".

## Build

1. **Call scheduling.** From `Settings.morningCall` (fetched and cached; re-scheduled on change,
   boot, time zone change, and app update): exact alarms (`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`
   with the permission flow) for the heads-up notification ("We're scheduled in 10" style copy
   from the prototype, but with the real first line from the agent if the prep job produced one
   via the feed, else a fixed line) and for the call. The call is a `CallStyle` notification with
   a full-screen intent (`USE_FULL_SCREEN_INTENT`, handle the Android 14+ permission check and
   fall back to a high-priority notification), answer / not now actions. Mode `NOTIFY` posts a
   normal notification instead. "Not now" sends `SessionControl.Snooze(15)` and reschedules.
2. **Call screens** (`Call.dc.html`): incoming, snoozed, in-call, ended. In-call: header with mic
   chip and timer, the four stage segments from `Session.stage`, the session feed (items with this
   `sessionId`, rendered with `CardView`, live partial bubble from `SpeechInput`), mute and the
   primary button (`that’s all` / `next` / `next` / `end call`) sending `SessionControl.Next`/`End`,
   disabled while the session is `BUSY`. Starts a `MORNING_CALL` session on answer. Keeps the
   screen on and shows over the lock screen during the call; an ongoing-call notification while
   in the background. Ended: plan, setting-up task list with live statuses (`TaskChanged`),
   "open the app", "replay" (shows the session's items again; no new session).
3. **Today** (`App.dc.html` today): date, plan box, "I’m doing" rows from `Today.doing` with
   statuses (drafted → review button), held row with count → Held, parked line. Pull to refresh;
   live updates from the feed.
4. **Draft review**: for a held `SaveDraft`/`SendEmail`: what they asked (context), what I think
   (`agentView`), why I'm asking (`verdict.rationale`), the draft (editable text field), "checked
   against your lines", **talk about it** (voice input into the AMEND session for this held
   action; agent revises → the held action updates), **send** (`HeldDecision.Approve(edited?)`).
   Toast "Sent to Dana." style confirmation on executed status.
5. **Ask**: the main feed (items without a session) with `CardView`, empty-state copy and
   "try saying" suggestions while empty, a text field plus **talk** button (voice → `UserInput`),
   proposals rendered inline with not now / approve buttons.
6. **Held**: list of held + blocked items (when, summary, status), tap → AMEND session view;
   "see allowed actions" toggles the activity list. Amend view: the held item box, the agent's
   question, voice answer bubbles, proposed change box (old struck through, new), keep as is
   (`HeldDecision.Keep`) / save rule (governance amendment save route) with a toast
   "Rule saved. Constitution vN.".
7. **Settings**: morning call (time, days, heads-up, call vs notify), voice (mic always on vs push
   to talk), constitution (→ constitution view with "amend by talking"), held actions (evening batch
   vs right away, urgent push), sources on the server (from connectors, last sync) and on the
   phone, forget this server, redo onboarding.
8. **Phone connector**: the phone as connector `device-<id>`: SMS (READ_SMS) sync in WorkManager
   to `/ingest/v1/batches` as `MessagePayload(channel = SMS)` with a cursor, and the action loop
   for `SET_DO_NOT_DISTURB` (notification policy access permission flow; allow-list people from
   the action; schedule turning it off at `until`), reporting results. Manifest `handles` =
   `SET_DO_NOT_DISTURB`. No reminders in the app.
9. **Bottom navigation** with "held · N" badge from the feed; deep links from notifications into
   Today, a held item, or the call.

## Tests

JVM unit tests: alarm time computation (zones, DST, days, heads-up), notification/permission
decision logic, every screen's view model with fake repositories (call stage flow incl. BUSY
gating and snooze, draft edit → approve with edited action, amend flow, settings round trip),
SMS → payload mapping and cursor, DND action handling. `./gradlew assembleDebug
testDebugUnitTest lint` in `android/` must pass. Machine limits per the README (`-Xmx2g`).
