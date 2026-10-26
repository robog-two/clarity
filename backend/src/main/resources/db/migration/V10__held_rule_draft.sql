-- "Approve automatically from now on": the line a small model drafts for a held action ({"text", "because"}).
ALTER TABLE held_action ADD COLUMN rule_draft jsonb;
