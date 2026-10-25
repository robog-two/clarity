package net.robog.clarity.backend.ingest

import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.SourceLinks
import net.robog.clarity.backend.persistence.SourceRecords
import net.robog.clarity.backend.persistence.wireName
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

/** Idempotency ledger (`source_record`) and source-to-entity links (`source_link`). */
internal object SourceLedger {

    fun priorSighting(tx: JdbcTransaction, connector: ConnectorId, noun: Noun, source: SourceId, version: String): PriorSighting? = with(tx) {
        val seen = !SourceRecords.selectAll().where {
            (SourceRecords.connectorId eq connector.value) and (SourceRecords.noun eq noun.wireName) and
                (SourceRecords.sourceId eq source.value) and (SourceRecords.sourceVersion eq version)
        }.empty()
        if (!seen) null else PriorSighting(linkedEntity(connector, noun, source)?.let { EntityId(it.toString()) })
    }

    private fun JdbcTransaction.linkedEntity(connector: ConnectorId, noun: Noun, source: SourceId): Uuid? =
        SourceLinks.selectAll().where {
            (SourceLinks.connectorId eq connector.value) and (SourceLinks.noun eq noun.wireName) and (SourceLinks.sourceId eq source.value)
        }.singleOrNull()?.get(SourceLinks.entityId)

    fun recordSighting(tx: JdbcTransaction, connector: ConnectorId, noun: Noun, source: SourceId, version: String, now: Instant) {
        tx.run {
            SourceRecords.insertIgnore {
                it[connectorId] = connector.value
                it[SourceRecords.noun] = noun.wireName
                it[sourceId] = source.value
                it[sourceVersion] = version
                it[receivedAt] = now
            }
        }
    }

    fun link(tx: JdbcTransaction, connector: ConnectorId, noun: Noun, source: SourceId, entity: Uuid, version: String) {
        tx.run {
            SourceLinks.upsert(SourceLinks.connectorId, SourceLinks.noun, SourceLinks.sourceId) {
                it[connectorId] = connector.value
                it[SourceLinks.noun] = noun.wireName
                it[sourceId] = source.value
                it[entityId] = entity
                it[latestVersion] = version
                it[deletedAt] = null
            }
        }
    }

    /**
     * Marks the link deleted and reports the entity that is now orphaned, i.e. no other connector
     * still has a live link to it. Returns null if there was no link or the entity is still in use.
     */
    fun unlink(tx: JdbcTransaction, connector: ConnectorId, noun: Noun, source: SourceId, version: String, now: Instant): Uuid? = tx.run {
        val entity = linkedEntity(connector, noun, source) ?: return null
        SourceLinks.update({
            (SourceLinks.connectorId eq connector.value) and (SourceLinks.noun eq noun.wireName) and (SourceLinks.sourceId eq source.value)
        }) {
            it[latestVersion] = version
            it[deletedAt] = now
        }
        val stillReferenced = !SourceLinks.selectAll().where {
            (SourceLinks.entityId eq entity) and SourceLinks.deletedAt.isNull()
        }.empty()
        if (stillReferenced) null else entity
    }
}
