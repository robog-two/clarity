-- Images a report's Markdown points at (![caption](https://...)), fetched once by the backend through the
-- egress proxy so renders never touch the network. `key` is the lowercase hex SHA-256 of the URL as
-- written in the Markdown. A failed fetch is a row too (bytes NULL, error set), so it is not retried in
-- a loop. Shared by every revision of the report.
CREATE TABLE report_image (
    report_id    uuid        NOT NULL REFERENCES report (id) ON DELETE CASCADE,
    key          text        NOT NULL,
    url          text        NOT NULL,
    content_type text,
    bytes        bytea,
    width        integer,
    height       integer,
    error        text,
    fetched_at   timestamptz NOT NULL,
    PRIMARY KEY (report_id, key),
    CHECK ((bytes IS NOT NULL AND content_type IS NOT NULL AND error IS NULL) OR (bytes IS NULL AND error IS NOT NULL))
);
