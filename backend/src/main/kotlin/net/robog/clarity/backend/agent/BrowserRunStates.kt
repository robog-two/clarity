package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.jsonb

/** `browser_run_state` (V42): one helper run's browser state, as [BrowserRunSnapshot] json. */
object BrowserRunStateTable : Table("browser_run_state") {
    val runId = text("run_id")
    val state = jsonb<BrowserRunSnapshot>("state", ClarityJson)
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(runId)
}

/** A started sign-in control: the page's flow key and the control's lower-case label. */
@Serializable
data class SignInStarter(val flow: String, val label: String)

/**
 * What a run's browser caps and sign-in guards need to survive a restart: the actions it took (its
 * cap), its audit sequence, sign-in progress per site (codes sent), the controls that started a
 * sign-in, the cookie names seen before signing in, and the code placeholders already typed.
 */
@Serializable
data class BrowserRunSnapshot(
    val actions: Int = 0,
    val seq: Int = 0,
    val hadLease: Boolean = false,
    val shownResume: Boolean = false,
    val taskKey: String? = null,
    val signIn: Map<String, SignInState> = emptyMap(),
    val starters: List<SignInStarter> = emptyList(),
    val cookies: Map<String, String> = emptyMap(),
    val usedCodes: List<String> = emptyList(),
)

/** Where [BrowserPilot] keeps each run's [BrowserRunSnapshot]. A seam: Postgres in production, memory in tests. */
interface BrowserRunStates {
    suspend fun load(runId: String): BrowserRunSnapshot?

    suspend fun save(runId: String, snapshot: BrowserRunSnapshot)

    /** Drops the states not saved since [before]; returns how many. */
    suspend fun dropOlderThan(before: Instant): Int
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PgBrowserRunStates(private val database: Database, private val clock: Clock) : BrowserRunStates {
    override suspend fun load(runId: String): BrowserRunSnapshot? = database.tx {
        BrowserRunStateTable.selectAll().where { BrowserRunStateTable.runId eq runId }.singleOrNull()?.get(BrowserRunStateTable.state)
    }

    override suspend fun save(runId: String, snapshot: BrowserRunSnapshot) {
        val now = clock.now()
        database.tx {
            BrowserRunStateTable.upsert {
                it[BrowserRunStateTable.runId] = runId
                it[state] = snapshot
                it[updatedAt] = now
            }
        }
    }

    override suspend fun dropOlderThan(before: Instant): Int = database.tx { BrowserRunStateTable.deleteWhere { updatedAt less before } }
}

class MemoryBrowserRunStates : BrowserRunStates {
    private val states = ConcurrentHashMap<String, BrowserRunSnapshot>()
    override suspend fun load(runId: String): BrowserRunSnapshot? = states[runId]
    override suspend fun save(runId: String, snapshot: BrowserRunSnapshot) { states[runId] = snapshot }
    override suspend fun dropOlderThan(before: Instant): Int = 0
}
