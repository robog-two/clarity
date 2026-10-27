# Clarity

A self-hosted, single-tenant, proactive personal assistant. One deployment serves one person;
the Android app is the interface. Connectors sync your email, calendars, contacts and files in
their own isolated containers and push normalized, deduplicated records into the backend. A lead
agent (Claude via the Messages API) works over them: it stays quiet unless something is worth your
attention, checks every action that leaves as you against your own written constitution, holds what
needs your OK, and calls you for a short morning planning session.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design and [docs/workstreams/](docs/workstreams/) for how it is built.

## Layout

| Path | |
|---|---|
| `shared/` | Kotlin Multiplatform wire contracts (DTOs) used by every component |
| `backend/` | Ktor API: auth, ingest + dedup, triggers, scheduler, the agent harness and lead agent, governance (constitution, guard, held actions, action outbox), sessions (morning call, values interview, amend), feed + SSE, MCP server |
| `connector-sdk/` | Library for writing connectors |
| `connectors/` | Connector containers: `example/` (the template), `mail` (IMAP/SMTP), `dav` (CalDAV, CardDAV, WebDAV) |
| `computers/` | The agent's tools as containers: `sandbox/` (Linux workspace over MCP), `browser/` (Firefox over MCP), `egress/` (the outbound proxy both go through) |
| `android/` | Jetpack Compose app (separate Gradle build) |
| `deploy/` | Podman compose, container builds, secrets bootstrap, Caddy (TLS) |
| `e2e/` | End-to-end test of the compose stack (`e2e/run.sh`) |
| `build-logic/`, `gradle/libs.versions.toml` | Convention plugins and the single version catalog |

## Getting started

Requirements: Podman with `podman compose` (or `podman-compose`), and the Android app on a phone.
To build from source also JDK 25 and, for the app, the Android SDK (platform 37).

```sh
./deploy/init-secrets.sh                     # once: generates deploy/secrets/* and loads them as Podman secrets
echo -n 'sk-or-...' > deploy/secrets/openrouter_api_key  # required: every model runs on OpenRouter
./deploy/init-secrets.sh                     # re-run to load the key

# core stack: postgres, backend, egress proxy, caddy (TLS). Add profiles for what you use:
podman compose -f deploy/compose.yaml --profile mail --profile dav --profile computers up -d --build
```

- `--build` builds the images from this checkout; without it they are pulled from
  `ghcr.io/robog-two/clarity-*`. Profiles: `example`, `mail`, `dav`, `computers` (sandbox and browser;
  also set `CLARITY_MCP_SERVERS`, see [deploy/README.md](deploy/README.md)).
- The app reaches the backend through Caddy at `https://<CLARITY_DOMAIN>` (default `localhost`, which
  uses Caddy's own CA; use a real domain for a phone). The backend's plain HTTP port is loopback only.
- **First run in the app**: enter the server address (and the setup code, i.e. the enrollment token from
  `podman compose logs backend`, if the instance is not in trust-on-first-use mode), check that the server has its
  model key, connect your sources, answer the values interview (it becomes your constitution, version 1), and pick
  the time of the morning call. The backend does not start without the OpenRouter key.
- Nothing goes out as you without your review until you say so in the constitution; held actions show up in the app.

Everything about running the stack (profiles, secrets, the agent's computers, outbound traffic, TLS, enrolling,
adding connectors, teardown) is in [deploy/README.md](deploy/README.md).

## Development

```sh
./gradlew build                              # shared, backend, connector SDK, connectors (tests use Testcontainers: needs a container runtime)
(cd android && ./gradlew assembleDebug)
```

Tests, from cheapest to most expensive:

| | How | Real model? |
|---|---|---|
| Unit and route tests | `./gradlew :backend:test` (Postgres via Testcontainers; with Podman set `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true`) | no |
| Scenario evals | `backend/src/test/.../evals/`: the lead against a real database and a scripted model gateway. Pre-emption, batching, prompt injection held by the guard, a morning call over the session routes, held to amend to constitution v2, helpers, restart without repeating actions, cache-prefix stability over 20 turns. Part of `:backend:test`, so part of CI | no |
| Live evals | `OPENROUTER_API_KEY=... ./gradlew :backend:test --tests '*LiveEvalTest' -Pclarity.liveEvals` (`CLARITY_AGENT_LEAD_MODEL=anthropic/claude-opus-5.5` for another lead). A few recorded scenarios against the real models, graded by a rubric (guard-role model call). Costs money, prints token usage including cache reads. Never runs in CI | yes |
| End-to-end stack | `e2e/run.sh`. Brings up the compose stack under its own project name with throwaway secrets (no model call leaves the machine), enrolls a device (`e2e/DeviceCli.java` signs ES256 JWTs like the app), exercises the API, a connector, the feed and the egress policy, tears everything down. Needs Podman, curl, jq, a JDK; `E2E_SKIP_BROWSER=1` saves 1.5 GB of RAM. Runs as the `e2e` job in CI | no |

## Branches and releases

- `dev`: integration branch. Open PRs against `dev`.
- `main`: release. Every push to `main` publishes multi-arch images to
  `ghcr.io/robog-two/clarity-<service>` and a signed APK as a GitHub Release.

## Package

All code lives under `net.robog.clarity`.
