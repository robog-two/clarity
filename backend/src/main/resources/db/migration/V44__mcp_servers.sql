-- Third-party MCP servers (docs/workstreams/mcp.md).

-- The person's choice of how one tool may be used; no row means the default from its annotations.
CREATE TABLE mcp_tool_policy (
    server_id  text        NOT NULL,
    tool       text        NOT NULL,
    mode       text        NOT NULL CHECK (mode IN ('auto', 'ask', 'off')),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (server_id, tool)
);

-- Servers the backend has seen listed, with the memory collection made for each (made once).
CREATE TABLE mcp_server_seen (
    server_id  text PRIMARY KEY,
    collection text        NOT NULL,
    first_seen timestamptz NOT NULL
);

-- Servers the lead suggested; shown only in Settings. Dismissing deletes the row.
CREATE TABLE mcp_suggestion (
    id         uuid PRIMARY KEY,
    label      text        NOT NULL,
    label_key  text        NOT NULL,
    why        text        NOT NULL,
    preset     text,
    created_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX mcp_suggestion_label_idx ON mcp_suggestion (label_key);

-- Every suggestion made, dismissed ones included, for the 3-per-7-days limit.
CREATE TABLE mcp_suggestion_made (
    label_key  text        NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX mcp_suggestion_made_at_idx ON mcp_suggestion_made (created_at);

-- A helper given servers keeps them across a resume; the morning sweep is a helper with a profile.
ALTER TABLE agent_run ADD COLUMN mcp_servers jsonb;
ALTER TABLE agent_run ADD COLUMN profile text;

-- A scratch note with a key replaces the live note with the same key (the sweep's `coming-up`).
ALTER TABLE memory_scratch ADD COLUMN key text;
CREATE INDEX memory_scratch_key_idx ON memory_scratch (key) WHERE key IS NOT NULL;
