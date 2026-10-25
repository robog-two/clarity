-- Canonical, deduplicated nouns. `dedup_key` is SHA-256 over the canonical tuple (see dedup/).

CREATE TABLE message (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dedup_key     bytea       NOT NULL UNIQUE,
    channel       text        NOT NULL,
    account       jsonb       NOT NULL,
    sender        jsonb       NOT NULL,
    recipients    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    -- normalized handle values of sender + recipients, for participant lookups
    participants  jsonb       NOT NULL DEFAULT '[]'::jsonb,
    subject       text,
    body          text        NOT NULL,
    sent_at       timestamptz NOT NULL,
    thread_ref    text,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);
CREATE INDEX message_sent_at_idx ON message (sent_at);
CREATE INDEX message_updated_at_idx ON message (updated_at);
CREATE INDEX message_participants_idx ON message USING gin (participants);

CREATE TABLE contact (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dedup_key     bytea       NOT NULL UNIQUE,
    display_name  text        NOT NULL,
    handles       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    organization  text,
    notes         text,
    -- lowercased display name, organization and handle values for substring search
    search_text   text        NOT NULL DEFAULT '',
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);
CREATE INDEX contact_updated_at_idx ON contact (updated_at);

CREATE TABLE calendar_event (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dedup_key     bytea       NOT NULL UNIQUE,
    ical_uid      text,
    calendar      text        NOT NULL,
    title         text        NOT NULL,
    start_at      timestamptz NOT NULL,
    end_at        timestamptz NOT NULL,
    all_day       boolean     NOT NULL DEFAULT false,
    location      text,
    description   text,
    attendees     jsonb       NOT NULL DEFAULT '[]'::jsonb,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);
CREATE INDEX calendar_event_start_at_idx ON calendar_event (start_at);
CREATE INDEX calendar_event_updated_at_idx ON calendar_event (updated_at);
