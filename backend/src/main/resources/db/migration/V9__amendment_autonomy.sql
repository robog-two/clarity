-- An amendment can also move one kind of action to another autonomy tier ({"kind", "tier"}).
ALTER TABLE amendment ADD COLUMN autonomy jsonb;
