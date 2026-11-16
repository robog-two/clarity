-- What the agent meant by a message (reply, check_in, look_forward, finding), and whether the push it
-- earned counts against the person's daily limit. Counted pushes are read back by local day.
ALTER TABLE feed_item ADD COLUMN kind text;
ALTER TABLE feed_item ADD COLUMN push_counted boolean NOT NULL DEFAULT false;
CREATE INDEX feed_item_push_counted_idx ON feed_item (at) WHERE push_counted;
CREATE INDEX feed_item_look_forward_idx ON feed_item (at) WHERE kind = 'look_forward';
