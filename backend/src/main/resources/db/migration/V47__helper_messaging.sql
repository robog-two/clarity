-- The lead steers a helper: durable task changes, done criteria, revisions, and questions from the helper.

ALTER TABLE agent_run ADD COLUMN done_when jsonb;
ALTER TABLE agent_run ADD COLUMN revisions integer NOT NULL DEFAULT 0;
-- The automatic check of the done criteria against the result (a list of verdicts); null when not run or unavailable.
ALTER TABLE agent_run ADD COLUMN criteria_check jsonb;

-- What the lead told a helper after it started: a change of task, or feedback on a finished result (a revision).
CREATE TABLE helper_change (
    run_id uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    seq    integer     NOT NULL,
    kind   text        NOT NULL CHECK (kind IN ('change', 'revision')),
    text   text        NOT NULL,
    at     timestamptz NOT NULL,
    PRIMARY KEY (run_id, seq)
);

-- What a helper asked the lead; one open at a time, answered by the lead or closed by the timeout.
CREATE TABLE helper_question (
    run_id      uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    seq         integer     NOT NULL,
    tool_use_id text        NOT NULL,
    text        text        NOT NULL,
    asked_at    timestamptz NOT NULL,
    answer      text,
    answered_at timestamptz,
    timed_out   boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (run_id, seq),
    UNIQUE (run_id, tool_use_id)
);
