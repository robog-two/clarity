# Workstream `connector-accounts`

Setting up mail and calendar from the phone. The person found three problems on a real device:

1. The mail and calendar rows open an empty page. The `mail` and `dav` connectors are registered
   from their key files, but their containers were not running, so they never sent a manifest and
   the app had no `configSchema` to build a form from.
2. Each connector holds one account. The person has several mail and calendar accounts.
3. The form builder renders `enum` fields as free text and drops `array` fields, such as folders
   and calendars.

Two lanes run in parallel and share the contract below. Do not change the contract on your own.
If it does not work for you, stop and report.

- **`connectors`**: `connectors/mail/**`, `connectors/dav/**`, `connector-sdk/**`, plus additive
  `shared/connector/**`.
- **`app`**: `android/**`.

## Contract: config schema conventions

A connector's `ConnectorManifest.configSchema` is a JSON Schema object. The app renders this subset
of it, and nothing outside the subset.

| Schema | Rendered as | Stored value |
|---|---|---|
| `type: string` | text field | string |
| `type: string` with `format: uri` or `url` | URL field | string |
| `type: string` with `format: email` | email field | string |
| `type: string` with `format: password`, `writeOnly: true` or `x-secret: true` | masked secret field, autofill off | sealed object (see below) |
| `type: string` with `enum: [...]` | dropdown; labels from `x-enum-titles` (same length) when present | string |
| `type: integer` or `number` (`minimum`, `maximum`) | number field | number |
| `type: boolean` | switch | boolean |
| `type: array` with `items: {type: string}` | list editor (add or remove entries) | JSON array of strings |
| `type: array` with `items: {type: object, ...}` | **collection**: one card per item, with add, edit and remove | JSON array of objects |

On every property:

- `title` is the label.
- `description` is the helper text.
- `examples[0]` is the placeholder.
- `default` is the initial value.
- `required` on the containing object marks the field as required.
- Fields render in the order of `properties`.

### Collections (one item per account)

- Each item object has an `id` property: `{"type": "string", "readOnly": true}`. The app generates
  it when the item is created, as 8–16 characters matching `[a-z0-9]+`. It never changes and the
  app never shows it.
- `x-item-title`: the name of the item property used as the card title (for example `"label"`).
  `x-item-subtitle` is a second property shown under it (for example `"username"`).
- `minItems`: the smallest number of items allowed before saving (usually 1).
- `x-presets` (optional, on the collection): a list of `{"title": "iCloud", "description": "...",
  "values": { <item property>: <value>, ... }}`. When adding an item, the app first offers these
  as choices ("iCloud", "Fastmail", ..., "Other"). A choice prefills the item form. `description`
  is shown as a hint in the form, for example "Use an app-specific password from
  account.apple.com".

### Secrets

- A secret is sealed by the app with HPKE per `shared/connector/Sealing.kt`. The `info` field
  name is:
  - for a top-level property: the property name (unchanged: `"password"`);
  - for a property inside a collection item: `Sealing.itemField(collection, itemId, property)`,
    which is `"<collection>/<itemId>/<property>"`, for example `"accounts/k3j9x2p1/password"`.
- The stored value is `{"type": "sealed", "algorithm": ..., "ciphertext": ...}` (unchanged).
- **Keeping a stored secret:** `GET /api/v1/connectors/{id}/config` returns the sealed objects.
  If the person does not retype a stored secret, the app sends the stored sealed object back
  unchanged, so editing one account does not require retyping every password. A stored sealed
  value satisfies `required`. The field shows "saved · type to replace".
- Because the item id is part of the sealing `info`, an item's secret stays valid when items are
  reordered or other items are removed.

### Mail and dav schemas

Top level: `{ "accounts": <collection>, ...connector-wide options if any }`, with
`"required": ["accounts"]`.

- **mail** items: `id`, `label` ("Work", "Personal"), `imapHost`, `imapPort`, `imapSecurity`
  (enum `ssl`/`starttls`/`none`), `smtpHost`, `smtpPort`, `smtpSecurity`, `username`, `password`
  (secret), `fromAddress`, `fromName`, `folders` (string array, default `["INBOX"]`) and
  `history` ("Mail history": enum `30d`/`90d`/`1y`/`all`, default `30d`; a longer choice backfills
  older mail, a shorter one deletes nothing; items saved before it existed keep their
  `initialWindowDays`).
  - Presets: iCloud (`imap.mail.me.com` 993 ssl, `smtp.mail.me.com` 587 starttls, app-specific
    password); Fastmail (`imap.fastmail.com` 993 ssl, `smtp.fastmail.com` 465 ssl, app password);
    Gmail (`imap.gmail.com` 993 ssl, `smtp.gmail.com` 465 ssl, app password, needs 2-step
    verification).
- **dav** items: `id`, `label`, `baseUrl`, `caldavUrl`, `carddavUrl`, `webdavUrl`, `username`,
  `password` (secret), `email`, `calendars`, `addressBooks` and `webdavFolders` (string arrays).
  - Presets: iCloud (`caldavUrl` `https://caldav.icloud.com`, `carddavUrl`
    `https://contacts.icloud.com`, app-specific password); Fastmail (`https://caldav.fastmail.com/dav/`,
    `https://carddav.fastmail.com/dav/`); Nextcloud (`baseUrl` hint
    `https://cloud.example.com/remote.php/dav`).
  - For a preset that sets `caldavUrl`/`carddavUrl`, `baseUrl` is not required. Validate this in
    the connector with a clear `ConfigIncomplete` message, and do not mark `baseUrl` as required
    in the item schema.

**Legacy config:** a stored config without `accounts` but with the old flat fields is read as a
single account with id `default` (its password sealed under the plain field name `"password"`).

### Test results

`ConnectorTestResult` is unchanged.

- `summary` names each account: `"Work: 4,218 messages · Personal: 312 messages"`.
- `ok` is false if any account fails.
- `error` names the failing account first: `"Personal: password rejected"`.
- The app shows these strings as they are.

## Lane `connectors`

1. `shared/connector/Sealing.kt`: `Sealing.itemField` already exists (added with this doc).
   `connector-sdk`: a helper to open an item secret, such as
   `runtime.secret(config, collection, itemId, property)`, and a small helper to read a collection
   from `ConnectorConfig`, if useful.
2. **mail:**
   - Parse `accounts`, falling back to the legacy flat config.
   - Sync each account on its own, with its own IMAP connection, folder cursors and state under
     `/data` keyed by account id. One failing account must not stop the others.
   - `sourceId` includes the account id (`<accountId>/<folder>/<uidValidity>/<uid>`). `MailRef.parse`
     still accepts the old three-part form as account `default`.
   - Records say which account received them. Use what the ingest contract already offers (for
     example the recipient handle or an attribute) and do not change shared ingest shapes.
   - `SendEmail` and `SaveDraft` pick an account in this order: the account whose `fromAddress`
     or `username` matches `email.from`; else the account of the `inReplyTo` message; else the
     first account. The test sends nothing.
3. **dav:**
   - Handle accounts the same way: each with its own sync state, a failure isolated per account,
     and source ids prefixed with the account id (keep parsing the old form as `default`).
   - `CreateEvent.calendar` may be `"<label>/<calendar>"`, `"<calendar>"` (first match across
     accounts) or null (the first account's default calendar). Moving, deleting and answering an
     event use the account that holds that event.
4. Write the schemas, presets and test summaries above. Update the connectors' README sections in
   `deploy/README.md` if they describe config fields.
5. Tests: legacy fallback; per-account sealing `info` (a value sealed for account A does not open
   as account B); account selection for send, draft and create event; one failing account does
   not stop another (use the existing GreenMail/Radicale or fake-server test setup the connectors
   already use); `sourceId` round trip including the legacy form. `./gradlew :connectors:mail:test
   :connectors:dav:test :connector-sdk:test :shared:allTests` (or the JVM test task) must pass.

## Lane `app`

1. **Form builder** (`android/.../connector/ConfigForm.kt` and the setup screens):
   - Support every row of the table above, including dropdowns, string lists and collections.
   - Secrets inside items are sealed with `Sealing.itemField`.
   - Stored secrets are kept (see "Keeping a stored secret").
   - Placeholders come from `examples`.
2. **Collection UI**, in the Material 3 style of the rest of the app:
   - The connector screen shows the accounts as cards (title and subtitle, plus the last test
     line for that account when the summary names it), an "add account" button, and edit and
     remove on each card. Remove asks for confirmation.
   - "Add account" offers the `x-presets` first.
   - The account editor is its own screen or sheet with the item's fields.
   - Saving PUTs the whole config.
   - Applies in onboarding and from Settings.
3. **Connectors that are not running:** a server connector with `manifest == null` must not open
   an empty form.
   - Show it in the list as "not running on your server". Its screen says to start its container
     (the `mail` / `dav` compose profile) and come back.
   - It does not block "sync all" or "continue" in onboarding.
   - Hide `device-*` connectors from the onboarding list, as Settings already does.
4. **Settings:** the server sources rows open the same connector screen, so accounts can be
   added and edited after onboarding.
5. Tests (JVM): parsing every schema kind; collection add, edit and remove state; id generation;
   item sealing `info` strings; keeping stored secrets (the PUT body contains the stored sealed
   object unchanged); presets prefilling; required checks with stored secrets; the not-running
   state not blocking onboarding. Use the real mail and dav schemas from this doc as fixtures.
   `./gradlew assembleDebug testDebugUnitTest lintDebug` in `android/` must pass.
