-- Phone approvals: each device may register a separate P-256 signing key (Android Keystore,
-- biometric or device credential for every use). The backend verifies signed decisions against it.
ALTER TABLE vault_device_key ADD COLUMN approval_key text;

-- Every signature the backend accepted, by SHA-256 of the signature: a signature works once.
CREATE TABLE approval_signature_used (
    signature_hash text PRIMARY KEY,
    held_id        uuid        NOT NULL,
    device_id      uuid        NOT NULL,
    used_at        timestamptz NOT NULL DEFAULT now()
);
