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
import net.robog.clarity.backend.governance.HeldWaiters
import net.robog.clarity.backend.governance.isMcpCall
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction

private val log = KotlinLogging.logger {}

/** Statuses the person (or a failure) settled; creating or holding an action does not wake the lead. */
private val DECIDED = setOf(
    HeldAction.Status.APPROVED, HeldAction.Status.DECLINED, HeldAction.Status.KEPT,
    HeldAction.Status.RULE_UPDATED, HeldAction.Status.FAILED, HeldAction.Status.EXPIRED,
)

/**
 * Pure: the inbox item (and its dedup key) a governance event becomes, if the lead needs to know.
 * Most decisions do not need the lead: a helper waiting on the item ([waitingHelper]) carries on by
 * itself, an approved connector action reports through its result, a rule the person saved is in
 * the lead's prompt from its next turn, and a no only needs noting when the lead is idle anyway.
 */
fun inboxFor(event: GovernanceEvent, waitingHelper: (HeldActionId) -> String? = { null }): Pair<InboxItem, String>? = when (event) {
    is GovernanceEvent.HeldChanged -> {
        val h = event.held
        val browserOrVault = h.action is ProposedAction.ExternalTool || h.action is ProposedAction.UseCredential || h.action is ProposedAction.AskCode
        val priority = when {
            h.status !in DECIDED || waitingHelper(h.id) != null -> null
            // An approved MCP call is run by the backend, which reports its result itself (McpApprovals).
            h.status == HeldAction.Status.APPROVED && isMcpCall(h.action) -> null
            h.status == HeldAction.Status.APPROVED -> if (browserOrVault) Priority.NORMAL else null
            h.status in NO -> Priority.LOW
            else -> Priority.NORMAL
        }
        priority?.let { InboxItem.HeldDecided(h.id.value, h.status.wire(), h.action.summary, it) to "held:${h.id.value}:${h.status.wire()}" }
    }
    is GovernanceEvent.ConstitutionChanged -> null
    is GovernanceEvent.Activity -> null
}

/** The person said no, or kept the rule that caught it. */
private val NO = setOf(HeldAction.Status.DECLINED, HeldAction.Status.KEPT, HeldAction.Status.RULE_UPDATED)

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
    private val waiters: HeldWaiters,
) : BackgroundService {
    override val name = "inbox-forwarder"

    override suspend fun run() = coroutineScope {
        launch { governance.events.collect { e -> inboxFor(e, waiters::helperFor)?.let { (item, key) -> safely { inbox.enqueue(item, key) } } } }
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
