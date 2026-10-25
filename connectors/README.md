# Writing a connector

A connector is a small, separate JVM service that **pushes** data from one source (IMAP, CalDAV, a
chat export, ...) into Clarity. It never reads from Clarity's database and cannot see other
connectors. `connectors/example` is the template: copy it.

## Create one

1. `cp -r connectors/example connectors/<name>`; rename the package and `applicationName`
   (`clarity-connector-<name>`) in its `build.gradle.kts`.
2. Add `":connectors:<name>"` to `settings.gradle.kts`.
3. Edit the `manifest` (kind, nouns it `produces`, JSON Schema for its settings) and the sync block.
4. Add the service to `deploy/compose.yaml` (copy `connector-example`), a secret
   `connector_<name>_key` mounted into **both** the backend and the connector, and an entry in the
   `matrix` of `.github/workflows/publish.yml` and `ci.yml`.

## The contract

```kotlin
suspend fun main() = runConnector(manifest) { config ->   // runs once per sync interval
    val records: List<IngestRecord> = fetchAndMap(config, state)
    push(records)          // chunked (<= 500), retried on 5xx/429/network errors, idempotent
}
```

The SDK (`connector-sdk`) handles: `PUT /ingest/v1/manifest` on start (retried until the backend is
up), polling `GET /ingest/v1/config` with `If-None-Match`, the sync loop, graceful SIGTERM shutdown.

| Env var | Default | Meaning |
|---|---|---|
| `CLARITY_URL` | `http://backend:8080` | Backend base URL |
| `CLARITY_CONNECTOR_KEY_FILE` | `/run/secrets/connector_key` | File holding the bearer key |
| `CLARITY_CONNECTOR_KEY` | | Inline key (dev only; wins over the file) |
| `CLARITY_SYNC_INTERVAL` | `5m` | ISO-8601 (`PT5M`) or Kotlin (`5m`, `1h 30m`) |
| `CLARITY_STATE_DIR` | `/data` | Connector-owned volume for `state` |

Rules for records:

- `(noun, sourceId, sourceVersion)` is the idempotency key. Use the source's stable id as `sourceId`
  and bump `sourceVersion` (ETag, MODSEQ, content hash) when the item changes. Re-pushing is free.
- Deleted upstream? Push an `IngestRecord.Tombstone`.
- Keep mapping from source item to `IngestRecord` a **pure function** and unit test it; keep IO in the
  sync block. `SyncScope.pushCatching` returns `Either<ClientError, ...>` if you want to handle
  failures as values; `push` throws `ClientException`.
- Keep cursors and caches in `state` (small JSON, atomically written to `/data`). Full-fidelity data
  belongs in the connector's own volume, not in the backend.
- `ClientError.Rejected` (4xx) means retrying is pointless: the key is wrong, the noun is not granted,
  or the payload is invalid. A `RecordOutcome.Rejected` for a single record is returned in the results.

## Access control model

- **Authentication**: each connector has a random key. The backend stores only its SHA-256.
  Provisioned connectors get the key through a Podman secret named `connector_<id>_key`, mounted
  into the connector and into the backend (which registers every such file at boot).
- **Authorization**: ingest is accepted only for nouns the user **granted** to that connector in the
  app. A new connector is `pending` until approved (dev instances can set
  `CLARITY_CONNECTORS_AUTO_GRANT=true`). The connector only declares what it *wants* in `produces`.
- **Network**: a connector sits on the internal `ingest` network (reaches only the backend) plus its
  own egress network for its upstream. It cannot reach Postgres, the browser tool, or other connectors.
- **Config & secrets**: the app edits config per connector; secret values are sealed to the
  connector's key (scaffolded in the DTOs, `ConnectorManifest.sealingKey`).
