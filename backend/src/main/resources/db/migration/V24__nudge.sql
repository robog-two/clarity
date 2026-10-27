-- Nudges: check-ins the lead schedules for itself (the nudge tool). The nudge job claims due
-- pending rows with FOR UPDATE SKIP LOCKED and resolves each exactly once; a resolved row stays
-- as a timeline entry (fired, skipped, missed or cancelled, with a reason).
CREATE TABLE nudge (
    id          uuid PRIMARY KEY,
    due_at      timestamptz NOT NULL,
    note        text        NOT NULL,
    task_id     uuid,
    status      text        NOT NULL DEFAULT 'pending',
    reason      text,
    created_at  timestamptz NOT NULL,
    resolved_at timestamptz
);
CREATE INDEX nudge_pending_due_idx ON nudge (due_at) WHERE status = 'pending';
CREATE INDEX nudge_resolved_idx ON nudge (resolved_at);
