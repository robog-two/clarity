package net.robog.clarity.backend.governance

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction

/** Things the feed forwards to the app (`FeedEvent.HeldChanged`, ...). */
sealed interface GovernanceEvent {
    /** A held action was created or changed status. */
    data class HeldChanged(val held: HeldAction) : GovernanceEvent

    /** The constitution moved to a new [version]. */
    data class ConstitutionChanged(val version: Int) : GovernanceEvent

    /** The agent did something alone. */
    data class Activity(val entry: ActivityEntry) : GovernanceEvent
}

/** Hot, bounded, in-process fan-out for [GovernanceEvent]s. Publishing never suspends. */
@Inject
@SingleIn(AppScope::class)
class GovernanceEvents {
    private val flow = MutableSharedFlow<GovernanceEvent>(extraBufferCapacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<GovernanceEvent> = flow.asSharedFlow()

    fun publish(event: GovernanceEvent) {
        flow.tryEmit(event)
    }

    private companion object { const val CAPACITY = 256 }
}

/** The final result of an action a connector executed (retries are not reported until they end). */
data class ActionResultEvent(
    val actionId: ActionId,
    val connectorId: ConnectorId,
    val heldId: HeldActionId?,
    val action: ProposedAction,
    val outcome: ActionOutcome,
)

/** Results for the lead to consume (inbox items: "sent", "couldn't send because ..."). */
@Inject
@SingleIn(AppScope::class)
class ActionResults {
    private val flow = MutableSharedFlow<ActionResultEvent>(extraBufferCapacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ActionResultEvent> = flow.asSharedFlow()

    fun publish(event: ActionResultEvent) {
        flow.tryEmit(event)
    }

    private companion object { const val CAPACITY = 256 }
}
