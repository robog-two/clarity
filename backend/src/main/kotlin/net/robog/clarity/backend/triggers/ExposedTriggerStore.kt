package net.robog.clarity.backend.triggers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.AgentTriggers
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wireName
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.TriggerSpec
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** [TriggerStore] backed by `agent_trigger`; the full [TriggerSpec] is kept as jsonb. */
@Inject
@ContributesBinding(AppScope::class)
class ExposedTriggerStore(private val database: Database, private val clock: Clock) : TriggerStore {

    override suspend fun list(): List<StoredTrigger> = database.tx {
        AgentTriggers.selectAll().orderBy(AgentTriggers.createdAt, SortOrder.ASC).map { it.toStored() }
    }

    override suspend fun enabledFor(noun: Noun): List<StoredTrigger> = database.tx {
        AgentTriggers.selectAll()
            .where { (AgentTriggers.noun eq noun.wireName) and (AgentTriggers.enabled eq true) }
            .map { it.toStored() }
    }

    override suspend fun create(spec: TriggerSpec): StoredTrigger = database.tx {
        val id = Uuid.random()
        AgentTriggers.insert {
            it[AgentTriggers.id] = id
            it[name] = spec.name
            it[noun] = spec.noun.wireName
            it[AgentTriggers.spec] = spec
            it[enabled] = spec.enabled
            it[createdAt] = clock.now()
        }
        StoredTrigger(id.toString(), spec)
    }

    override suspend fun delete(id: String): Boolean {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return false
        return database.tx { AgentTriggers.deleteWhere { AgentTriggers.id eq uuid } > 0 }
    }

    private fun ResultRow.toStored() = StoredTrigger(this[AgentTriggers.id].toString(), this[AgentTriggers.spec])
}
