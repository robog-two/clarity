package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.robog.clarity.backend.governance.ActionResultEvent
import net.robog.clarity.backend.governance.ActionResults
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.HeldAction

private val log = KotlinLogging.logger {}

/** Statuses the person (or a failure) settled; creating or holding an action does not wake the lead. */
private val DECIDED = setOf(
    HeldAction.Status.APPROVED, HeldAction.Status.DECLINED, HeldAction.Status.KEPT,
    HeldAction.Status.RULE_UPDATED, HeldAction.Status.FAILED, HeldAction.Status.EXPIRED,
)

/** Pure: the inbox item (and its dedup key) a governance event becomes, if the lead needs to know. */
fun inboxFor(event: GovernanceEvent): Pair<InboxItem, String>? = when (event) {
    is GovernanceEvent.HeldChanged ->
        if (event.held.status in DECIDED) {
            InboxItem.HeldDecided(event.held.id.value, event.held.status.wire(), event.held.action.summary) to
                "held:${event.held.id.value}:${event.held.status.wire()}"
        } else null
    is GovernanceEvent.ConstitutionChanged -> InboxItem.ConstitutionChanged(event.version) to "constitution:${event.version}"
    is GovernanceEvent.Activity -> null
}

/** Pure: a connector's final action result as an inbox item. */
fun inboxFor(event: ActionResultEvent): Pair<InboxItem, String> {
    val (done, detail) = when (val o = event.outcome) {
        is ActionOutcome.Done -> true to o.content
        is ActionOutcome.Failed -> false to o.message
    }
    return InboxItem.ActionResult(event.actionId.value, event.action.summary, done, detail) to "action:${event.actionId.value}"
}

/** Governance decisions and action results wake the lead through the inbox. */
@Inject
@ContributesIntoSet(AppScope::class)
class InboxForwarder(
    private val governance: GovernanceEvents,
    private val results: ActionResults,
    private val inbox: InboxStore,
) : BackgroundService {
    override val name = "inbox-forwarder"

    override suspend fun run() = coroutineScope {
        launch { governance.events.collect { e -> inboxFor(e)?.let { (item, key) -> safely { inbox.enqueue(item, key) } } } }
        launch { results.events.collect { e -> inboxFor(e).let { (item, key) -> safely { inbox.enqueue(item, key) } } } }
        Unit
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "could not enqueue inbox item" } // one bad event must not stop forwarding
        }
    }
}
