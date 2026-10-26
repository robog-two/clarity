# Workstream `vault-backend`

Lane: `backend/.../backend/vault/**` (new), migration **`V7__vault.sql`**, `shared/.../vault/**`
(new contracts), a browser tool wrapper in `backend/.../agent/BrowserTools.kt` (new) with a
minimal registration change in `AgentToolbox`, the harness `REQUIRE_APPROVAL` cleanup, their tests.
The phone side (Keystore key, form card, biometric approval) is the next workstream
(`vault-app`); define the contracts it needs here.

Read "Credential vault" and "Governance" in `ARCHITECTURE.md`, `docs/workstreams/later-waves.md`
(`vault` and the browser gate), the shared `governance`/`feed` contracts, and the code you build on:
`agent/AgentToolbox.kt`, `McpToolSources.kt`, `ToolSupport.kt` (provenance), `ExternalTools.kt`,
`governance/ActionGate.kt`, `Guard.kt`, `GuardRules.kt`, `HeldActionStore.kt`, `harness/Harness.kt`,
`harness/ToolPolicy.kt`, and the browser MCP tool list (`@mozilla/firefox-devtools-mcp` tools as
the backend sees them through `McpToolSources`, names prefixed `browser__`).

## Contracts (`shared/vault/Vault.kt`, plus additive changes)

- `CredentialId`, `CredentialKind` (login, payment_card, identity, note), `FieldType` (username,
  email, password, card_number, card_expiry, card_cvc, name, address, phone, otp, text),
  `FieldSpec(name, label, type, required)`, `CredentialMeta(id, name, kind, site, purpose,
  fields: List<FieldSpec>, hints: Map<String,String> (username hint, brand, last4, expiry month),
  createdAt, lastUsedAt)`.
- Envelope: `VaultEnvelope(deviceId, alg = "ECDH-ES-P256-HKDF-SHA256-A256GCM", epk, iv, ciphertext)`
  per secret field (or one per credential with a JSON body; document the choice), plus pure
  Kotlin field validators in `shared/vault/Validators.kt` (Luhn, expiry not past, CVC length by
  brand, email, phone E.164-ish) used by app and backend for metadata sanity.
- `Card.CredentialRequest(requestId)`; `CredentialRequest(id, kind, fields, site, purpose, status)`.
- `ProposedAction.UseCredential(credentialId, site, fields)` with a new `ActionKind.USE_CREDENTIAL`;
  `ActionKind.TWO_FACTOR_CODE` for asking the person for a code (`ProposedAction.AskCode(site, purpose)`).
- Routes (add to `ApiPaths`): `POST /api/v1/vault/devices` (register the device's vault public key),
  `GET /api/v1/vault/credentials`, `POST /api/v1/vault/credentials` (from a request: metadata +
  envelopes), `DELETE …/{id}`, `GET /api/v1/vault/requests/{id}`,
  `POST /api/v1/vault/grants/{heldId}` (phone, after biometric approval: the opened secret fields
  for that one approval, plus `ttlSeconds` ≤ 300), `POST /api/v1/vault/codes/{heldId}` (the person
  typed a 2FA code).

## Build

1. **Store**: `credential` (meta + per-device envelopes, ciphertext only), `credential_request`,
   `vault_device_key`. The backend never logs or returns ciphertext except to its device.
2. **Tools** (always loaded, byte-stable): `request_credential(kind, fields, site, purpose)` →
   creates a request + feed `CredentialRequest` card, returns the request id;
   `list_credentials()` (metadata only); `ask_code(site, purpose)` → held item, returns the code
   only into the grant store (the agent then uses `[secret:code_<heldId>]`).
3. **Browser tool wrapper** (`BrowserTools.kt`): wrap every `browser__*` MCP tool.
   - Hide raw-script tools (e.g. `evaluate_script`) entirely.
   - **Placeholders**: find `[secret:<credential>_<field>]` in string inputs. For each, look up a
     live **grant** (credential × registrable domain of the current page, from the last
     navigation/snapshot URL; use the Public Suffix List via a JVM library, e.g. Guava's
     `InternetDomainName`, or OkHttp's `PublicSuffixDatabase`). No grant → propose
     `UseCredential` through `ActionGate` (always held; payment cards never alone) and return
     "waiting for approval" text; with a grant → substitute values and call the MCP tool. Grants
     live in memory only, expire after the approved TTL (≤ 5 min), and are dropped on expiry.
   - **Gate** acting tools (click, fill, fill_form, type, press_key, select, upload, drag, hover is
     read-only): build a description from the page URL and the target uid's role/name from the
     last snapshot; deterministic rules first (target looks like send/submit/pay/buy/book/order/
     delete/confirm/accept/sign up/unsubscribe → `ExternalTool` proposal through the gate; payment
     pages and card fields → never alone), then a fast `triage`-role check for the rest (structured
     output: ok / ask). Read-only tools pass. Keep latency low: cache the triage verdict per (url,
     uid) for the grant window.
   - **Scrubbing**: a per-grant scrubber replaces every released value (plus variants: digits with
     spaces/dashes, URL-encoded, HTML-entity) with its placeholder in every browser tool result
     before it reaches the harness, the journal or logs. Refuse `take_screenshot` while a filled
     secret field may be visible (any substitution in the last N actions on that page) with a
     clear message.
4. **Approval release**: on `POST grants/{heldId}`: verify the held action is APPROVED for that
   device, the fields match the credential, and create the grant. Approving in the app without
   the secret (e.g. from the generic decision route) must not create a grant.
5. **Guard**: `USE_CREDENTIAL` → always ASK (never ALLOW alone; payment card → DENY unless the
   person approves on the phone: treat as ASK with the "never alone" label so the approval
   screen still offers yes-with-biometric). Update `GuardRules` tables.
6. **Cleanup**: lead and helpers move from `ToolPolicy.AllowAll` to a policy where external
   effects only happen through the gate/wrapper; remove `Access.REQUIRE_APPROVAL`,
   `HarnessEvent.ApprovalRequired` and `Dispatch.NeedsApproval` from the harness.

## Tests

Pure: placeholder parsing (incl. malformed, nested, escaped), registrable-domain extraction,
scrubbing variants, gate rules table (button texts in several phrasings, payment page detection),
grant expiry. Wrapper tests with a fake browser MCP: no grant → held; approved release → grant →
substitution reaches the fake browser while the harness-visible result and journal hold only the
placeholder; another site → asks again; expiry → asks again; screenshot refused after fill;
`evaluate_script` hidden. Route tests for device keys, credential create/list/delete, grant
release authorization. Testcontainers for the store. The value must never appear in any
persisted row: assert by scanning the DB after the flow.
