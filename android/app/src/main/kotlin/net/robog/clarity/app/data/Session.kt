package net.robog.clarity.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.robog.clarity.shared.DeviceId

/** The enrolled instance this app talks to. */
data class Session(val instanceUrl: String, val deviceId: DeviceId)

interface SessionStore {
    val session: Flow<Session?>
    suspend fun save(session: Session)
    suspend fun clear()
}

class DataStoreSessionStore(private val store: DataStore<Preferences>) : SessionStore {
    override val session: Flow<Session?> = store.data.map { prefs ->
        val url = prefs[URL]
        val id = prefs[DEVICE_ID]
        if (url != null && id != null) Session(url, DeviceId(id)) else null
    }

    override suspend fun save(session: Session) {
        store.edit {
            it[URL] = session.instanceUrl
            it[DEVICE_ID] = session.deviceId.value
        }
    }

    /**
     * Removes the session only. The file is shared with every other store, and wiping all of it
     * also lost state that is not the server's, such as the do-not-disturb setting to go back to;
     * forgetting the server clears each per-server store explicitly (see [ServerScopedState]).
     */
    override suspend fun clear() {
        store.edit {
            it.remove(URL)
            it.remove(DEVICE_ID)
        }
    }

    private companion object {
        val URL = stringPreferencesKey("instance_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}

/** Whether the person finished the onboarding flow on this device (set on its last screen). */
interface OnboardingFlagStore {
    val done: Flow<Boolean>
    suspend fun markDone()

    /** "redo onboarding" in settings: show the onboarding flow again although setup is complete. */
    val redo: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    suspend fun startRedo() = Unit

    /**
     * The server was forgotten: not set up any more, and the next pairing walks through setup
     * again, even with a server that reports its setup complete (which otherwise skips the flow).
     */
    suspend fun forgetServer() = Unit
}

class DataStoreOnboardingFlagStore(private val store: DataStore<Preferences>) : OnboardingFlagStore {
    override val done: Flow<Boolean> = store.data.map { it[DONE] == true }

    override val redo: Flow<Boolean> = store.data.map { it[REDO] == true }

    override suspend fun markDone() {
        store.edit {
            it[DONE] = true
            it[REDO] = false
        }
    }

    override suspend fun startRedo() {
        store.edit { it[REDO] = true }
    }

    override suspend fun forgetServer() {
        store.edit {
            it[DONE] = false
            it[REDO] = true
        }
    }

    private companion object {
        val DONE = booleanPreferencesKey("onboarding_done")
        val REDO = booleanPreferencesKey("onboarding_redo")
    }
}
