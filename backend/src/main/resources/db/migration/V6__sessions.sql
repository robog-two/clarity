-- Sessions (morning call, values interview, amend), the person's settings and the day's plan.

-- The person's settings: a single row holding the shared `Settings` JSON.
CREATE TABLE settings (
    id         integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    doc        jsonb       NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- One row per session. At most one is live (active, busy or snoozed) at a time.
CREATE TABLE agent_session (
    id             uuid PRIMARY KEY,
    kind           text        NOT NULL,
    state          text        NOT NULL,
    started_at     timestamptz NOT NULL,
    ended_at       timestamptz,
    stage          text,              -- morning call
    question       integer,           -- values interview
    question_count integer,
    captured_line  jsonb,
    held_action_id text,              -- amend
    amendment_id   text,
    snoozed_until  timestamptz,
    -- values interview: every question is answered, the constitution is being written
    finalizing     boolean     NOT NULL DEFAULT false,
    -- bumped on every transition; part of the inbox dedup key of the playbook sent for it
    version        integer     NOT NULL DEFAULT 0,
    -- SessionData: captured chips, the lines saved so far, the card to update
    data           jsonb       NOT NULL DEFAULT '{}'
);
CREATE UNIQUE INDEX agent_session_one_live_idx ON agent_session ((true)) WHERE state IN ('active', 'busy', 'snoozed');

-- What came out of the morning call, per local day (Today reads it).
CREATE TABLE day_plan (
    date       date PRIMARY KEY,
    priorities jsonb       NOT NULL DEFAULT '[]',
    plan       jsonb       NOT NULL DEFAULT '[]',
    parked     jsonb       NOT NULL DEFAULT '[]',
    called     boolean     NOT NULL DEFAULT false,
    session_id text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
