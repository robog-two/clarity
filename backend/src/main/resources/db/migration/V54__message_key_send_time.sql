-- A message's dedup key now includes its send time in whole seconds (see dedup/DedupKey.kt), so the
-- same words sent on two days stay two messages. Existing rows get the new key from their old one:
-- sha256 of the length-prefixed parts "message@", hex(old key), epoch seconds.
UPDATE message SET dedup_key = sha256(
    int4send(8) || convert_to('message@', 'UTF8') ||
    int4send(64) || convert_to(encode(dedup_key, 'hex'), 'UTF8') ||
    int4send(length(floor(extract(epoch FROM sent_at))::bigint::text)) || convert_to(floor(extract(epoch FROM sent_at))::bigint::text, 'UTF8')
);
