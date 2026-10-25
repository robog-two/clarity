package net.robog.clarity.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState

/** One line of the connectors list: a connector on the server or one on this phone. */
sealed interface ConnectorRow {
    val key: String
    val title: String
    val proto: String

    data class Server(val summary: ConnectorSummary) : ConnectorRow {
        override val key: String get() = summary.id.value
        override val title: String get() = summary.manifest?.displayName ?: summary.id.value
        override val proto: String get() = "${summary.manifest?.kind ?: "connector"} · on your server"
    }

    data class Phone(val kind: PhoneConnectorKind) : ConnectorRow {
        override val key: String get() = kind.key
        override val title: String get() = kind.title
        override val proto: String get() = "on this phone"
    }
}

/** What the last test of a connector said. */
sealed interface TestState {
    data object Testing : TestState
    data class Ok(val summary: String?) : TestState
    data class Failed(val reason: String) : TestState
}

data class ConnectorItem(val row: ConnectorRow, val saved: Boolean, val test: TestState?) {
    /** The right-hand status text from the prototype. */
    val status: String
        get() = when {
            test is TestState.Testing -> "testing…"
            test is TestState.Ok -> test.summary ?: "synced"
            test is TestState.Failed -> "failed · fix →"
            saved -> "ready"
            else -> "set up →"
        }

    /** The caption under the name: the protocol, the failure reason, or "allowed" for phone items. */
    val caption: String
        get() = when {
            test is TestState.Failed -> test.reason
            saved && row is ConnectorRow.Phone -> "allowed · on this phone"
            else -> row.proto
        }
}

data class ConnectorsState(
    val loading: Boolean = true,
    val items: List<ConnectorItem> = emptyList(),
    val syncing: Boolean = false,
    /** The server has no connectors route yet. */
    val unavailable: Boolean = false,
    val error: ApiError? = null,
) {
    val anyFailed: Boolean get() = items.any { it.test is TestState.Failed }
    val allSynced: Boolean get() = items.isNotEmpty() && items.all { it.test is TestState.Ok }
    val allSaved: Boolean get() = items.isNotEmpty() && items.all { it.saved }
    val canSync: Boolean get() = allSaved && !syncing && !anyFailed

    /** "3 of 6 set up", "testing and syncing…", ... */
    val summaryLine: String
        get() = connectorsSummaryLine(
            total = items.size,
            saved = items.count { it.saved },
            synced = items.count { it.test is TestState.Ok },
            syncing = syncing,
            anyFailed = anyFailed,
        )
}

fun connectorsSummaryLine(total: Int, saved: Int, synced: Int, syncing: Boolean, anyFailed: Boolean): String = when {
    syncing -> "testing and syncing…"
    anyFailed -> "$synced of $total synced · tap the failed one to fix it"
    total > 0 && synced == total -> "all $total synced"
    else -> "$saved of $total set up"
}

/** Server connectors count as set up when every noun they produce is granted. */
fun isSetUp(summary: ConnectorSummary): Boolean {
    val produces = summary.manifest?.produces ?: return false
    return produces.isNotEmpty() && produces.all { summary.grants[it] == GrantState.GRANTED }
}

/** Step 1, connectors: the list, then "sync all" tests each one. */
@Inject
@ViewModelKey(ConnectorsStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ConnectorsStepViewModel(
    private val connectors: ConnectorsRepository,
    private val phone: PhonePermissions,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectorsState())
    val state: StateFlow<ConnectorsState> = _state.asStateFlow()

    private var summaries: Map<String, ConnectorSummary> = emptyMap()

    /** The row of a server connector (for the setup form). */
    fun summaryOf(key: String): ConnectorSummary? = summaries[key]

    /** Loads (or reloads, keeping test results) the list. */
    fun refresh(): Job =
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val tests = _state.value.items.associate { it.row.key to it.test }
            val list = connectors.list()
            val server: List<ConnectorSummary> = when (list) {
                is ApiResult.Success -> list.value
                is ApiResult.Failure -> {
                    val notFound = list.error is ApiError.NotFound
                    // Without a list the phone connectors can still be set up.
                    _state.update { it.copy(unavailable = notFound, error = if (notFound) null else list.error) }
                    emptyList()
                }
            }
            if (list is ApiResult.Success) _state.update { it.copy(unavailable = false, error = null) }
            summaries = server.associateBy { it.id.value }
            val rows = server.map { ConnectorRow.Server(it) } + PhoneConnectorKind.entries.map { ConnectorRow.Phone(it) }
            _state.update { s ->
                s.copy(
                    loading = false,
                    items = rows.map { row ->
                        ConnectorItem(row, saved = isSaved(row), test = tests[row.key])
                    },
                )
            }
        }

    private fun isSaved(row: ConnectorRow): Boolean = when (row) {
        is ConnectorRow.Server -> isSetUp(row.summary)
        is ConnectorRow.Phone -> phone.isGranted(row.kind)
    }

    /** The person came back from a connector's form. A failed one is tested again right away. */
    fun afterSetup(key: String, retest: Boolean) {
        viewModelScope.launch {
            refresh().join()
            if (retest) testOne(key)
        }
    }

    /** "sync all": test every connector in turn. */
    fun syncAll() {
        if (!_state.value.canSync) return
        viewModelScope.launch {
            _state.update { s -> s.copy(syncing = true, items = s.items.map { it.copy(test = null) }) }
            for (item in _state.value.items) testOne(item.row.key)
            _state.update { it.copy(syncing = false) }
        }
    }

    private suspend fun testOne(key: String) {
        val item = _state.value.items.firstOrNull { it.row.key == key } ?: return
        setTest(key, TestState.Testing)
        val result = when (val row = item.row) {
            // The phone connector itself is a later workstream; its permission is all there is to check.
            is ConnectorRow.Phone -> TestState.Ok(null)
            is ConnectorRow.Server -> when (val r = connectors.test(row.summary.id)) {
                is ApiResult.Success ->
                    if (r.value.ok) TestState.Ok(r.value.summary) else TestState.Failed(r.value.error ?: "sync failed")
                is ApiResult.Failure -> TestState.Failed(
                    when (r.error) {
                        is ApiError.NotFound -> "not available on this server yet"
                        is ApiError.Unreachable -> "could not reach the server"
                        else -> "the server could not test it"
                    },
                )
            }
        }
        setTest(key, result)
    }

    private fun setTest(key: String, test: TestState) =
        _state.update { s -> s.copy(items = s.items.map { if (it.row.key == key) it.copy(test = test) else it }) }
}
