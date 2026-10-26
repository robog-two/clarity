-- The lead agent: its one thread, journals, inbox, feed, tasks, memory and the durable scheduler.

-- The one lead thread. Its journal lives in agent_journal under run_id = id.
CREATE TABLE agent_thread (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    compacted_at timestamptz,
    -- when the lead last finished a turn / the person last said something (idle compaction)
    last_turn_at timestamptz,
    last_user_input_at timestamptz
);

-- Helpers are rows in agent_run (the lead thread is not).
ALTER TABLE agent_run
    ADD COLUMN parent_run_id uuid, -- the delegating run: the lead thread (agent_thread.id) or another helper
    ADD COLUMN role          text NOT NULL DEFAULT 'helper',
    ADD COLUMN result        text,
    ADD COLUMN tools         jsonb;
CREATE INDEX agent_run_open_idx ON agent_run (status) WHERE status IN ('queued', 'running');

-- Append-only conversation of a run (lead thread or helper); the harness' RunJournal.
CREATE TABLE agent_journal (
    run_id     text        NOT NULL,
    seq        integer     NOT NULL,
    message    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

-- Everything that can wake the lead. dedup_key makes enqueueing idempotent.
CREATE TABLE inbox_item (
    seq         bigserial PRIMARY KEY,
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    priority    text        NOT NULL,
    item        jsonb       NOT NULL,
    dedup_key   text UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    consumed_at timestamptz
);
CREATE INDEX inbox_item_pending_idx ON inbox_item (seq) WHERE consumed_at IS NULL;

-- The app's feed. batch_state: null = shown; 'pending' = waits for the evening summary; 'summarized' = was.
CREATE TABLE feed_item (
    seq             bigserial PRIMARY KEY,
    id              uuid        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    at              timestamptz NOT NULL DEFAULT now(),
    author          text        NOT NULL,
    session_id      text,
    cards           jsonb       NOT NULL,
    streaming       boolean     NOT NULL DEFAULT false,
    client_input_id text UNIQUE,
    -- what the notification policy decided: push_now | in_app | hold_until:<iso> | evening_batch:<iso>
    notify          text,
    batch_state     text
);
CREATE INDEX feed_item_batch_idx ON feed_item (seq) WHERE batch_state = 'pending';

CREATE TABLE agent_task (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title            text        NOT NULL,
    detail           text        NOT NULL DEFAULT '',
    status           text        NOT NULL,
    held_action_id   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memory_journal (
    id         bigserial PRIMARY KEY,
    date       date        NOT NULL,
    text       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    tsv        tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED
);
CREATE INDEX memory_journal_date_idx ON memory_journal (date);
CREATE INDEX memory_journal_tsv_idx ON memory_journal USING gin (tsv);

CREATE TABLE memory_scratch (
    id         bigserial PRIMARY KEY,
    text       text        NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Durable scheduler: claimed with FOR UPDATE SKIP LOCKED, so restarts never double-fire.
CREATE TABLE scheduled_job (
    name        text PRIMARY KEY,
    next_run_at timestamptz NOT NULL,
    last_run_at timestamptz,
    payload     jsonb
);
CREATE INDEX scheduled_job_due_idx ON scheduled_job (next_run_at);

-- Oversized tool results that were not written to the sandbox workspace (read_result).
CREATE TABLE tool_result (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      text        NOT NULL,
    tool_use_id text        NOT NULL,
    tool_name   text        NOT NULL,
    content     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, tool_use_id)
);

-- Replaced by scheduled_job.
DROP TABLE job_run;
