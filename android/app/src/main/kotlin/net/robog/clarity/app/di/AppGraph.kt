package net.robog.clarity.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.ktor.client.HttpClient
import net.robog.clarity.BuildConfig
import net.robog.clarity.app.AppConfig
import net.robog.clarity.app.connector.NoOpPhoneConnector
import net.robog.clarity.app.connector.PhoneConnector
import net.robog.clarity.app.data.DataStoreSessionStore
import net.robog.clarity.app.data.DefaultEnrollmentRepository
import net.robog.clarity.app.data.DefaultInstanceRepository
import net.robog.clarity.app.data.DefaultSessionRepository
import net.robog.clarity.app.data.EnrollmentRepository
import net.robog.clarity.app.data.InstanceRepository
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.identity.AndroidKeystoreDeviceKey
import net.robog.clarity.app.identity.DeviceKey
import net.robog.clarity.app.net.ClarityApi
import net.robog.clarity.app.net.DeviceJwtSigner
import net.robog.clarity.app.net.KtorClarityApi
import net.robog.clarity.app.net.createHttpClient

/**
 * The application graph. ViewModels are contributed with `@ContributesIntoMap(AppScope::class)`
 * and resolved through the multibound [ViewModelGraph.metroViewModelFactory].
 */
@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {

    @Provides fun appConfig(): AppConfig = AppConfig(allowCleartext = BuildConfig.DEBUG)

    @Provides @SingleIn(AppScope::class)
    fun dataStore(context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("clarity") }

    @Provides @SingleIn(AppScope::class)
    fun sessionStore(store: DataStore<Preferences>): SessionStore = DataStoreSessionStore(store)

    @Provides @SingleIn(AppScope::class)
    fun deviceKey(): DeviceKey = AndroidKeystoreDeviceKey()

    @Provides @SingleIn(AppScope::class)
    fun httpClient(key: DeviceKey, sessions: SessionStore): HttpClient =
        createHttpClient(DeviceJwtSigner(key), sessions)

    @Provides @SingleIn(AppScope::class)
    fun api(client: HttpClient, sessions: SessionStore): ClarityApi = KtorClarityApi(client, sessions)

    @Provides fun enrollmentRepository(api: ClarityApi, key: DeviceKey, sessions: SessionStore): EnrollmentRepository =
        DefaultEnrollmentRepository(api, key, sessions)

    @Provides fun sessionRepository(sessions: SessionStore, key: DeviceKey): SessionRepository =
        DefaultSessionRepository(sessions, key)

    @Provides fun instanceRepository(api: ClarityApi): InstanceRepository = DefaultInstanceRepository(api)

    @Provides fun phoneConnector(): PhoneConnector = NoOpPhoneConnector()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
