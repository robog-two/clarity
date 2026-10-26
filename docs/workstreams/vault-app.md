# Workstream `vault-app`

Lane: `android/**`, plus the small backend pieces listed in 4–5 (`backend/.../vault/**`,
`governance/ActionGate.kt` decision path, a migration **`V8__approval_keys.sql`**) and additive
`shared/vault` contracts.

Read "Credential vault" and "Governance" in `ARCHITECTURE.md`, `docs/workstreams/vault-backend.md`
and the vault contracts it produced (`shared/vault/Vault.kt`, `Validators.kt`, the `ApiPaths.VAULT*`
routes, `ProposedAction.UseCredential` / `AskCode`, `Card.CredentialRequest`), the backend reference
implementation `backend/src/test/.../vault/VaultCryptoReference.kt` (envelope seal/open, must match
byte for byte), `backend/.../vault/VaultRoutes.kt`, and the app's Held/approval and card code.

Boundaries come from the person's interview: a kind at *never alone* (whatever it is: paying,
sending as them, ...) can only be approved with a yes on the phone, verified by the server;
*never* cannot be approved at all; vault releases are always *never alone*.

## Build

1. **Vault key** (phone): EC P-256 in the Android Keystore, `PURPOSE_AGREE_KEY`, StrongBox when
   available, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`
   (auth for every use), invalidated on new biometric enrollment (then re-register and tell the
   person their stored credentials must be re-entered). Register with `POST /api/v1/vault/devices`.
2. **Approval key** (phone): a separate P-256 signing key, same auth-per-use parameters. Register
   it with the vault key (extend `RegisterVaultKey` additively with `approvalKey`).
3. **Credential form card**: render `Card.CredentialRequest` as a form from the request's
   `FieldSpec`s with the shared validators live (Luhn, expiry, CVC by brand, email, phone), secret
   fields masked with show/hide, autofill disabled for them (`importantForAutofill=no`, no
   keyboard learning: `KeyboardOptions(autoCorrect=false)` + `IME_FLAG_NO_PERSONALIZED_LEARNING`).
   On save: seal per the envelope scheme for every registered device key (`GET devices`), send
   `NewCredential` with only the allowed hints (username, brand, last4, expiry_month).
4. **Approving never-alone actions** (backend + app): for a held action whose kind is at
   *never alone* for this person (or a vault kind), the generic decision route refuses approve
   (extend the existing vault check to all never-alone kinds) and a new route
   `POST /api/v1/held/{id}/signed-decision` takes `{decision, signature}` where the signature is
   ES256 by the device's approval key over `"clarity-approval-v1|<heldId>|<sha256 of the
   canonical action JSON>|<decision>"`; the backend verifies against the registered approval key
   before approving. The approval screen shows what, where, why (agent view + rationale), then
   **yes** runs `BiometricPrompt` with a `CryptoObject` for the signing key (and for
   `UseCredential` also the vault key agreement to open the envelope, then `POST grants/{heldId}`
   with the fields and `ttlSeconds = 300`). **No** is a plain decline.
5. **Two-factor code**: `AskCode` held items show a one-field numeric form; send via
   `POST /api/v1/vault/codes/{heldId}` after the same biometric yes.
6. **Inbox notice** (backend): when a credential is saved for a request, enqueue an inbox item so
   the lead knows it can continue ("credential `<name>` for `<site>` is ready").
7. **Vault screen** in Settings: list credentials (metadata only), delete, re-key after biometric
   change.

## Tests

JVM: envelope seal on the phone side opens with the backend reference (shared test vectors), field
validators, form state, signature payload construction, approval view models. Backend: signed
decision verification (good, wrong key, wrong action hash, replay of a used signature, never tier
still refused), generic route refuses never-alone kinds, inbox notice. `./gradlew assembleDebug
testDebugUnitTest lint` in `android/` and `:backend:test` must pass.
