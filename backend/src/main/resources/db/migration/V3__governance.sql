-- Governance: constitution versions, amendments, held actions, the action outbox and activity.

CREATE TABLE constitution_version (
    version    integer PRIMARY KEY,
    document   jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE held_action (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- the proposing run's idempotency key: a retried run gets the same held action back
    idempotency_key text        NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- the effective action (the person's edited version once approved)
    action          jsonb       NOT NULL,
    original_action jsonb,
    verdict         jsonb       NOT NULL,
    agent_view      text        NOT NULL,
    context         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    urgency         text        NOT NULL,
    status          text        NOT NULL,
    decided_at      timestamptz,
    failure         text
);
CREATE INDEX held_action_status_idx ON held_action (status, created_at DESC);

CREATE TABLE amendment (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    line_id    text,
    old_text   text,
    proposed   jsonb       NOT NULL,
    reason     text        NOT NULL,
    from_held  uuid REFERENCES held_action (id) ON DELETE SET NULL,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE action_outbox (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id    text        NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    held_id         uuid REFERENCES held_action (id) ON DELETE SET NULL,
    action          jsonb       NOT NULL,
    idempotency_key text        NOT NULL UNIQUE,
    status          text        NOT NULL CHECK (status IN ('queued', 'leased', 'done', 'failed')),
    lease_until     timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    result          jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX action_outbox_poll_idx ON action_outbox (connector_id, status, next_attempt_at);

CREATE TABLE activity (
    id        bigserial PRIMARY KEY,
    at        timestamptz NOT NULL DEFAULT now(),
    summary   text        NOT NULL,
    kind      text        NOT NULL,
    ok        boolean     NOT NULL DEFAULT true,
    action_id uuid
);
CREATE INDEX activity_at_idx ON activity (at DESC);
