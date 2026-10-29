-- A job whose fire throws is retried with a growing delay instead of being re-claimed every tick
-- (which starved the jobs due after it). failures counts the failed tries in a row; retry_slot
-- keeps the slot the job was due at, so its idempotency keys stay the same across the retries.
ALTER TABLE scheduled_job ADD COLUMN failures integer NOT NULL DEFAULT 0;
ALTER TABLE scheduled_job ADD COLUMN retry_slot timestamptz;
