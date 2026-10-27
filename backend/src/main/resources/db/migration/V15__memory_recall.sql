-- Recall on demand: named memory collections, the archive of the lead conversation that
-- compaction took out of view, and embeddings for hybrid (full-text + vector) search.

-- A named topic of notes. Only the index (name + description) is shown to the lead.
CREATE TABLE memory_collection (
    id          bigserial PRIMARY KEY,
    name        text        NOT NULL UNIQUE CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,39}$'),
    description text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memory_note (
    id            bigserial PRIMARY KEY,
    collection_id bigint      NOT NULL REFERENCES memory_collection (id) ON DELETE CASCADE,
    text          text        NOT NULL,
    -- where it came from, free text (e.g. "mail from Dana", "morning call"); optional
    source        text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED
);
CREATE INDEX memory_note_collection_idx ON memory_note (collection_id, updated_at DESC);
CREATE INDEX memory_note_tsv_idx ON memory_note USING gin (tsv);

-- Readable spans of a run's journal (agent_journal) from before its latest compaction point, which
-- the model no longer sees. Written once per span; (run_id, from_seq) makes indexing idempotent.
-- untrusted: the text quotes tool output from outside (mail, web, helpers).
CREATE TABLE conversation_chunk (
    id         bigserial PRIMARY KEY,
    run_id     text        NOT NULL,
    from_seq   integer     NOT NULL,
    to_seq     integer     NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at   timestamptz NOT NULL,
    text       text        NOT NULL,
    untrusted  boolean     NOT NULL DEFAULT false,
    tsv        tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    UNIQUE (run_id, from_seq)
);
CREATE INDEX conversation_chunk_tsv_idx ON conversation_chunk USING gin (tsv);
CREATE INDEX conversation_chunk_run_idx ON conversation_chunk (run_id, to_seq);

-- One embedding per searchable record (no pgvector: similarity is computed in the backend).
-- source: journal | note | conversation | feed | message | event | helper; ref: that row's id.
-- vector: float32 little-endian, L2-normalized, `dims` values. source_at: the row's own updated_at
-- (or created_at) when it was embedded, so an edited row is embedded again.
CREATE TABLE memory_embedding (
    source      text        NOT NULL,
    ref         text        NOT NULL,
    model       text        NOT NULL,
    dims        integer     NOT NULL,
    vector      bytea       NOT NULL,
    source_at   timestamptz,
    embedded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, ref)
);
CREATE INDEX memory_embedding_embedded_at_idx ON memory_embedding (embedded_at);
