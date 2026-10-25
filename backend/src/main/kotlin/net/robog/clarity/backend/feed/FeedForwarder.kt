package net.robog.clarity.backend.feed

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.shared.feed.FeedEvent

/** Pure: the feed event a governance event becomes, if the app needs to hear about it. */
fun feedEventFor(event: GovernanceEvent): FeedEvent? = when (event) {
    is GovernanceEvent.HeldChanged -> FeedEvent.HeldChanged(event.held.id)
    is GovernanceEvent.ConstitutionChanged -> FeedEvent.ConstitutionChanged(event.version)
    is GovernanceEvent.Activity -> null
}

/** Forwards governance changes to SSE subscribers. */
@Inject
@ContributesIntoSet(AppScope::class)
class FeedForwarder(private val governance: GovernanceEvents, private val hub: FeedHub) : BackgroundService {
    override val name = "feed-forwarder"

    override suspend fun run() {
        governance.events.collect { event -> feedEventFor(event)?.let(hub::publish) }
    }
}
