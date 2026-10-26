-- One row per model call (lead, helpers, guard, triage, drafts): tokens and the cost in millionths of a dollar.
CREATE TABLE model_call (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    model text NOT NULL,
    input_tokens bigint NOT NULL,
    output_tokens bigint NOT NULL,
    cache_read_tokens bigint NOT NULL,
    cache_write_5m_tokens bigint NOT NULL,
    cache_write_1h_tokens bigint NOT NULL,
    cost_micros bigint
);
CREATE INDEX model_call_at_idx ON model_call (at DESC);
