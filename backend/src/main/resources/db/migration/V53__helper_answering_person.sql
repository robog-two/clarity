-- A helper delegated in a turn that was answering the person: its result and questions come back as replies.
ALTER TABLE agent_run ADD COLUMN answering_person boolean NOT NULL DEFAULT false;
