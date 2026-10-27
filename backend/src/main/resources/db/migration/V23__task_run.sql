-- Tasks as threads: which helper runs worked on which task ("what I'm doing" → the Agent's Path).
-- Additive only: a new link table and a nullable column; no existing row is rewritten.
CREATE TABLE agent_task_run (
    task_id    uuid        NOT NULL,
    run_id     uuid        NOT NULL,
    -- 'explicit' (delegate named the task) or 'inferred' (the one task the lead touched in the same turn)
    how        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, run_id)
);
CREATE INDEX agent_task_run_run_idx ON agent_task_run (run_id);

-- Which run (the lead thread or a helper) made each model call; null for calls made before this.
ALTER TABLE model_call ADD COLUMN run_id text;
CREATE INDEX model_call_run_idx ON model_call (run_id, at) WHERE run_id IS NOT NULL;
