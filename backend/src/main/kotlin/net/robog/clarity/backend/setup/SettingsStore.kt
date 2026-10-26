package net.robog.clarity.backend.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.DefaultSettingsProvider
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.governance.PersonZone
import net.robog.clarity.backend.governance.UtcPersonZone
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.ClarityJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

// Mirrors db/migration/V6__sessions.sql.
object SettingsRows : Table("settings") {
    val id = integer("id")
    val doc = jsonb<Settings>("doc", ClarityJson)
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Pure: [settings] if they make sense, else what is wrong (shown to the person). */
fun validateSettings(settings: Settings): Either<String, Settings> {
    val call = settings.morningCall
    return when {
        runCatching { TimeZone.of(settings.timeZone) }.isFailure -> "unknown time zone '${settings.timeZone}' (use an IANA name like Europe/Berlin)".left()
        call.days.any { it !in 1..7 } -> "call days are ISO weekdays 1 (Monday) to 7 (Sunday)".left()
        call.enabled && call.days.isEmpty() -> "pick at least one day for the morning call".left()
        call.headsUpMinutes != null && call.headsUpMinutes !in 1..120 -> "the heads-up is 1 to 120 minutes before the call, or off".left()
        else -> settings.right()
    }
}

/** The person's settings (`settings`, one row); defaults until they first save. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DefaultSettingsProvider::class])
class SettingsStore(private val database: Database, private val clock: Clock) : SettingsProvider {
    override suspend fun current(): Settings = database.tx {
        SettingsRows.selectAll().where { SettingsRows.id eq ROW }.singleOrNull()?.get(SettingsRows.doc)
    } ?: Settings()

    suspend fun save(settings: Settings): Either<String, Settings> = validateSettings(settings).onRight { valid ->
        database.tx {
            SettingsRows.upsert(SettingsRows.id) {
                it[id] = ROW
                it[doc] = valid
                it[updatedAt] = clock.now()
            }
        }
    }

    private companion object { const val ROW = 1 }
}

/** The person's zone for protected-time checks follows their settings. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [UtcPersonZone::class])
class SettingsPersonZone(private val settings: SettingsStore) : PersonZone {
    override suspend fun zone(): TimeZone = net.robog.clarity.backend.governance.zoneOrUtc(settings.current().timeZone)
}
