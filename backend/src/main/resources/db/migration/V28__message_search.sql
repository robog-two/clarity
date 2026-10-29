-- Text search over messages (the agent's search_messages tool).
--   search: words of the subject (weight A) and the body (weight C), English stemming, so
--           "astrology" finds "Astrology Club". The body is capped so a huge mail stays indexable.
--   people: sender and recipient addresses and names, lowercased, for fragment matches
--           ("turecek" finds turecekt@gmail.com and "Tim Turecek") through a trigram index.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE message
    ADD COLUMN search tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(subject, '')), 'A') ||
        setweight(to_tsvector('english', left(body, 100000)), 'C')
    ) STORED,
    ADD COLUMN people text GENERATED ALWAYS AS (
        lower(
            coalesce(sender ->> 'value', '') || ' ' || coalesce(sender ->> 'displayName', '') || ' ' ||
            jsonb_path_query_array(recipients, '$[*].value')::text || ' ' ||
            jsonb_path_query_array(recipients, '$[*].displayName')::text
        )
    ) STORED;

CREATE INDEX message_search_idx ON message USING gin (search);
CREATE INDEX message_people_trgm_idx ON message USING gin (people gin_trgm_ops);
