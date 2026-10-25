package net.robog.clarity.backend.connectors

import arrow.core.Either
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.persistence.ConnectorConfigs
import net.robog.clarity.backend.persistence.ConnectorGrants
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wireName
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Connector identities, manifests, grants and configuration: the write side of connector
 * management. (The ingest pipeline reads grants via [grantedNouns] and never writes them.)
 */
@Inject
@SingleIn(AppScope::class)
class ConnectorRegistry(
    private val database: Database,
    private val clock: Clock,
    config: AppConfig,
) {
    private val autoGrant = config.connectors.autoGrant

    /** Resolves a bearer key hash to its connector and records the sighting. */
    suspend fun authenticateKey(keyHash: ByteArray): ConnectorId? = database.tx {
        Connectors.selectAll().where { Connectors.keyHash eq keyHash }.singleOrNull()
            ?.get(Connectors.id)?.let(::ConnectorId)
            ?.also { touch(it) }
    }

    /** Idempotently registers the implicit connector of an enrolled device (`device-<id>`). */
    suspend fun ensureDeviceConnector(deviceId: DeviceId): ConnectorId {
        val id = ConnectorId("device-${deviceId.value.lowercase()}")
        database.tx {
            Connectors.insertIgnore {
                it[Connectors.id] = id.value
                it[provisioned] = false
                it[createdAt] = clock.now()
            }
            touch(id)
        }
        return id
    }

    /** Registers (or re-keys) a connector whose key was provisioned out of band (a Podman secret). */
    suspend fun provision(id: ConnectorId, keyHash: ByteArray) = database.tx {
        Connectors.insertIgnore {
            it[Connectors.id] = id.value
            it[provisioned] = true
            it[Connectors.keyHash] = keyHash
            it[createdAt] = clock.now()
        }
        Connectors.update({ Connectors.id eq id.value }) {
            it[Connectors.keyHash] = keyHash
            it[provisioned] = true
        }
    }

    /** Stores the manifest and creates grant rows for newly announced nouns. */
    suspend fun acceptManifest(id: ConnectorId, manifest: ConnectorManifest) = database.tx {
        val now = clock.now()
        Connectors.update({ Connectors.id eq id.value }) {
            it[Connectors.manifest] = manifest
            it[lastSeenAt] = now
        }
        val existing = grantsOf(id)
        val initial = if (autoGrant) GrantState.GRANTED else GrantState.PENDING
        manifest.produces.forEach { noun ->
            // never override a user's explicit decision; autoGrant only lifts `pending`
            val current = existing[noun]
            if (current == null || (autoGrant && current == GrantState.PENDING)) writeGrant(id, noun, initial)
        }
    }

    suspend fun configOf(id: ConnectorId): ConnectorConfig = database.tx {
        touch(id)
        currentConfig(id)
    }

    suspend fun list(): List<ConnectorSummary> = database.tx {
        Connectors.selectAll().map { row ->
            val id = ConnectorId(row[Connectors.id])
            ConnectorSummary(id, row[Connectors.manifest], grantsOf(id), row[Connectors.lastSeenAt])
        }.sortedBy { it.id.value }
    }

    suspend fun setGrants(id: ConnectorId, grants: Map<Noun, GrantState>): Either<ConnectorError, Map<Noun, GrantState>> =
        database.tx {
            either {
                ensure(exists(id)) { ConnectorError.NotFound(id.value) }
                grants.forEach { (noun, state) -> writeGrant(id, noun, state) }
                grantsOf(id)
            }
        }

    suspend fun getConfig(id: ConnectorId): Either<ConnectorError, ConnectorConfig> = database.tx {
        either {
            ensure(exists(id)) { ConnectorError.NotFound(id.value) }
            currentConfig(id)
        }
    }

    /** Replaces the values and bumps the version so polling connectors notice. */
    suspend fun setConfig(id: ConnectorId, values: JsonObject): Either<ConnectorError, ConnectorConfig> = database.tx {
        either {
            ensure(exists(id)) { ConnectorError.NotFound(id.value) }
            val next = currentConfig(id).version + 1
            ConnectorConfigs.upsert(ConnectorConfigs.connectorId) {
                it[connectorId] = id.value
                it[version] = next
                it[ConnectorConfigs.values] = values
                it[updatedAt] = clock.now()
            }
            ConnectorConfig(next, values)
        }
    }

    private fun JdbcTransaction.exists(id: ConnectorId): Boolean =
        !Connectors.selectAll().where { Connectors.id eq id.value }.empty()

    private fun JdbcTransaction.touch(id: ConnectorId) {
        Connectors.update({ Connectors.id eq id.value }) { it[lastSeenAt] = clock.now() }
    }

    private fun JdbcTransaction.currentConfig(id: ConnectorId): ConnectorConfig =
        ConnectorConfigs.selectAll().where { ConnectorConfigs.connectorId eq id.value }.singleOrNull()
            ?.let { ConnectorConfig(it[ConnectorConfigs.version], it[ConnectorConfigs.values]) }
            ?: ConnectorConfig(0, JsonObject(emptyMap()))

    private fun JdbcTransaction.writeGrant(id: ConnectorId, noun: Noun, state: GrantState) {
        ConnectorGrants.upsert(ConnectorGrants.connectorId, ConnectorGrants.noun) {
            it[connectorId] = id.value
            it[ConnectorGrants.noun] = noun.wireName
            it[ConnectorGrants.state] = state.wireName
            it[updatedAt] = clock.now()
        }
    }
}
