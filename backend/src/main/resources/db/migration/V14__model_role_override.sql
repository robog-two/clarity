-- Runtime overrides of a role's model settings, set in the app: one row per role, null = the deploy-time default.
CREATE TABLE model_role_override (
    role text PRIMARY KEY CHECK (role IN ('lead', 'helper', 'guard', 'triage')),
    provider text CHECK (provider IN ('anthropic', 'openrouter')),
    model text,
    effort text,
    max_tokens bigint,
    cache_ttl text CHECK (cache_ttl IN ('5m', '1h')),
    compaction boolean,
    compact_at_tokens bigint,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Which role made each call, so costs can be compared per role (null for calls before this).
ALTER TABLE model_call ADD COLUMN role text;
