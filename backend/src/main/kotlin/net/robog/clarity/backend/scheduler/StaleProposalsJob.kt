package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration.Companion.minutes
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.vault.VaultStore
import net.robog.clarity.shared.session.Settings

private val log = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Closes proposals nobody can act on any more: amendments ([ConstitutionStore.tidyProposals]) and
 * credential forms ([VaultStore.tidyRequests]). Saves close what they make redundant at once; this
 * catches the rest (a conversation that ended, a proposal that got old) and heals rows left from
 * before the rules existed. Idempotent: it only moves `proposed`/`open` rows to a closed status.
 */
@Inject
@SingleIn(AppScope::class)
class StaleProposals(private val constitutions: ConstitutionStore, private val vault: VaultStore) {
    suspend fun tidy(): Int = constitutions.tidyProposals() + vault.tidyRequests()
}

@Inject
@ContributesIntoSet(AppScope::class)
class StaleProposalsJob(private val stale: StaleProposals) : ScheduledJob {
    override val name = "stale-proposals"

    override fun schedule(settings: Settings) = Schedule.Every(10.minutes)

    override suspend fun fire(context: FireContext) {
        stale.tidy()
    }
}

/** The same pass once at boot, so rows from before a deploy are healed before the app asks. */
@Inject
@ContributesIntoSet(AppScope::class)
class StaleProposalsAtStartup(private val stale: StaleProposals) : StartupTask {
    override val name = "stale-proposals"

    override suspend fun run() {
        // Tidying is never worth failing a boot over; the scheduled job tries again.
        try {
            stale.tidy()
        } catch (e: Exception) {
            log.warn(e) { "could not close stale proposals at startup" }
        }
    }
}
