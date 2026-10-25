# Build plan

The full product (see `ARCHITECTURE.md` and `docs/design/prototype/`) is built in workstreams.
Each workstream is one spec file here, implemented by one agent on its own branch
(`ws/<id>`), merged into `dev` by the orchestrator after review.

| Wave | Id | Workstream | Depends on |
|---|---|---|---|
| 0 | — | Architecture, `:shared` contracts, this plan (orchestrator) | — |
| 1 | `harness` | Harness: compaction, tool search, caching, model roles, journal/resume | 0 |
| 1 | `governance` | Constitution, guard, held actions, action outbox, notify policy, connector-sdk actions | 0 |
| 1 | `computers` | Sandbox (`sandboxd`) + Firefox browser containers, compose wiring | 0 |
| 2 | `lead` | Lead thread, inbox, helpers, memory, feed + SSE, durable scheduler, domain tools | harness, governance |
| 2 | `connectors` | New noun (document); `mail` and `dav` connectors with actions | governance |
| 2 | `app-core` | Android: theme, networking + SSE, card renderer, voice, onboarding | 0 |
| 2 | `egress` | Egress proxy: browser can reach only the public internet, backend only the model API | computers |
| 3 | `sessions` | Morning call, values interview, amend, Today, settings, setup API | lead |
| 3 | `app-screens` | Android: call + scheduling/notifications, Today, Ask, Held, Amend, Settings, phone connector | app-core, sessions contracts |
| 3 | `vault` | Credential vault: phone-held key, forms, fill + scrubbing, biometric approval | governance, computers, lead |
| 3 | `push-evals` | Push over the SSE feed (foreground service), scenario evals, end-to-end test, docs | lead, sessions |

## Rules for every workstream

- **Read first**: `ARCHITECTURE.md`, this file, your spec, and the code you touch. Follow the
  principles there (functional core, Arrow `Raise` for domain errors, contracts in `:shared`).
- **Contracts**: `:shared` types are the interface between workstreams. You may *add* fields
  with defaults or new types if you need them; do not rename or remove existing ones. Note any
  addition in your final report.
- **Stay in your lane**: only change files your spec names, plus small, necessary edits
  elsewhere (DI graph wiring, config, catalog, compose). List every file outside your lane you
  touched and why.
- **Libraries**: prefer well-maintained open-source Kotlin/JVM libraries over hand-rolled code.
  Add them to `gradle/libs.versions.toml` (exact versions, verified to exist on Maven Central or
  the stated repository). Check the version you pick actually resolves.
- **Machine limits** (4 cores, 7 GB RAM, other agents build at the same time): build only what
  you changed, e.g. `./gradlew :backend:test --tests '…' -Dorg.gradle.jvmargs=-Xmx1536m
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`. Never run `./gradlew build`
  for the whole repo more than once at the end. Stop Gradle daemons when done
  (`./gradlew --stop`). Do not run more than one container build at a time.
- **Tests**: unit tests for every pure function; Ktor `testApplication` for routes;
  Testcontainers Postgres for persistence (the integration test shows the pattern). Tests must
  not call the real Anthropic API; use the scripted `ModelGateway` fakes.
- **No secrets** in code, tests or commits. No network calls to third parties in tests.
- **Commit** on your branch in small logical commits with clear messages; do not push.
- **Final report** (your last message): what you built, what you skipped and why, contract
  additions, files touched outside your lane, how you tested, and open questions.
