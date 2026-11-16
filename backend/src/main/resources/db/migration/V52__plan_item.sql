-- Plan items: what the person put on a day's plan (a priority or a parked item), followed from day
-- to day so a task that keeps coming back is noticed. days_carried counts the days it has been on a
-- plan in a row; started_on is the day the person said they were starting it.
CREATE TABLE plan_item (
    id           uuid PRIMARY KEY,
    text         text        NOT NULL,
    first_date   date        NOT NULL,
    last_date    date        NOT NULL,
    days_carried integer     NOT NULL DEFAULT 1,
    state        text        NOT NULL DEFAULT 'open',
    started_on   date,
    first_step   text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX plan_item_open_idx ON plan_item (last_date) WHERE state = 'open';

-- A nudge may belong to a plan item (the check-in an if-then schedules, or a follow-up the person asked for).
ALTER TABLE nudge ADD COLUMN plan_item_id uuid;
ALTER TABLE nudge ADD COLUMN follow_up boolean NOT NULL DEFAULT false;
CREATE INDEX nudge_plan_item_idx ON nudge (plan_item_id) WHERE plan_item_id IS NOT NULL;
