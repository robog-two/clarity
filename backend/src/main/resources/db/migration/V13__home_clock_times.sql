-- Repeating times are now wall-clock times in the settings' homeZone (see ScheduleClock), not UTC
-- at the zone's standard offset.
--
-- Only the morning call was ever written in the UTC form: it moves back onto the home clock
-- (13:30 in America/New_York becomes 08:30, weekdays shifted if it crosses midnight), so it rings
-- at the same moment as before. The evening summary and protected hours were set before the UTC
-- form and never converted, so they already read as the person set them; they are left alone.

UPDATE settings s
SET doc = jsonb_set(jsonb_set(s.doc, '{morningCall,at}', to_jsonb(to_char(c.local, 'HH24:MI'))), '{morningCall,days}', c.days),
    updated_at = now()
FROM (
    SELECT t.id,
           t.local,
           coalesce((
               SELECT jsonb_agg(v.day ORDER BY v.day)
               FROM jsonb_array_elements_text(t.doc -> 'morningCall' -> 'days') d,
                    LATERAL (SELECT ((d::int - 1 + (t.local::date - date '2001-01-01')) % 7 + 7) % 7 + 1 AS day) v
           ), '[]'::jsonb) AS days
    FROM (
        SELECT x.id, x.doc,
               -- 2001-01-01 is a Monday; the stored time plus the zone's standard (smaller) offset this year
               timestamp '2001-01-01' + (x.doc -> 'morningCall' ->> 'at')::time + least(
                   (j AT TIME ZONE x.zone) - (j AT TIME ZONE 'UTC'),
                   (l AT TIME ZONE x.zone) - (l AT TIME ZONE 'UTC')
               ) AS local
        FROM (
            SELECT id, doc, doc ->> 'homeZone' AS zone,
                   make_timestamptz(extract(year FROM now())::int, 1, 15, 12, 0, 0, 'UTC') AS j,
                   make_timestamptz(extract(year FROM now())::int, 7, 15, 12, 0, 0, 'UTC') AS l
            FROM settings
            WHERE doc -> 'morningCall' ? 'at'
              AND doc ->> 'homeZone' IN (SELECT name FROM pg_timezone_names)
        ) x
    ) t
) c
WHERE s.id = c.id;

-- The evening job's next run was computed in the old form: move it to the next evening-summary
-- time on the home clock.
UPDATE scheduled_job j
SET next_run_at = (
    SELECT min(o.at)
    FROM generate_series(-1, 2) d,
         LATERAL (SELECT (((now() AT TIME ZONE z.zone)::date + d) + z.evening) AT TIME ZONE z.zone AS at) o
    WHERE o.at > now()
)
FROM (
    SELECT doc ->> 'homeZone' AS zone, (doc ->> 'eveningSummaryAt')::time AS evening
    FROM settings
    WHERE doc ? 'eveningSummaryAt'
      AND doc ->> 'homeZone' IN (SELECT name FROM pg_timezone_names)
) z
WHERE j.name = 'evening-batch';
