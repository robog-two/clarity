-- The people a held notification is about (send_to_user's `about`, as Handle JSON), so that adding
-- someone to "who gets through" can release what is already held from them.
ALTER TABLE feed_item ADD COLUMN notify_about jsonb;
