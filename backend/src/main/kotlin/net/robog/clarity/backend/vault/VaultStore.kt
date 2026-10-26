package net.robog.clarity.backend.vault

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.VaultEnvelope
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.jsonb

// Exposed table objects mirror db/migration/V7__vault.sql. They are never used to create schema.

object VaultDeviceKeys : Table("vault_device_key") {
    val deviceId = uuid("device_id")
    val publicKey = text("public_key")
    val alg = text("alg")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(deviceId)
}

object Credentials : Table("credential") {
    val id = uuid("id")
    val name = text("name")
    val kind = text("kind")
    val site = text("site")
    val purpose = text("purpose")
    val fieldSpecs = jsonb<List<FieldSpec>>("fields", ClarityJson)
    val hints = jsonb<Map<String, String>>("hints", ClarityJson)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object CredentialRequests : Table("credential_request") {
    val id = uuid("id")
    val name = text("name")
    val kind = text("kind")
    val fieldSpecs = jsonb<List<FieldSpec>>("fields", ClarityJson)
    val site = text("site")
    val purpose = text("purpose")
    val status = text("status")
    val credentialId = uuid("credential_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object CredentialEnvelopes : Table("credential_envelope") {
    val credentialId = uuid("credential_id")
    val deviceId = uuid("device_id")
    val envelope = jsonb<VaultEnvelope>("envelope", ClarityJson)
    override val primaryKey = PrimaryKey(credentialId, deviceId)
}

/** What [VaultStore.createCredential] can refuse. */
sealed interface CreateOutcome {
    data class Created(val meta: CredentialMeta) : CreateOutcome
    data object NameTaken : CreateOutcome
    data object RequestNotOpen : CreateOutcome
    data object UnknownRequest : CreateOutcome
}

/** Persistence of the vault: ciphertext and metadata only. */
interface VaultStore {
    suspend fun upsertDeviceKey(key: DeviceVaultKey): DeviceVaultKey
    suspend fun deviceKeys(): List<DeviceVaultKey>
    suspend fun deviceKey(device: DeviceId): DeviceVaultKey?

    suspend fun createRequest(name: String, kind: CredentialKind, fields: List<FieldSpec>, site: String, purpose: String): CredentialRequest
    suspend fun request(id: CredentialRequestId): CredentialRequest?
    suspend fun cancelRequest(id: CredentialRequestId): Boolean

    /** Completes an open request into a credential with [envelopes], atomically. */
    suspend fun createCredential(request: CredentialRequestId, hints: Map<String, String>, envelopes: List<VaultEnvelope>): CreateOutcome

    suspend fun list(): List<CredentialMeta>
    suspend fun get(id: CredentialId): CredentialMeta?
    suspend fun byName(name: String): CredentialMeta?
    suspend fun delete(id: CredentialId): Boolean
    suspend fun touch(id: CredentialId)

    /** The calling device's own sealed copy; never anyone else's. */
    suspend fun envelopeFor(id: CredentialId, device: DeviceId): VaultEnvelope?
}

private fun uuidOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedVaultStore(private val database: Database, private val clock: Clock) : VaultStore {

    override suspend fun upsertDeviceKey(key: DeviceVaultKey): DeviceVaultKey = database.tx {
        val device = Uuid.parse(key.deviceId.value)
        val now = clock.now()
        val updated = VaultDeviceKeys.update({ VaultDeviceKeys.deviceId eq device }) {
            it[publicKey] = key.publicKey
            it[alg] = key.alg
            it[createdAt] = now
        }
        if (updated == 0) {
            VaultDeviceKeys.insert {
                it[deviceId] = device
                it[publicKey] = key.publicKey
                it[alg] = key.alg
                it[createdAt] = now
            }
        }
        key.copy(createdAt = now)
    }

    override suspend fun deviceKeys(): List<DeviceVaultKey> = database.tx {
        VaultDeviceKeys.selectAll().orderBy(VaultDeviceKeys.createdAt).map { it.toKey() }
    }

    override suspend fun deviceKey(device: DeviceId): DeviceVaultKey? {
        val id = uuidOrNull(device.value) ?: return null
        return database.tx { VaultDeviceKeys.selectAll().where { VaultDeviceKeys.deviceId eq id }.singleOrNull()?.toKey() }
    }

    override suspend fun createRequest(
        name: String,
        kind: CredentialKind,
        fields: List<FieldSpec>,
        site: String,
        purpose: String,
    ): CredentialRequest = database.tx {
        val id = Uuid.random()
        val now = clock.now()
        CredentialRequests.insert {
            it[CredentialRequests.id] = id
            it[CredentialRequests.name] = name
            it[CredentialRequests.kind] = kind.wire()
            it[fieldSpecs] = fields
            it[CredentialRequests.site] = site
            it[CredentialRequests.purpose] = purpose
            it[status] = CredentialRequestStatus.OPEN.wire()
            it[createdAt] = now
        }
        CredentialRequests.selectAll().where { CredentialRequests.id eq id }.single().toRequest()
    }

    override suspend fun request(id: CredentialRequestId): CredentialRequest? {
        val uuid = uuidOrNull(id.value) ?: return null
        return database.tx { CredentialRequests.selectAll().where { CredentialRequests.id eq uuid }.singleOrNull()?.toRequest() }
    }

    override suspend fun cancelRequest(id: CredentialRequestId): Boolean {
        val uuid = uuidOrNull(id.value) ?: return false
        return database.tx {
            CredentialRequests.update({ (CredentialRequests.id eq uuid) and (CredentialRequests.status eq CredentialRequestStatus.OPEN.wire()) }) {
                it[status] = CredentialRequestStatus.CANCELLED.wire()
            } > 0
        }
    }

    override suspend fun createCredential(
        request: CredentialRequestId,
        hints: Map<String, String>,
        envelopes: List<VaultEnvelope>,
    ): CreateOutcome {
        val requestUuid = uuidOrNull(request.value) ?: return CreateOutcome.UnknownRequest
        return database.tx {
            val row = CredentialRequests.selectAll().where { CredentialRequests.id eq requestUuid }.forUpdate().singleOrNull()
                ?: return@tx CreateOutcome.UnknownRequest
            val req = row.toRequest()
            if (req.status != CredentialRequestStatus.OPEN) return@tx CreateOutcome.RequestNotOpen
            if (Credentials.selectAll().where { Credentials.name eq req.name }.any()) return@tx CreateOutcome.NameTaken
            val id = Uuid.random()
            val now = clock.now()
            Credentials.insert {
                it[Credentials.id] = id
                it[name] = req.name
                it[kind] = req.kind.wire()
                it[site] = req.site
                it[purpose] = req.purpose
                it[fieldSpecs] = req.fields
                it[Credentials.hints] = hints
                it[createdAt] = now
            }
            envelopes.forEach { env ->
                CredentialEnvelopes.insert {
                    it[credentialId] = id
                    it[deviceId] = Uuid.parse(env.deviceId.value)
                    it[envelope] = env
                }
            }
            CredentialRequests.update({ CredentialRequests.id eq requestUuid }) {
                it[status] = CredentialRequestStatus.FULFILLED.wire()
                it[credentialId] = id
            }
            CreateOutcome.Created(Credentials.selectAll().where { Credentials.id eq id }.single().toMeta())
        }
    }

    override suspend fun list(): List<CredentialMeta> = database.tx {
        Credentials.selectAll().orderBy(Credentials.createdAt, SortOrder.ASC).map { it.toMeta() }
    }

    override suspend fun get(id: CredentialId): CredentialMeta? {
        val uuid = uuidOrNull(id.value) ?: return null
        return database.tx { Credentials.selectAll().where { Credentials.id eq uuid }.singleOrNull()?.toMeta() }
    }

    override suspend fun byName(name: String): CredentialMeta? =
        database.tx { Credentials.selectAll().where { Credentials.name eq name }.singleOrNull()?.toMeta() }

    override suspend fun delete(id: CredentialId): Boolean {
        val uuid = uuidOrNull(id.value) ?: return false
        return database.tx { Credentials.deleteWhere { Credentials.id eq uuid } > 0 }
    }

    override suspend fun touch(id: CredentialId) {
        val uuid = uuidOrNull(id.value) ?: return
        val now = clock.now()
        database.tx { Credentials.update({ Credentials.id eq uuid }) { it[lastUsedAt] = now } }
    }

    override suspend fun envelopeFor(id: CredentialId, device: DeviceId): VaultEnvelope? {
        val credential = uuidOrNull(id.value) ?: return null
        val dev = uuidOrNull(device.value) ?: return null
        return database.tx {
            CredentialEnvelopes.selectAll()
                .where { (CredentialEnvelopes.credentialId eq credential) and (CredentialEnvelopes.deviceId eq dev) }
                .singleOrNull()?.get(CredentialEnvelopes.envelope)
        }
    }
}

private fun ResultRow.toKey() = DeviceVaultKey(
    deviceId = DeviceId(this[VaultDeviceKeys.deviceId].toString()),
    publicKey = this[VaultDeviceKeys.publicKey],
    alg = this[VaultDeviceKeys.alg],
    createdAt = this[VaultDeviceKeys.createdAt],
)

private fun ResultRow.toMeta() = CredentialMeta(
    id = CredentialId(this[Credentials.id].toString()),
    name = this[Credentials.name],
    kind = parseWire<CredentialKind>(this[Credentials.kind]) ?: CredentialKind.NOTE,
    site = this[Credentials.site],
    purpose = this[Credentials.purpose],
    fields = this[Credentials.fieldSpecs],
    hints = this[Credentials.hints],
    createdAt = this[Credentials.createdAt],
    lastUsedAt = this[Credentials.lastUsedAt],
)

private fun ResultRow.toRequest() = CredentialRequest(
    id = CredentialRequestId(this[CredentialRequests.id].toString()),
    name = this[CredentialRequests.name],
    kind = parseWire<CredentialKind>(this[CredentialRequests.kind]) ?: CredentialKind.NOTE,
    fields = this[CredentialRequests.fieldSpecs],
    site = this[CredentialRequests.site],
    purpose = this[CredentialRequests.purpose],
    status = parseWire<CredentialRequestStatus>(this[CredentialRequests.status]) ?: CredentialRequestStatus.OPEN,
    credentialId = this[CredentialRequests.credentialId]?.let { CredentialId(it.toString()) },
    createdAt = this[CredentialRequests.createdAt],
)
