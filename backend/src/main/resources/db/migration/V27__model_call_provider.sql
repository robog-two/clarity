-- The upstream provider OpenRouter routed each model call to (its stream's `provider`), so routing
-- and cache hits can be read per provider. Null when the response did not say, and for older calls.
ALTER TABLE model_call ADD COLUMN provider text;
