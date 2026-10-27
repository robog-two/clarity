-- OpenRouter is the only model provider: the provider column of the runtime overrides goes.
-- Only model_role_override changes; the journals, transcripts, memory and model_call rows are untouched.
--
-- A model saved for the direct Anthropic API (a Claude id without a vendor, e.g. claude-opus-5-5 or
-- claude-sonnet-4-5-20250929) becomes its OpenRouter id (anthropic/claude-opus-5.5, anthropic/claude-sonnet-4.5).
-- Any other id without a vendor is kept as it is; the server ignores it and uses the role's default model.
UPDATE model_role_override
SET model = regexp_replace(model, '^claude-([a-z]+)-([0-9]+)-([0-9]+)(-[0-9]{8})?$', 'anthropic/claude-\1-\2.\3')
WHERE position('/' in model) = 0
  AND model ~ '^claude-[a-z]+-[0-9]+-[0-9]+(-[0-9]{8})?$';

UPDATE model_role_override
SET model = regexp_replace(model, '^claude-([a-z]+)-([0-9]+)(-[0-9]{8})?$', 'anthropic/claude-\1-\2')
WHERE position('/' in model) = 0
  AND model ~ '^claude-[a-z]+-[0-9]+(-[0-9]{8})?$';

ALTER TABLE model_role_override DROP COLUMN provider;
