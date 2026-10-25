package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.RunJournal
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The harness' [RunJournal] in Postgres (`agent_journal`): append-only, ordered by a per-run
 * sequence. One writer per run, so `max(seq) + 1` is safe; the primary key backs that up.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PgRunJournal(private val database: Database, private val clock: Clock) : RunJournal {
    override suspend fun append(runId: String, message: Message) {
        database.tx {
            val next = (AgentJournal.select(AgentJournal.seq.max()).where { AgentJournal.runId eq runId }
                .single()[AgentJournal.seq.max()] ?: 0) + 1
            AgentJournal.insert {
                it[AgentJournal.runId] = runId
                it[seq] = next
                it[AgentJournal.message] = message
                it[createdAt] = clock.now()
            }
        }
    }

    override suspend fun load(runId: String): List<Message> = database.tx {
        AgentJournal.selectAll().where { AgentJournal.runId eq runId }.orderBy(AgentJournal.seq, SortOrder.ASC)
            .map { it[AgentJournal.message] }
    }
}
