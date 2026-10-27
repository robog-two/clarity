package net.robog.clarity.app.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.data.feed.FeedStore
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.shared.session.SessionId

/** The person's own push choices, kept on this phone. */
interface PushPrefs {
    /** Keep the connection open for pushes. On unless the person turned it off. */
    val enabled: Flow<Boolean>
    suspend fun setEnabled(on: Boolean)

    /** The battery explanation was already shown once (at the end of onboarding or in Settings). */
    val batteryAsked: Flow<Boolean>
    suspend fun markBatteryAsked()
}

class DataStorePushPrefs(private val store: DataStore<Preferences>) : PushPrefs {
    override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] != false }
    override suspend fun setEnabled(on: Boolean) { store.edit { it[ENABLED] = on } }

    override val batteryAsked: Flow<Boolean> = store.data.map { it[BATTERY_ASKED] == true }
    override suspend fun markBatteryAsked() { store.edit { it[BATTERY_ASKED] = true } }

    private companion object {
        val ENABLED = booleanPreferencesKey("push_enabled")
        val BATTERY_ASKED = booleanPreferencesKey("push_battery_asked")
    }
}

/** The three facts that decide whether the service runs, as one flow. */
fun pushConditions(sessions: SessionStore, flag: OnboardingFlagStore, prefs: PushPrefs): Flow<PushConditions> =
    combine(sessions.session, flag.done, prefs.enabled) { session, done, enabled ->
        PushConditions(hasServer = session != null, setUp = done, enabled = enabled)
    }.distinctUntilChanged()

/** Starts and stops the foreground service so that it matches [PushConditions]. */
interface PushController {
    val enabled: Flow<Boolean>
    val batteryAsked: Flow<Boolean>

    /** The person's switch: saved, then the service follows it. */
    suspend fun setEnabled(on: Boolean)

    suspend fun markBatteryAsked()

    /** Start or stop the service to match the current state (app open, boot, update, forget). */
    suspend fun reconcile()
}

/** The tracker, for screens that report where the person is. Null in previews and tests. */
val LocalFrontScreen = androidx.compose.runtime.staticCompositionLocalOf<FrontScreen?> { null }

/**
 * Tracks what is on screen so that a notification is not posted for something the person is looking at.
 * The main activity and the call (its own task) report separately; the call wins while it is in front.
 */
class FrontScreen {
    private data class State(
        val started: Boolean = false,
        val place: Place = Place.Other,
        val callShown: Boolean = false,
        val call: SessionId? = null,
    )

    private val state = MutableStateFlow(State())
    private val _front = MutableStateFlow<Front>(Front.Background)
    val front: StateFlow<Front> = _front.asStateFlow()

    /** The main activity is started (on screen) or stopped. */
    fun appVisible(visible: Boolean) = set { it.copy(started = visible) }

    /** Which tab or screen is showing in the main activity. */
    fun showing(place: Place) = set { it.copy(place = place) }

    fun showingTab(tab: MainTab) = showing(Place.Tab(tab))

    /** The call screen is in front ([shown]) with the call [sessionId], or left the front. */
    fun callShowing(shown: Boolean, sessionId: SessionId?) = set { it.copy(callShown = shown, call = sessionId) }

    private fun set(change: (State) -> State) {
        val s = state.updateAndGet(change)
        _front.value = when {
            s.callShown -> Front.Showing(Place.Call(s.call))
            s.started -> Front.Showing(s.place)
            else -> Front.Background
        }
    }
}

/**
 * Claims in the ledger what the person has on screen ([seenArrivals]), so that a later catch-up
 * never notifies something already read. Without it, an item that reached the cache while the push
 * service was not running stayed unclaimed however long the person had looked at it.
 */
class SeenOnScreen(
    private val front: Flow<Front>,
    private val store: FeedStore,
    private val ledger: NotifiedLedger,
) {
    /** Runs until cancelled; run it while the app is in front. */
    suspend fun track() {
        combine(front, store.items, store.held) { f, items, held -> seenArrivals(f, items, held) }
            .distinctUntilChanged()
            .collect { if (it.isNotEmpty()) ledger.claimAll(it) }
    }
}
