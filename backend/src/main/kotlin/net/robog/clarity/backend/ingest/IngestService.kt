package net.robog.clarity.backend.ingest

import arrow.core.Either
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import net.robog.clarity.backend.connectors.grantedNouns
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.IngestResponse
import net.robog.clarity.shared.ingest.RecordOutcome
import net.robog.clarity.shared.ingest.RecordResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

private val log = KotlinLogging.logger {}

/**
 * The ingest pipeline's effectful shell. Each record is decided by the pure [planRecord] and then
 * executed here; the whole batch shares one transaction, and events are published only after it
 * commits so subscribers never observe rolled-back data.
 */
@Inject
@SingleIn(AppScope::class)
class IngestService(
    private val database: Database,
    private val clock: Clock,
    private val bus: IngestEventBus,
) {
    suspend fun ingest(connector: ConnectorId, batch: IngestBatch): Either<IngestError, IngestResponse> {
        val checked = either<IngestError, Unit> {
            ensure(batch.records.size <= IngestBatch.MAX_RECORDS) {
                IngestError.BatchTooLarge(batch.records.size, IngestBatch.MAX_RECORDS)
            }
        }
        if (checked.isLeft()) return checked.map { IngestResponse(emptyList()) }

        val processed = database.tx {
            val granted = grantedNouns(connector)
            val events = mutableListOf<Ingested>()
            val results = batch.records.map { record ->
                val outcome = either<RecordRejection, RecordPlan> { planRecord(record, previousOf(connector, record), granted) }
                    .fold({ RecordOutcome.Rejected(it.reason) }, { execute(connector, record, it, events) })
                RecordResult(record.sourceId, outcome)
            }
            IngestResponse(results) to events.toList()
        }
        processed.second.forEach { bus.publish(it) }
        log.debug { "ingest from $connector: ${processed.first.results.size} records, ${processed.second.size} events" }
        return Either.Right(processed.first)
    }

    private fun JdbcTransaction.previousOf(connector: ConnectorId, record: IngestRecord): PriorSighting? =
        SourceLedger.priorSighting(this, connector, record.targetNoun, record.sourceId, record.sourceVersion)

    private fun JdbcTransaction.execute(
        connector: ConnectorId,
        record: IngestRecord,
        plan: RecordPlan,
        events: MutableList<Ingested>,
    ): RecordOutcome {
        val now = clock.now()
        return when (plan) {
            is RecordPlan.Replay -> RecordOutcome.Unchanged(plan.entityId)
            is RecordPlan.Apply -> {
                val upserted = upsertCanonical(plan.payload, plan.key, now)
                SourceLedger.recordSighting(this, connector, plan.payload.noun, record.sourceId, record.sourceVersion, now)
                SourceLedger.link(this, connector, plan.payload.noun, record.sourceId, upserted.id, record.sourceVersion)
                val entityId = EntityId(upserted.id.toString())
                events += Ingested(connector, entityId, plan.payload, upserted.created)
                if (upserted.created) RecordOutcome.Created(entityId) else RecordOutcome.Merged(entityId)
            }
            is RecordPlan.Remove -> {
                SourceLedger.unlink(this, connector, plan.noun, record.sourceId, record.sourceVersion, now)
                    ?.let { softDelete(plan.noun, it, now) }
                SourceLedger.recordSighting(this, connector, plan.noun, record.sourceId, record.sourceVersion, now)
                RecordOutcome.Removed
            }
        }
    }
}
