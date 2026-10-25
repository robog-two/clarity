package net.robog.clarity.backend.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.security.interfaces.ECPublicKey
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.Devices
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.DeviceId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Registered devices and their public keys. Revoking a device = deleting its row. */
@Inject
@SingleIn(AppScope::class)
class DeviceRepository(private val database: Database, private val clock: Clock) {

    suspend fun count(): Long = database.tx { Devices.selectAll().count() }

    suspend fun register(name: String, publicKeyDer: ByteArray): DeviceId = database.tx {
        val id = Uuid.random()
        Devices.insert {
            it[Devices.id] = id
            it[Devices.name] = name
            it[publicKey] = publicKeyDer
            it[createdAt] = clock.now()
        }
        DeviceId(id.toString())
    }

    /** The key to verify tokens of [id] with, or null for unknown/revoked/invalid ids. */
    suspend fun publicKey(id: String): ECPublicKey? {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
        val der = database.tx {
            Devices.selectAll().where { Devices.id eq uuid }.singleOrNull()?.get(Devices.publicKey)
        } ?: return null
        return parseP256PublicKey(der).getOrNull()
    }

    suspend fun touch(id: DeviceId) {
        val uuid = Uuid.parse(id.value)
        database.tx { Devices.update({ Devices.id eq uuid }) { it[lastSeenAt] = clock.now() } }
    }
}
