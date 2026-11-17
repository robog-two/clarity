-- A helper delegated in a turn that was answering the person: its result and questions come back as replies.
ALTER TABLE agent_run ADD COLUMN answering_person boolean NOT NULL DEFAULT false;

-- Whether the lead's open turn is answering the person, so a turn resumed after a crash answers the same way.
ALTER TABLE agent_thread ADD COLUMN open_turn_answering boolean;
