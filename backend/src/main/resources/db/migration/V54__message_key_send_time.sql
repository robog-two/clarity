-- A text or chat message's dedup key now includes its send time in epoch milliseconds (see
-- dedup/DedupKey.kt), so the same words sent twice stay two messages. Email keeps its old key: a mail
-- without a Date header gets its fetch time, which differs per folder. Existing rows get the new key
-- from their old one: sha256 of the length-prefixed parts "message@", hex(old key), epoch milliseconds.
UPDATE message SET dedup_key = sha256(
    int4send(8) || convert_to('message@', 'UTF8') ||
    int4send(64) || convert_to(encode(dedup_key, 'hex'), 'UTF8') ||
    int4send(length(floor(extract(epoch FROM sent_at) * 1000)::bigint::text)) ||
    convert_to(floor(extract(epoch FROM sent_at) * 1000)::bigint::text, 'UTF8')
)
WHERE channel <> 'email';
