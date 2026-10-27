package net.robog.clarity.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.connector.PhoneConnector
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.push.ConnectionHolder
import net.robog.clarity.app.push.PushController
import net.robog.clarity.app.push.SeenOnScreen
import net.robog.clarity.app.ui.Toasts
import net.robog.clarity.app.ui.held.heldOutcomeToasts
import net.robog.clarity.shared.governance.HeldAction

/** What the tab bar needs: the number on "held · N" and whether the connection is up. */
data class MainUiState(
    val held: List<HeldAction> = emptyList(),
    val sync: SyncState = SyncState.Connecting,
    val busy: Boolean = false,
) {
    /** Held items still waiting for the person (the number on the tab). */
    val heldWaiting: Int get() = held.count { it.status == HeldAction.Status.HELD }
}

/** Tab labels in the prototype: "today", "ask", "held · 2", "settings". */
fun heldTabLabel(waiting: Int): String = if (waiting > 0) "held · $waiting" else "held"

/**
 * The signed-in shell: the held badge, the confirmation toasts when held actions run, and the
 * background work that belongs to being in front (feed sync, call alarms, the phone connector).
 */
@Inject
@ViewModelKey(MainViewModel::class)
@ContributesIntoMap(AppScope::class)
class MainViewModel(
    private val feed: FeedRepository,
    private val toasts: Toasts,
    private val scheduler: CallScheduler,
    private val phone: PhoneConnector,
    private val connection: ConnectionHolder,
    private val push: PushController,
    private val seen: SeenOnScreen,
) : ViewModel() {
    /** Confirmations to show above the tab bar. */
    val messages = toasts.messages

    val state: StateFlow<MainUiState> = combine(feed.held, feed.syncState, feed.agentBusy) { held, sync, busy ->
        MainUiState(held, sync, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        // "Sent to Dana." when an approved action has run.
        viewModelScope.launch {
            var previous: List<HeldAction>? = null
            feed.held.collect { next ->
                previous?.let { prev -> heldOutcomeToasts(prev, next).forEach(toasts::show) }
                previous = next
            }
        }
    }

    /**
     * Suspends until cancelled; call it while the app is started. The feed connection and the action
     * loop are shared with the push service: whichever holds them first opens them, and they close
     * when nobody needs them. With push off this is what keeps the app current while it is open.
     */
    suspend fun keepInSync() = coroutineScope {
        launch { connection.holdFeed() }
        launch {
            scheduler.refresh()
            phone.schedule()
            phone.syncOnce()
            connection.holdActions()
        }
        // In front is the one moment the system lets a foreground service start.
        launch { push.reconcile() }
        // What the person reads here must not notify later.
        launch { seen.track() }
    }
}
