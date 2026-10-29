-- Jobs that start work outside their own transaction (a helper run) claim their slot here first,
-- in a transaction of its own: a retry of the same slot finds the claim and starts nothing.
CREATE TABLE job_slot_claim (
    job        text        NOT NULL,
    slot       timestamptz NOT NULL,
    claimed_at timestamptz NOT NULL,
    PRIMARY KEY (job, slot)
);
