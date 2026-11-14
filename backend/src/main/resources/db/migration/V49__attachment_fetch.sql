-- A message's own id and its attachment list (names and sizes; the bytes stay at the source).
ALTER TABLE message
    ADD COLUMN message_id  text,
    ADD COLUMN attachments jsonb NOT NULL DEFAULT '[]'::jsonb;

-- The fetch channel: one row per (message, part, source version). It is the queue the connector
-- polls and, once done, the cache of the bytes until they expire.
CREATE TABLE attachment_fetch (
    id             uuid        PRIMARY KEY,
    connector_id   text        NOT NULL,
    entity_id      uuid        NOT NULL,
    source_id      text        NOT NULL,
    source_version text        NOT NULL,
    part           text        NOT NULL,
    status         text        NOT NULL,           -- pending | leased | done | failed
    requested_at   timestamptz NOT NULL,
    lease_until    timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    done_at        timestamptz,
    error_code     text,
    error_message  text,
    filename       text,
    mime_type      text,
    size           bigint,
    bytes          bytea
);

CREATE UNIQUE INDEX attachment_fetch_key ON attachment_fetch (entity_id, part, source_version);
CREATE INDEX attachment_fetch_queue ON attachment_fetch (connector_id, status);
CREATE INDEX attachment_fetch_done ON attachment_fetch (done_at) WHERE status = 'done';

-- The pictures `view_image` showed the model: bytes live here for a day, the journal keeps only a reference.
CREATE TABLE tool_image (
    run_id      text        NOT NULL,
    tool_use_id text        NOT NULL,
    mime_type   text        NOT NULL,
    bytes       bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (run_id, tool_use_id)
);
CREATE INDEX tool_image_created ON tool_image (created_at);
