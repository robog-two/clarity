# Workstream `connectors`

Lane: `connectors/mail/**`, `connectors/dav/**` (new modules), the `document` noun across
`shared/noun`, `backend/.../dedup/**`, `backend/.../persistence/NounTables.kt` + reader, migration
**`V5__documents.sql`**, document domain tools (`search_documents`, `read_document`) in a new file
`backend/.../agent/DocumentTools.kt`, a `POST /api/v1/connectors/{id}/test` route in
`backend/.../connectors/`, sealing helpers in `connector-sdk`, compose services, CI/publish entries.

Read "Connectors", "Ingest, normalization and dedup", "Action outbox" in `ARCHITECTURE.md`; the
shared `noun`, `ingest`, `connector` (incl. `Sealing.kt`), `action` and `governance` contracts;
`connector-sdk/**` (incl. the new action handlers and ledger); `connectors/example/**`;
`backend/.../connectors/**` and `ingest/**`; `deploy/compose.yaml`.

Libraries (Java/Kotlin only, no extra containers): **Eclipse Angus Mail** (Jakarta Mail impl) for
IMAP/SMTP; **dav4jvm** (`com.github.bitfireAT:dav4jvm`, JitPack: add the JitPack repository in
`settings.gradle.kts` with a content filter for `com.github.bitfireAT` only) for
WebDAV/CalDAV/CardDAV; **ical4j** for iCalendar; **ez-vcard** for vCard; **Google Tink** for HPKE
config sealing; Apache PDFBox (and plain text/markdown/HTML via jsoup) for document text. Check
each version resolves.

## Build

1. **Noun `document`**: `DocumentPayload(store, path, title, mimeType, size, modifiedAt, excerpt)`
   (excerpt ≤ 8 KB of extracted text); normalize + dedup key `store | normalized path`; table
   + reader + `search_documents` (title/path/excerpt FTS) and `read_document` (proposes a
   `ReadDocument` action to the owning connector and waits up to 30 s for the `Done.content`
   result through `ActionResults`; returns a clear "still fetching, try again" otherwise).
2. **Sealing in connector-sdk**: generate and persist an X25519 key pair in the state dir on first
   start, publish the public key in the manifest `sealingKey`, `ConnectorConfig.secret(name)` opens
   `SealedValue`s per `Sealing` (HPKE via Tink). Round-trip test against a Tink sealer.
3. **Test round trip**: `POST /api/v1/connectors/{id}/test` enqueues a `connector_test` request
   the connector sees on its next config poll or action poll (add a small `ConnectorTest`
   contract if needed: `{requestId}` → connector reports `{ok, message, counts}`); the app polls
   `GET /api/v1/connectors` for the result (`ConnectorSummary` gains optional `lastTest`).
4. **`connectors/mail`**: config schema (imap host:port, smtp host:port, username, password
   [sealed], from name, folders to sync, initial window days, default 30). Sync: UID-based
   incremental per folder with UIDVALIDITY handling, IDLE where supported else poll, push
   `MessagePayload` (email channel, `threadRef` from Message-ID/References), tombstones on expunge.
   Actions: `SAVE_DRAFT` (IMAP APPEND to the Drafts folder found by SPECIAL-USE, replacing a
   previous draft with the same idempotency key), `SEND_EMAIL` (SMTP; `Message-ID` derived from the
   idempotency key so a resend is detectable; In-Reply-To/References from the original message;
   then APPEND to Sent if the server doesn't). Plain text bodies; no HTML formatting.
5. **`connectors/dav`**: config schema (base URL or separate CalDAV/CardDAV/WebDAV URLs, username,
   password [sealed], calendars to sync, WebDAV folders to index). Discovery (RFC 6764
   `.well-known` + principal/home-set). Sync with `sync-collection` (RFC 6578) when supported,
   else ETag comparison. Calendar events → `CalendarEventPayload` (expand recurrences within a
   window, e.g. -30/+180 days); contacts → `ContactPayload`; WebDAV files → `DocumentPayload`
   with text extraction for txt/md/html/pdf/odt-if-easy. Actions: `CREATE_EVENT` (UID = derived
   from idempotency key, `TRANSP:OPAQUE`, private hold), `MOVE_EVENT` (If-Match ETag; with
   `notifyAttendees` only if the person is the organizer and the server supports RFC 6638 implicit
   scheduling, otherwise fail with a message the agent can relay), `RESPOND_TO_INVITE`
   (PARTSTAT update via the server's scheduling), `CREATE_REMINDER` (short event at `at` with a
   `VALARM` DISPLAY trigger at start, in the default calendar), `READ_DOCUMENT` (fetch + extract
   full text, return as `Done.content`, capped at 200 KB).
6. **Compose + CI**: services `connector-mail` and `connector-dav` (profiles `mail`, `dav`), each
   with its own key secret, `/data` volume, egress network; add to `init-secrets.sh`, `.env.example`,
   CI build and publish.

## Tests

Pure: dedup/normalize for documents, iCalendar ↔ payload mapping (incl. recurrence expansion,
all-day, time zones), vCard → contact, MIME → message payload (multipart, encodings, threading),
idempotency-key → UID/Message-ID derivation. Integration without network: GreenMail
(in-process IMAP/SMTP test server) for the mail connector; a small in-process DAV fixture (Ktor
test server answering PROPFIND/REPORT with recorded responses) for dav. No calls to real servers.
