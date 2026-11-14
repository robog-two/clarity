package net.robog.clarity.app.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SourcesRepository
import net.robog.clarity.app.net.SourceFile
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.source.SourceView

/** How one attachment stands: on its way, or why it failed (the row shows it and can try again). */
sealed interface FileState {
    data object Loading : FileState
    data class Failed(val message: String) : FileState
}

/** A downloaded attachment for the screen to hand to the phone (it opens or shares it, then calls [SourceViewModel.opened]). */
class FileRequest(val part: String, val file: SourceFile, val name: String)

data class SourceUiState(
    val loading: Boolean = true,
    /** Why the record could not be fetched (nothing else shows then). */
    val error: String? = null,
    val view: SourceView? = null,
    /** Attachments being fetched or that failed, by part. */
    val files: Map<String, FileState> = emptyMap(),
    val open: FileRequest? = null,
    /** A short message, shown once (that no app could open a file). */
    val note: String? = null,
)

/** Pure: what the person reads for a failed attachment; the cases the server names come first. */
fun fileError(error: ApiError): String = when {
    error is ApiError.NotFound -> "The mail server no longer has this file."
    error is ApiError.Http && error.status == 413 -> "This file is too big to open here."
    error is ApiError.Http && error.status == 504 -> "The mail server took too long. Try again."
    else -> error.sentence()
}

/** Pure: why a record could not be shown. */
fun sourceError(error: ApiError): String =
    if (error is ApiError.NotFound) "Can’t find this. It may have been removed." else error.sentence()

/**
 * One cited record, read-only. Loaded once for its id; a message's attachments are fetched one at a
 * time on tap and handed to the screen as [SourceUiState.open].
 */
@Inject
@ViewModelKey(SourceViewModel::class)
@ContributesIntoMap(AppScope::class)
class SourceViewModel(private val repository: SourcesRepository) : ViewModel() {
    private val _state = MutableStateFlow(SourceUiState())
    val state: StateFlow<SourceUiState> = _state.asStateFlow()
    private var id: String? = null

    /** Opens record [id]; again for the same one does nothing. */
    fun open(id: String) {
        if (this.id == id) return
        this.id = id
        load(id)
    }

    fun retry() {
        load(id ?: return)
    }

    private fun load(id: String) {
        _state.value = SourceUiState()
        viewModelScope.launch {
            when (val r = repository.view(id)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, view = r.value) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = sourceError(r.error)) }
            }
        }
    }

    /** Fetches attachment [part] of the open message. Ignored while that one is on its way. */
    fun download(part: String) {
        val id = id ?: return
        val message = _state.value.view as? SourceView.Message ?: return
        val attachment = message.attachments.firstOrNull { it.part == part } ?: return
        if (_state.value.files[part] == FileState.Loading) return
        _state.update { it.copy(files = it.files + (part to FileState.Loading)) }
        viewModelScope.launch {
            when (val r = repository.attachment(id, part)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        files = it.files - part,
                        open = FileRequest(part, r.value, safeFileName(attachment.filename ?: r.value.filename, part)),
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(files = it.files + (part to FileState.Failed(fileError(r.error)))) }
            }
        }
    }

    /** The screen handed [SourceUiState.open] over; [ok] false when nothing on the phone could take it. */
    fun opened(ok: Boolean = true) = _state.update { it.copy(open = null, note = if (ok) it.note else "No app can open this file") }

    fun noteShown() = _state.update { it.copy(note = null) }
}
