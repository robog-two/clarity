-- The browser pilot's audit trail: one row per step a helper took in the browser (act, sign_in, go,
-- back), with what Jev saw and answered, what the gate decided and what changed. Screenshots stay
-- here on the server; a frame taken while a secret may have been on screen is never stored (the
-- *_withheld flag is set instead).
CREATE TABLE browser_step (
    id bigserial PRIMARY KEY,
    run_id text NOT NULL,
    seq integer NOT NULL,
    at timestamptz NOT NULL,
    tool text NOT NULL,
    tab_url text,
    tab_title text,
    intent text,
    expect text,
    before_png bytea,
    before_withheld boolean NOT NULL DEFAULT false,
    box jsonb,
    hit_role text,
    hit_name text,
    jev jsonb,
    gate text NOT NULL,
    action text,
    after_png bytea,
    after_withheld boolean NOT NULL DEFAULT false,
    change_report text,
    cost_micros bigint,
    UNIQUE (run_id, seq)
);
CREATE INDEX browser_step_at_idx ON browser_step (at DESC);
