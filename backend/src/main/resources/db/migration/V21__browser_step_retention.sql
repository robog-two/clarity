-- Browser step screenshots are dropped after a retention period (CLARITY_BROWSER_FRAME_RETENTION,
-- 30 days by default); the step rows stay. frames_dropped_at says when a row's frames went.
ALTER TABLE browser_step ADD COLUMN frames_dropped_at timestamptz;
