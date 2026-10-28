-- The secret link of a Good (a report): a public download page, /g/<token>, until it is turned off.
-- The token is <lookup><secret>: [lookup] finds the row, [secret] (18 random bytes) is compared in
-- constant time. The secret is kept (not hashed) so asking again returns the same live link.
-- At most one live link per report; a revoked one stays as a record and never works again.
CREATE TABLE report_link (
    lookup      text        PRIMARY KEY,
    report_id   uuid        NOT NULL REFERENCES report (id) ON DELETE CASCADE,
    secret      bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    revoked_at  timestamptz
);

CREATE UNIQUE INDEX report_link_live ON report_link (report_id) WHERE revoked_at IS NULL;
