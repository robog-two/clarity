package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.LedgerEntry
import net.robog.clarity.harness.ToolLedger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Mirrors db/migration/V35__runaway_guards.sql. */
object ToolCallLedger : Table("tool_call_ledger") {
    val runId = text("run_id")
    val toolUseId = text("tool_use_id")
    val toolName = text("tool_name")
    val content = text("content")
    val isError = bool("is_error")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(runId, toolUseId)
}

/**
 * The harness' [ToolLedger] in Postgres (`tool_call_ledger`). The first result kept for a call
 * stays. Entries older than a week are dropped now and then: a resume comes within minutes.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PgToolLedger(private val database: Database, private val clock: Clock) : ToolLedger {
    private val lastPrune = AtomicLong(0)

    override suspend fun find(runId: String, toolUseId: String): LedgerEntry? = database.tx {
        ToolCallLedger.selectAll().where { (ToolCallLedger.runId eq runId) and (ToolCallLedger.toolUseId eq toolUseId) }
            .singleOrNull()?.let { LedgerEntry(it[ToolCallLedger.toolName], it[ToolCallLedger.content], it[ToolCallLedger.isError]) }
    }

    override suspend fun record(runId: String, toolUseId: String, entry: LedgerEntry) {
        val now = clock.now()
        database.tx {
            ToolCallLedger.insertIgnore {
                it[ToolCallLedger.runId] = runId
                it[ToolCallLedger.toolUseId] = toolUseId
                it[toolName] = entry.toolName
                it[content] = entry.content
                it[isError] = entry.isError
                it[createdAt] = now
            }
            val previous = lastPrune.get()
            if (now.toEpochMilliseconds() - previous > 1.hours.inWholeMilliseconds && lastPrune.compareAndSet(previous, now.toEpochMilliseconds())) {
                ToolCallLedger.deleteWhere { createdAt less (now - 7.days) }
            }
        }
    }
}
