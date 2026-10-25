package net.robog.clarity.app.connector

/**
 * The phone as a connector (ARCHITECTURE: "The phone as a connector").
 *
 * Future behaviour: collect on-device data (SMS, contacts, calendar, ...), map it to
 * `IngestRecord`s, and push `IngestBatch`es (<= `IngestBatch.MAX_RECORDS`) with
 * `POST /ingest/v1/batches` authenticated by the same per-request device JWT used for the app API.
 * The backend treats the device as connector `device-<deviceId>`, which only ingests nouns the
 * user granted (grants start `pending`). Re-sending is safe: the idempotency key is
 * `(connector, noun, sourceId, sourceVersion)`.
 *
 * Scheduling (WorkManager) is intentionally not wired up yet.
 */
interface PhoneConnector {
    /** Connector id the backend assigns to this device, e.g. `device-<deviceId>`. */
    fun connectorId(deviceId: String): String = "device-$deviceId"

    /** Collects and pushes pending data once. Returns how many records were sent. */
    suspend fun syncOnce(): Int
}

class NoOpPhoneConnector : PhoneConnector {
    override suspend fun syncOnce(): Int = 0
}
