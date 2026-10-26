-- Notifications held for protected time. The hold-release job turns notify 'hold_until:<iso>' into
-- 'released:<iso>' (the release time) and sets released_at, once; the phone notifies on that change.
ALTER TABLE feed_item ADD COLUMN released_at timestamptz;
CREATE INDEX feed_item_hold_idx ON feed_item (seq) WHERE notify LIKE 'hold_until:%';
CREATE INDEX feed_item_released_idx ON feed_item (released_at) WHERE released_at IS NOT NULL;
