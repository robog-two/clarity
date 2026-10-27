-- A topic marker's title: a short label for the stretch of the lead's conversation that a compaction
-- summarized ("Ulta Hair Appt"). Null on every other item.
ALTER TABLE feed_item ADD COLUMN topic text;
