-- Reports ("goods"): documents the agent writes for the person. Additive only.
-- A report is a series of revisions (Markdown with optional YAML front matter, plus a style spec);
-- every file format is rendered from a revision on demand. Renditions are a pure cache (rebuildable,
-- swept by size), revisions are the source of truth and are never swept. The name avoids the existing
-- `document` table (V5), which holds files ingested from WebDAV.

CREATE TABLE report (
    id             uuid        PRIMARY KEY,
    -- only 'document' is made today; the others are reserved for slides, spreadsheets and sites
    kind           text        NOT NULL DEFAULT 'document' CHECK (kind IN ('document', 'deck', 'sheet', 'site')),
    title          text        NOT NULL,
    created_at     timestamptz NOT NULL,
    -- the run (lead thread or helper) that created it; null when made outside a run
    created_by_run text
);
CREATE INDEX report_created_idx ON report (created_at DESC);

CREATE TABLE report_revision (
    id             bigserial   PRIMARY KEY,
    report_id      uuid        NOT NULL REFERENCES report (id) ON DELETE CASCADE,
    n              integer     NOT NULL CHECK (n >= 1),
    source         text        NOT NULL,
    -- the style spec (font, size, spacing, margins, indent, heading sizes, ...), see shared ReportStyle
    style          jsonb       NOT NULL,
    created_at     timestamptz NOT NULL,
    created_by_run text,
    UNIQUE (report_id, n)
);

-- Cache of rendered files, keyed by (revision, format, renderer version). The file lives under
-- <dataDir>/reports at blob_path; a row without its file (or a file without its row) is rebuilt or swept.
CREATE TABLE report_rendition (
    revision_id      bigint      NOT NULL REFERENCES report_revision (id) ON DELETE CASCADE,
    format           text        NOT NULL,
    renderer_version text        NOT NULL,
    blob_path        text        NOT NULL UNIQUE,
    size_bytes       bigint      NOT NULL CHECK (size_bytes >= 0),
    content_sha256   text        NOT NULL,
    created_at       timestamptz NOT NULL,
    last_accessed_at timestamptz NOT NULL,
    PRIMARY KEY (revision_id, format, renderer_version)
);
CREATE INDEX report_rendition_lru_idx ON report_rendition (last_accessed_at);

-- Web sources a report cites, from search citations (url_citation annotations), so a works-cited
-- section can be rendered from stored rows.
CREATE TABLE report_source (
    id         bigserial   PRIMARY KEY,
    report_id  uuid        NOT NULL REFERENCES report (id) ON DELETE CASCADE,
    url        text        NOT NULL,
    title      text,
    cited_text text,
    fetched_at timestamptz NOT NULL,
    UNIQUE (report_id, url)
);

-- Web searches a model call ran (OpenRouter usage.server_tool_use.web_search_requests).
ALTER TABLE model_call ADD COLUMN web_search_requests integer NOT NULL DEFAULT 0;
