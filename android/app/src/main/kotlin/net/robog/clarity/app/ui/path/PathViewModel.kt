package net.robog.clarity.app.ui.path

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
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
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.PATH_PAGE
import net.robog.clarity.app.data.PathRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.path.AgentPath

/** Whose path a screen shows: a task's (a thread of runs) or one helper run's. */
sealed interface PathTarget {
    data class Task(val id: TaskId) : PathTarget
    data class Run(val id: String) : PathTarget
}

data class PathUiState(
    val loading: Boolean = true,
    val path: AgentPath? = null,
    val rows: List<PathRow> = emptyList(),
    val error: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** The instance address the screenshots are fetched from. */
    val frameBase: String? = null,
)

/** How often a running path is fetched again while it is on screen. */
val PATH_POLL: Duration = 3.seconds

/**
 * The Agent's Path of a task or a helper run. Loads the first page, more on demand, and while
 * something on it is still running fetches it again every [PATH_POLL] (only while the screen is
 * started, see [poll]) and whenever the live feed says the task changed.
 */
@Inject
@ViewModelKey(PathViewModel::class)
@ContributesIntoMap(AppScope::class)
class PathViewModel(
    private val repository: PathRepository,
    private val feed: FeedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PathUiState())
    val state: StateFlow<PathUiState> = _state.asStateFlow()

    private var target: PathTarget? = null
    private val fetching = Mutex()

    /** Opens [target]; again for the same target does nothing. */
    fun open(target: PathTarget) {
        if (this.target == target) return
        this.target = target
        _state.value = PathUiState()
        viewModelScope.launch {
            _state.update { it.copy(frameBase = repository.base()) }
            fetch()
        }
        if (target is PathTarget.Task) {
            // The task changed on the server (status, a helper linked): fetch the path again.
            viewModelScope.launch {
                feed.tasks.map { tasks -> tasks.firstOrNull { it.id == target.id }?.let { it.status to it.updatedAt to it.runIds } }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { fetch() }
            }
        }
    }

    /** Fetches again every [PATH_POLL] while the path is live; returns when it is not. Run it while the screen is started. */
    suspend fun poll() {
        while (true) {
            delay(PATH_POLL)
            if (_state.value.path?.live != true) return
            fetch()
        }
    }

    /** Pull to refresh. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            fetch()
        }
    }

    /** The next page, appended. */
    fun loadMore() {
        val path = _state.value.path ?: return
        val next = path.next ?: return
        viewModelScope.launch {
            fetching.withLock {
                _state.update { it.copy(loadingMore = true) }
                when (val r = load(next, PATH_PAGE)) {
                    is ApiResult.Success -> publish(r.value.copy(items = path.items + r.value.items))
                    is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, error = r.error.sentence()) }
                }
            }
        }
    }

    /** Everything loaded so far, again from the start (a refresh keeps the pages already open). */
    private suspend fun fetch() = fetching.withLock {
        val loaded = _state.value.path?.items?.size ?: 0
        when (val r = load(0, maxOf(PATH_PAGE, loaded))) {
            is ApiResult.Success -> publish(r.value)
            is ApiResult.Failure -> _state.update { it.copy(loading = false, refreshing = false, error = if (it.path == null) r.error.sentence() else it.error) }
        }
    }

    private suspend fun load(from: Int, limit: Int): ApiResult<AgentPath> = when (val t = target) {
        is PathTarget.Task -> repository.task(t.id, from, limit)
        is PathTarget.Run -> repository.run(t.id, from, limit)
        null -> error("nothing opened")
    }

    private fun publish(path: AgentPath) = _state.update {
        it.copy(loading = false, refreshing = false, loadingMore = false, error = null, path = path, rows = pathRows(path.items))
    }
}
