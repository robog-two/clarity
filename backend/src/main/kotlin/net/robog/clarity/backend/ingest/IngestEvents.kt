package net.robog.clarity.backend.ingest

import kotlinx.coroutines.flow.SharedFlow
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.Payload

/** Published after an ingest transaction commits, once per created or merged entity. */
data class Ingested(
    val connectorId: ConnectorId,
    val entityId: EntityId,
    val payload: Payload,
    val created: Boolean,
)

/** In-process fan-out of ingest events (triggers, future projections). */
interface IngestEventBus {
    val events: SharedFlow<Ingested>
    suspend fun publish(event: Ingested)
}
