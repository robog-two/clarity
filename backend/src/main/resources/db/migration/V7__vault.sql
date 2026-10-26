-- Credential vault: ciphertext only. The keys live on the phones; the backend cannot open any secret.
-- Two-factor codes and released secret values are never stored: they exist only in backend memory
-- for the lifetime of a grant.

-- Each device's vault key-agreement public key (P-256, SPKI DER, base64url), distinct from its signing key.
CREATE TABLE vault_device_key (
    device_id  uuid PRIMARY KEY REFERENCES device (id) ON DELETE CASCADE,
    public_key text        NOT NULL,
    alg        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE credential (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL UNIQUE,
    kind         text        NOT NULL,
    site         text        NOT NULL,
    purpose      text        NOT NULL,
    fields       jsonb       NOT NULL,
    -- non-secret recognition hints (username, brand, last4, expiry_month)
    hints        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);

-- What the agent asked the person to enter (the form card's contents).
CREATE TABLE credential_request (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          text        NOT NULL,
    kind          text        NOT NULL,
    fields        jsonb       NOT NULL,
    site          text        NOT NULL,
    purpose       text        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('open', 'fulfilled', 'cancelled')),
    credential_id uuid REFERENCES credential (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- One sealed copy of each credential per device.
CREATE TABLE credential_envelope (
    credential_id uuid  NOT NULL REFERENCES credential (id) ON DELETE CASCADE,
    device_id     uuid  NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    envelope      jsonb NOT NULL,
    PRIMARY KEY (credential_id, device_id)
);
