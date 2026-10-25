package net.robog.clarity.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.HeldAction

data class MainUiState(
    val items: List<FeedItem> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val held: List<HeldAction> = emptyList(),
    val sync: SyncState = SyncState.Connecting,
    val busy: Boolean = false,
) {
    /** Held items still waiting for the person (the number on the tab). */
    val heldWaiting: Int get() = held.count { it.status == HeldAction.Status.HELD }
}

/** Tab labels in the prototype: "today", "ask", "held · 2", "settings". */
fun heldTabLabel(waiting: Int): String = if (waiting > 0) "held · $waiting" else "held"

/** The cached feed for the placeholder tabs; keeps the cache in sync while the app is on screen. */
@Inject
@ViewModelKey(MainViewModel::class)
@ContributesIntoMap(AppScope::class)
class MainViewModel(private val feed: FeedRepository) : ViewModel() {
    val state: StateFlow<MainUiState> = combine(feed.items, feed.tasks, feed.held, feed.syncState, feed.agentBusy) { items, tasks, held, sync, busy ->
        MainUiState(items, tasks, held, sync, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    /** Suspends until cancelled; call it while the app is started. */
    suspend fun keepInSync() = feed.sync()
}
