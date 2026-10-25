# Clarity

A self-hosted, single-tenant, proactive personal assistant. One deployment serves one person;
the Android app is the interface. Connectors sync your email, calendars and other sources in
their own isolated containers and push normalized, deduplicated records into the backend, where
an agent harness (Claude via the Messages API) works over them on a schedule or when a trigger fires.

> Status: scaffolding. The plumbing is in place; product features are not built yet.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design.

## Layout

| Path | |
|---|---|
| `shared/` | Kotlin Multiplatform wire contracts (DTOs) used by every component |
| `backend/` | Ktor API: auth, ingest + dedup, triggers, scheduler, agent harness, MCP server |
| `connector-sdk/` | Library for writing connectors |
| `connectors/` | Connector containers (`example/` is the template) |
| `android/` | Jetpack Compose app (separate Gradle build) |
| `deploy/` | Podman compose, container build, secrets bootstrap |
| `build-logic/`, `gradle/libs.versions.toml` | Convention plugins and the single version catalog |

## Development

Requirements: JDK 25, Podman (with `podman compose` or `podman-compose`), and for the app the
Android SDK (platform 37).

```sh
./gradlew build                       # shared, backend, connector SDK, connectors
(cd android && ./gradlew assembleDebug)

./deploy/init-secrets.sh              # once: generates deploy/secrets/*
podman compose -f deploy/compose.yaml --profile example up -d --build
```

Then open the app, enter your instance URL, and enroll. The first device is trusted on first use;
additional devices need the enrollment token printed in the backend log on first boot (also
stored in the backend's data volume as `enrollment-token`). See [deploy/README.md](deploy/README.md).

## Branches and releases

- `dev`: integration branch. Open PRs against `dev`.
- `main`: release. Every push to `main` publishes multi-arch images to
  `ghcr.io/robog-two/clarity-<service>`.

## Package

All code lives under `net.robog.clarity`.
