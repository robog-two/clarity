-- Proposals that nobody can act on any more: an amendment or a credential request that a later
-- save made redundant ("superseded"), or that nobody acted on in time ("expired"). History is kept:
-- rows only change status, with the reason and when. The backend's tidy pass (ProposalTidy) sets
-- them at startup, on a schedule and whenever a related change is saved; the wire types report them
-- as discarded / cancelled with `closedReason`.

ALTER TABLE amendment ADD COLUMN closed_reason text;
ALTER TABLE amendment ADD COLUMN closed_at timestamptz;
CREATE INDEX amendment_status_idx ON amendment (status, created_at);

ALTER TABLE credential_request ADD COLUMN closed_reason text;
ALTER TABLE credential_request ADD COLUMN closed_at timestamptz;

-- The status check of V7 is unnamed; drop whichever check constrains the status column.
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'credential_request'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE credential_request DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE credential_request ADD CONSTRAINT credential_request_status_check
    CHECK (status IN ('open', 'fulfilled', 'cancelled', 'superseded', 'expired'));
CREATE INDEX credential_request_status_idx ON credential_request (status, created_at);
