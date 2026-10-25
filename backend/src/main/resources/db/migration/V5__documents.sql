-- Documents (files in WebDAV and similar stores) and the connector connection-test round trip.

CREATE TABLE document (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dedup_key     bytea       NOT NULL UNIQUE,
    store         text        NOT NULL,
    path          text        NOT NULL,
    title         text        NOT NULL,
    mime_type     text,
    size          bigint,
    modified_at   timestamptz,
    -- extracted text, at most 8 KB; the full text is fetched on demand from the connector
    excerpt       text        NOT NULL DEFAULT '',
    search        tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(path, '') || ' ' || coalesce(excerpt, ''))
    ) STORED,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);
CREATE INDEX document_search_idx ON document USING gin (search);
CREATE INDEX document_updated_at_idx ON document (updated_at);

-- One row per connector: the newest test request and the newest report.
CREATE TABLE connector_test (
    connector_id text PRIMARY KEY REFERENCES connector (id) ON DELETE CASCADE,
    request      jsonb       NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    report       jsonb
);
