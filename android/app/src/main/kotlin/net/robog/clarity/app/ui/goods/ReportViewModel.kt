package net.robog.clarity.app.ui.goods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.GoodsRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.report.ReportCitation

/** What the viewer's top bar asked for: open the download page, or share its link. */
enum class LinkAction { Download, Share }

/** A link the screen should hand to the browser ([LinkAction.Download]) or the share sheet. */
data class LinkRequest(val url: String, val action: LinkAction, val title: String)

data class ReportUiState(
    val loading: Boolean = true,
    /** Why the report could not be fetched (nothing else shows then). */
    val error: String? = null,
    val title: String = "",
    /** "Document · revision 2 · Today, 9:41". */
    val subtitle: String = "",
    val document: MdDocument? = null,
    /** Why the text could not be shown, while Download and Share still work. */
    val sourceError: String? = null,
    val sources: List<ReportCitation> = emptyList(),
    /** Whether the Good has a live download link (anyone with it can download). */
    val linkOn: Boolean = false,
    /** The action waiting for its link. */
    val busy: LinkAction? = null,
    /** A short line about the link: why it failed, or that it was turned off. */
    val linkNote: String? = null,
    val open: LinkRequest? = null,
)

/**
 * One Good, read in the app: its details and latest Markdown (fetched together). Download and Share
 * both use the Good's one secret download page (asked of the server, made on first use); the screen
 * hands [ReportUiState.open] to the browser or the share sheet and then calls [opened]. The link can
 * be turned off; the next Download or Share makes a new one.
 */
@Inject
@ViewModelKey(ReportViewModel::class)
@ContributesIntoMap(AppScope::class)
class ReportViewModel(
    private val repository: GoodsRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()
    private var id: String? = null

    /** Where the Markdown is parsed (off the main thread); tests run it on theirs. */
    internal var parseOn: CoroutineDispatcher = Dispatchers.Default

    /** Opens report [id]; again for the same one does nothing. */
    fun open(id: String) {
        if (this.id == id) return
        this.id = id
        _state.value = ReportUiState()
        viewModelScope.launch { load(id) }
    }

    fun retry() {
        val id = id ?: return
        _state.value = ReportUiState()
        viewModelScope.launch { load(id) }
    }

    /** Asks for the download page's link, then for the screen to open or share it. Ignored while one is on its way. */
    fun link(action: LinkAction) {
        val id = id ?: return
        val current = _state.value
        if (current.busy != null || current.title.isEmpty()) return
        _state.update { it.copy(busy = action, linkNote = null) }
        viewModelScope.launch {
            when (val r = repository.link(id)) {
                is ApiResult.Success -> _state.update { it.copy(busy = null, linkOn = true, open = LinkRequest(r.value, action, it.title)) }
                is ApiResult.Failure -> _state.update { it.copy(busy = null, linkNote = r.error.sentence()) }
            }
        }
    }

    /** Turns the download link off: whoever has it can no longer download. */
    fun turnOffLink() {
        val id = id ?: return
        viewModelScope.launch {
            when (val r = repository.turnOffLink(id)) {
                is ApiResult.Success -> _state.update { it.copy(linkOn = false, linkNote = "Link turned off. Sharing again makes a new one.") }
                is ApiResult.Failure -> _state.update { it.copy(linkNote = r.error.sentence()) }
            }
        }
    }

    /** The screen handed [ReportUiState.open] over; [ok] false when nothing on the phone could take it. */
    fun opened(ok: Boolean = true) = _state.update {
        it.copy(open = null, linkNote = if (ok) it.linkNote else "Nothing on this phone can open the link.")
    }

    private suspend fun load(id: String) {
        val (detail, source) = coroutineScope {
            val d = async { repository.detail(id) }
            val s = async { repository.source(id) }
            d.await() to s.await()
        }
        when (detail) {
            is ApiResult.Failure -> _state.update { it.copy(loading = false, error = detail.error.sentence()) }
            is ApiResult.Success -> {
                val value = detail.value
                val document = (source as? ApiResult.Success)?.value?.let { withContext(parseOn) { parseMarkdown(it) } }
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        title = document?.meta?.title ?: value.title,
                        subtitle = revisionLine(value, clock.now(), clock.zone()),
                        document = document,
                        sourceError = (source as? ApiResult.Failure)?.error?.sentence(),
                        sources = value.sources,
                        linkOn = value.link != null,
                    )
                }
            }
        }
    }
}
