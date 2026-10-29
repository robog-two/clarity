-- A helper run's browser state (actions taken, sign-in progress, codes already typed), so a run
-- resumed after a restart keeps its action cap and never starts a sign-in over.
CREATE TABLE browser_run_state (
    run_id     text PRIMARY KEY,
    state      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX browser_run_state_updated_idx ON browser_run_state (updated_at);
