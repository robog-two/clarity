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
import net.robog.clarity.app.data.GoodsFile
import net.robog.clarity.app.data.GoodsRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.report.ReportCitation

/** A download in progress, or the reason the last one failed. */
sealed interface DownloadState {
    data object Busy : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** A downloaded file the screen should hand to another app: to view it, or to share it. */
data class OpenRequest(val file: GoodsFile, val share: Boolean, val title: String)

data class ReportUiState(
    val loading: Boolean = true,
    /** Why the report could not be fetched (nothing else shows then). */
    val error: String? = null,
    val title: String = "",
    /** "Document · revision 2 · Today, 9:41". */
    val subtitle: String = "",
    val document: MdDocument? = null,
    /** Why the text could not be shown, while the downloads still work. */
    val sourceError: String? = null,
    val formats: List<GoodsFormat> = emptyList(),
    val sources: List<ReportCitation> = emptyList(),
    /** Per format wire name. */
    val downloads: Map<String, DownloadState> = emptyMap(),
    val open: OpenRequest? = null,
)

/**
 * One Good, read in the app: its details and latest Markdown (fetched together), and downloads in
 * each format through the authenticated client. A finished download becomes [ReportUiState.open]
 * for the screen to hand to a viewer or the share sheet; the screen then calls [opened].
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

    /** Downloads [format] (a wire name), then asks the screen to open or [share] it. Ignored while that format is downloading. */
    fun download(format: String, share: Boolean = false) {
        val id = id ?: return
        val current = _state.value
        if (current.downloads[format] == DownloadState.Busy || current.title.isEmpty()) return
        _state.update { it.copy(downloads = it.downloads + (format to DownloadState.Busy)) }
        viewModelScope.launch {
            when (val r = repository.download(id, format, current.title)) {
                is ApiResult.Success -> _state.update {
                    it.copy(downloads = it.downloads - format, open = OpenRequest(r.value, share, it.title))
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(downloads = it.downloads + (format to DownloadState.Failed(r.error.sentence())))
                }
            }
        }
    }

    /** The screen handed [ReportUiState.open] over (or could not). */
    fun opened() = _state.update { it.copy(open = null) }

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
                // A long document parses off the main thread.
                val document = (source as? ApiResult.Success)?.value?.let { withContext(parseOn) { parseMarkdown(it) } }
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        title = document?.meta?.title ?: value.title,
                        subtitle = revisionLine(value, clock.now(), clock.zone()),
                        document = document,
                        sourceError = (source as? ApiResult.Failure)?.error?.sentence(),
                        formats = detailFormats(value),
                        sources = value.sources,
                    )
                }
            }
        }
    }
}
