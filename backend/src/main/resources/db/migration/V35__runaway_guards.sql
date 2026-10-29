-- Guards against runs that loop or repeat themselves.

-- How many times a helper run was started (its first start and every resume after a restart);
-- a run started too often is failed instead of resumed again.
ALTER TABLE agent_run ADD COLUMN starts integer NOT NULL DEFAULT 0;

-- Every tool call's result, written as soon as the tool returns, so a run resumed after a crash
-- gets the result instead of running the call again (a second email, helper or edit).
CREATE TABLE tool_call_ledger (
    run_id      text        NOT NULL,
    tool_use_id text        NOT NULL,
    tool_name   text        NOT NULL,
    content     text        NOT NULL,
    is_error    boolean     NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (run_id, tool_use_id)
);
CREATE INDEX tool_call_ledger_created_idx ON tool_call_ledger (created_at);

-- A model call that ended without usage (cancelled, failed or no usage sent): it happened, its
-- tokens and cost are unknown.
ALTER TABLE model_call ADD COLUMN incomplete boolean NOT NULL DEFAULT false;
CREATE INDEX model_call_role_at_idx ON model_call (role, at);

-- The person is told once per period that a spend limit stopped the models.
CREATE TABLE spend_notice (
    period_key text        PRIMARY KEY,
    posted_at  timestamptz NOT NULL
);
