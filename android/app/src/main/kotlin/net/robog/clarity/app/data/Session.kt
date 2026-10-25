package net.robog.clarity.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val URL = stringPreferencesKey("instance_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
