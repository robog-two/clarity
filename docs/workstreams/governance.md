# Workstream `governance`

Lane: `backend/.../backend/governance/**` (new), `backend/.../backend/connectors/**` (outbox
routes), a new Flyway migration `V3__governance.sql`, `connector-sdk/**` (action polling),
`connectors/example` (handle one action to prove it), their tests.

Read "Governance", "Action outbox" and "Connectors" in `ARCHITECTURE.md`, and the shared types in
`shared/governance/Governance.kt` and `shared/action/Actions.kt`.

## Build

1. **Schema** (`V3__governance.sql`): `constitution_version` (version, document jsonb, created_at),
   `amendment`, `held_action`, `action_outbox` (id, connector_id, action jsonb, idempotency_key
   UNIQUE, status queued/leased/done/failed, lease_until, attempts, next_attempt_at, result
   jsonb), `activity`.
2. **ConstitutionStore**: current, history, `saveVersion` (version + 1), apply amendment.
   Seed: none (onboarding creates v1). Routes: `GET /api/v1/constitution`
   (current + `?version=`), `POST /api/v1/constitution` (create v1 from the interview, used by
   `sessions`), `POST /api/v1/constitution/amendments` (save a proposed amendment), list amendments.
3. **Guard** (`Guard.check(action, constitution, provenance): GuardVerdict`):
   - pure `deterministicVerdict(...)` per `ARCHITECTURE.md` (hard-line kinds, autonomy tier,
     multi-recipient "as me", untrusted provenance, protected time for calendar writes into a
     protected block, `neverStore`);
   - model check through an injected `GuardModel` interface (one structured call: action,
     constitution, agent reason → decision, line ids, rationale). Implementation over the
     harness `ModelGateway` with the `guard` role (if the `harness` workstream's
     `complete(...)` is not merged yet, write against the current `generate` and keep the
     adapter small). The model can only tighten.
   - combine: `max(deterministic, model)` with DENY > ASK > ALLOW. Model failure → ASK.
4. **ActionGate** (what external domain tools call):
   `propose(action, agentView, context, provenance, urgency): ProposalOutcome` →
   `Allowed(actionId)` (outbox + activity) | `Held(heldActionId)` | `Blocked(heldActionId)`,
   each with a short text for the tool result the model sees ("Held for review: ...", with the
   rationale). Idempotent by `action.idempotencyKey` (same key → same outcome).
5. **Held actions**: store, list (`GET /api/v1/held?status=`), get, decide
   (`POST /api/v1/held/{id}/decision` with `HeldDecision`): Approve (edited version re-checked
   only against hard lines) → outbox; Decline/Keep → status. Publish change events on an
   in-process `GovernanceEvents` flow (the feed will forward them as `FeedEvent.HeldChanged`).
   `GET /api/v1/activity`.
6. **Outbox + routing**: connector selection by the `handles` in its manifest (exactly one
   connector should handle a kind; if several, pick by config preference, else oldest);
   no handler → held action fails with a clear message. Routes `GET /ingest/v1/actions?wait=`
   (long-poll up to 30 s, leases actions for 2 min), `POST /ingest/v1/actions/{id}/result`
   (idempotent; retryable failures back off exponentially, max 5 attempts). Result events on a
   flow (`ActionResults`) for the lead to consume. Device connectors (phone) use the same routes.
7. **Notification policy** (pure, `governance/NotifyPolicy.kt`): given urgency, sender/people,
   constitution (gets-through, protected times), settings (`batchLowUrgency`, `pushUrgent`,
   evening time) and now → `PushNow | ShowInApp | HoldUntil(instant) | EveningBatch`.
   No delivery here (push is a later workstream); expose the decision.
8. **connector-sdk**: manifest `handles`; `on<T : ProposedAction> { action -> ActionOutcome }`
   handlers; a long-poll loop running beside the sync loop with retry/backoff; a local ledger
   (in `StateStore`) of executed idempotency keys so a re-delivered action returns the stored
   outcome. Update the example connector to handle `CREATE_REMINDER` by logging it.

## Tests

Pure tests for `deterministicVerdict` (table-driven over every `ActionKind` × tier × provenance),
verdict combination, `NotifyPolicy` (incl. time zones and blocks crossing midnight). Route tests
for held decisions and the action long-poll/result flow (lease expiry, duplicate result,
retry). Testcontainers test for the outbox idempotency. SDK test for the action loop with a
mock engine.
