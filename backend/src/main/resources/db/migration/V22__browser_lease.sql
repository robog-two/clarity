-- One run at a time uses the shared browser (BrowserLeases). browser_lease is a single row (id 1)
-- locked FOR UPDATE while the lease changes hands; browser_lease_queue holds the runs waiting for it,
-- first come first served. browser_resume keeps where a run left the browser, for a later run of the
-- same task (by task_key, a hash of its task text): shown, never reopened.
CREATE TABLE browser_lease (
    id integer PRIMARY KEY CHECK (id = 1),
    holder_run text,
    acquired_at timestamptz,
    heartbeat_at timestamptz,
    last_activity_at timestamptz
);
INSERT INTO browser_lease (id) VALUES (1);

CREATE TABLE browser_lease_queue (
    run_id text PRIMARY KEY,
    enqueued_at timestamptz NOT NULL
);
CREATE INDEX browser_lease_queue_order_idx ON browser_lease_queue (enqueued_at, run_id);

CREATE TABLE browser_resume (
    run_id text PRIMARY KEY,
    task_key text,
    url text,
    title text,
    where_line text,
    at timestamptz NOT NULL
);
CREATE INDEX browser_resume_task_idx ON browser_resume (task_key, at DESC);
