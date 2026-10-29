package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import net.robog.clarity.backend.agent.JobSlotClaims
import net.robog.clarity.backend.persistence.ownTx
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore

/**
 * Slot claims for jobs whose work commits outside the scheduler's transaction (a helper run is
 * started and saved on its own). The claim commits at once, in a transaction of its own, so when
 * the job's transaction later rolls back and the slot is retried, the retry finds it and starts
 * nothing. At most once per slot: a claim whose work then failed is not retried until the next slot.
 */
@Inject
@SingleIn(AppScope::class)
class JobSlots(private val database: Database, private val clock: Clock) {
    /** True when this call claimed [job]'s [slot]; false when it was claimed before. */
    suspend fun claim(job: String, slot: Instant): Boolean = database.ownTx {
        val now = clock.now()
        JobSlotClaims.deleteWhere { claimedAt less now - KEEP }
        JobSlotClaims.insertIgnore {
            it[JobSlotClaims.job] = job
            it[JobSlotClaims.slot] = slot
            it[claimedAt] = now
        }.insertedCount > 0
    }

    private companion object {
        val KEEP = 30.days
    }
}
