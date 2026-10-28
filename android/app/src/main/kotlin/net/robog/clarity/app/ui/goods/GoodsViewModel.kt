package net.robog.clarity.app.ui.goods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.GoodsRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.feed.Card

data class GoodsUiState(
    val loading: Boolean = true,
    val rows: List<GoodsRow> = emptyList(),
    /** Why the list could not be fetched; shown over whatever was loaded before. */
    val error: String? = null,
    val refreshing: Boolean = false,
)

/**
 * The Goods tab: every report the agent made, the most recently changed first. Fetched when the tab
 * opens, on pull to refresh, and again whenever a new report card arrives in the feed.
 */
@Inject
@ViewModelKey(GoodsViewModel::class)
@ContributesIntoMap(AppScope::class)
class GoodsViewModel(
    private val repository: GoodsRepository,
    private val feed: FeedRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(GoodsUiState())
    val state: StateFlow<GoodsUiState> = _state.asStateFlow()
    private val fetching = Mutex()

    init {
        viewModelScope.launch { fetch() }
        viewModelScope.launch {
            feed.items.map { items -> items.sumOf { item -> item.cards.count { it is Card.Report } } }
                .distinctUntilChanged()
                .drop(1)
                .collect { fetch() }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch { fetch() }
    }

    private suspend fun fetch() = fetching.withLock {
        when (val r = repository.list()) {
            is ApiResult.Success -> _state.value = GoodsUiState(loading = false, rows = goodsRows(r.value, clock.now(), clock.zone()))
            is ApiResult.Failure -> _state.update { it.copy(loading = false, refreshing = false, error = r.error.sentence()) }
        }
    }
}
