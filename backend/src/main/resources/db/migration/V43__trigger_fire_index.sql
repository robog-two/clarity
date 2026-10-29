-- The per-trigger hourly fire limit counts a trigger's recent inbox items by their dedup key prefix.
CREATE INDEX inbox_item_dedup_prefix_idx ON inbox_item (dedup_key text_pattern_ops, created_at);
