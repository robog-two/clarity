-- Outing ideas: something to look forward to. The lead saves what it spots (a gig in a newsletter, a
-- friend saying "we should get dinner") or finds on the web, and the outing scout offers one that fits
-- a free slot. A declined idea is never suggested again; there is no pause after a no.
CREATE TABLE outing_idea (
    id           uuid PRIMARY KEY,
    what         text        NOT NULL,
    place        text,
    category     text,
    window_from  timestamptz,
    window_to    timestamptz,
    with_contact uuid,
    source_id    uuid,
    url          text,
    state        text        NOT NULL DEFAULT 'open',
    created_at   timestamptz NOT NULL,
    decided_at   timestamptz,
    -- when its suggestion was posted: the weekly budget of two counts these
    suggested_at timestamptz
);
CREATE INDEX outing_idea_state_idx ON outing_idea (state, created_at);
