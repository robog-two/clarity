-- The agent's tapback on one of the person's messages ({"itemId", "reaction"}). A reaction is an item
-- of its own (no cards), so a phone that already has the target gets it by seq.
ALTER TABLE feed_item ADD COLUMN reaction jsonb;
