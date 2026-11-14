-- The sources route asks "does any feed item have a sources card citing this record" with `cards @> ...`;
-- jsonb_path_ops serves containment only, which keeps the index small.
CREATE INDEX feed_item_cards_idx ON feed_item USING gin (cards jsonb_path_ops);
