# Workstream `app-core`

Lane: `android/**` (the separate Gradle build), the catalog entries it needs, and
`shared/src/commonMain/.../connector/Sealing.kt` helpers only if you need a shared constant.

Read "Android app", "Sessions and voice" and "HTTP surface" in `ARCHITECTURE.md`, the shared
contracts (`shared/feed`, `shared/session`, `shared/connector`, `shared/governance`), the existing
app code (`android/app/src/**`, it already has enrollment, device JWT, Ktor client, Navigation 3,
Metro DI), and the prototype screens `docs/design/prototype/Onboarding.dc.html` and
`docs/design/prototype/App.dc.html` (HTML + a small JS state machine per screen: the markup is the
layout, `renderVals()` and the `defs()`/`qs()` data are the copy and flow).

The person chose **Material style**: build with Material 3 components, styled after the
prototype. The backend routes for feed, sessions, settings and model key land in later waves:
code against the shared contracts and `ApiPaths`, and make every screen work against fake
repositories in tests and previews. Treat a 404 from a not-yet-built route as "not available on
this server yet", not a crash.

## Build

1. **Theme.** A Material 3 `ColorScheme` from the prototype's `.app` CSS variables (`--primary`
   #466660, `--pc` primary container #CDE7DF, `--surface` #FBF8F4, …; see any `.dc.html` helmet).
   Derive a dark scheme with the same hues. Typography: Roboto Flex for UI, Libre Caslon Text for
   reading text (the `.rd`/`.sm` classes); bundle both fonts as `res/font` (OFL, include the
   license files; no Google Play downloadable fonts, the app must work on de-Googled phones).
   Shapes: 24 dp pill buttons, 16 dp cards, 12 dp context boxes, as in the prototype. Lowercase
   UI labels as in the prototype copy.
2. **Networking.** Extend `ClarityApi` with every app route in `ARCHITECTURE.md` (typed with the
   shared DTOs, all returning `ApiResult`). SSE feed client (`ktor-client` SSE plugin) on
   `ApiPaths.EVENTS`: decodes `FeedEvent`, reconnects with backoff and a fresh device JWT,
   resumes from the last `seq` (fetch `FEED?after=` on reconnect to fill gaps).
3. **Feed store.** A local cache of feed items, tasks and held summaries so the app opens instantly
   (Room with KSP, or SQLDelight; pick whichever builds cleanly with AGP 9 and Kotlin 2.4 and say
   why). Repositories expose `Flow`s; the SSE client writes into the store.
4. **Card renderer.** One `@Composable CardView(card)` for every `Card` subtype, matching the
   prototype (`.ag` agent text, `.you`/`.live` bubbles, `.ctx` context box, `.chip` lists, the plan
   box, `.note`, proposal box with its buttons via a callback, tasks list). A streaming text
   item shows deltas as they arrive. Compose previews for each card.
5. **Voice.** `voice/`: a `SpeechInput` wrapper over `SpeechRecognizer.createOnDeviceSpeechRecognizer`
   (fall back to the default recognizer if on-device isn't available), exposing
   `Flow<SpeechEvent>` (partial text, final segment, listening state, error). "Mic stays on"
   mode restarts listening after each final segment until stopped; mute pauses it. Request
   `RECORD_AUDIO` with a rationale. A `MicStateChip` ("● listening" / "● mic on" / "○ muted").
   Final segments become `UserInput(source = VOICE)`.
6. **Onboarding**, as in `Onboarding.dc.html`, replacing the current onboarding screens:
   welcome → server (address + optional setup code = enrollment token; "check server" shows
   reachable/TLS/latency, then enrolls) → model (API key field, masked as in the prototype;
   `PUT setup/model-key`; shows "key works") → connectors list (from `GET /api/v1/connectors`; each
   opens a setup screen whose form is generated from the manifest's `configSchema` JSON Schema:
   string/password(`format: password` or `writeOnly`)/url/email/number/boolean fields; secret
   fields sealed per `Sealing` with **Google Tink HPKE** before `PUT config`; then grant the
   connector's nouns; "sync all" calls `POST /connectors/{id}/test` and shows per-connector
   status/failure as in the prototype) → values intro → values interview (start a
   `VALUES_INTERVIEW` session; question text, live/said answer bubbles from voice, "captured as"
   line box, reword / keep → via `SessionControl`) → constitution review (`GET /api/v1/constitution`
   rendered by section as in the prototype) → morning call schedule (`Settings.MorningCall`: time
   pills, day toggles, heads-up, call vs notify; `PUT settings`) → done screen.
   On-phone connectors in the list: "messages" (SMS read permission; the phone connector itself is
   a later workstream, here only the permission + grant step).
7. **Navigation**: onboarding until setup is complete (`SetupStatus`), then a placeholder main
   scaffold with the four bottom tabs from `App.dc.html` (today, ask, held, settings) with empty
   states; the real screens are the `app-screens` workstream.

Note: `android/settings.gradle.kts` restricts `com.google.*` to the Google Maven repo, but Tink
(`com.google.crypto.tink:tink-android`) is on Maven Central: adjust the content filters so it
resolves (exclude `com.google.crypto.tink` from the google-only rule).

## Tests

Unit tests (JVM, no device): theme/token mapping, JSON-Schema → form model, field validation,
sealing round-trip (seal with Tink, open with a test private key), SSE client resume logic over
`ktor-client-mock` (reconnect, gap fill, dedup by seq), every onboarding view model's state
transitions with fake repositories, speech-event → input mapping. `./gradlew assembleDebug
testDebugUnitTest lint` must pass in `android/`.

Machine limits apply (see README): for the Android build pass
`-Dorg.gradle.jvmargs=-Xmx2g --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`.
