package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Instant
import net.robog.clarity.backend.persistence.JobRuns
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

@Inject
@ContributesBinding(AppScope::class)
class ExposedJobRunStore(private val database: Database) : JobRunStore {
    override suspend fun lastRun(name: String): Instant? = database.tx {
        JobRuns.selectAll().where { JobRuns.name eq name }.singleOrNull()?.get(JobRuns.lastRunAt)
    }

    override suspend fun markRun(name: String, at: Instant) {
        database.tx {
            JobRuns.upsert(JobRuns.name) {
                it[JobRuns.name] = name
                it[lastRunAt] = at
            }
        }
    }
}
