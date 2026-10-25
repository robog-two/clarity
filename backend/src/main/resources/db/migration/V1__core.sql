-- Core control-plane tables: devices, connectors, ingest bookkeeping, agent state.

CREATE TABLE device (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL,
    -- SubjectPublicKeyInfo (DER) of the device's EC P-256 key.
    public_key   bytea       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz
);

-- Only the SHA-256 of the enrollment token is stored.
CREATE TABLE enrollment_token (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash bytea       NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE connector (
    id           text PRIMARY KEY,
    -- SHA-256 of the connector's bearer key; null for device-backed connectors.
    key_hash     bytea UNIQUE,
    provisioned  boolean     NOT NULL DEFAULT false,
    manifest     jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz
);

CREATE TABLE connector_grant (
    connector_id text        NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    noun         text        NOT NULL,
    state        text        NOT NULL CHECK (state IN ('pending', 'granted', 'revoked')),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, noun)
);

CREATE TABLE connector_config (
    connector_id text PRIMARY KEY REFERENCES connector (id) ON DELETE CASCADE,
    version      bigint      NOT NULL,
    config_values jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Idempotency ledger: one row per (connector, noun, source id, source version) ever accepted.
CREATE TABLE source_record (
    connector_id   text        NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    noun           text        NOT NULL,
    source_id      text        NOT NULL,
    source_version text        NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, noun, source_id, source_version)
);

-- Where a connector's record lives in the canonical store, so tombstones can find the entity.
CREATE TABLE source_link (
    connector_id   text        NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    noun           text        NOT NULL,
    source_id      text        NOT NULL,
    entity_id      uuid        NOT NULL,
    latest_version text        NOT NULL,
    deleted_at     timestamptz,
    PRIMARY KEY (connector_id, noun, source_id)
);
CREATE INDEX source_link_entity_idx ON source_link (entity_id);

CREATE TABLE agent_trigger (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    noun       text        NOT NULL,
    spec       jsonb       NOT NULL,
    enabled    boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_trigger_noun_idx ON agent_trigger (noun) WHERE enabled;

CREATE TABLE agent_run (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reason      text        NOT NULL,
    status      text        NOT NULL,
    task        jsonb,
    transcript  jsonb,
    usage       jsonb,
    error       text,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);
CREATE INDEX agent_run_started_idx ON agent_run (started_at DESC);

-- Persisted last run of in-process periodic jobs, so restarts do not double-fire.
CREATE TABLE job_run (
    name        text PRIMARY KEY,
    last_run_at timestamptz NOT NULL
);
