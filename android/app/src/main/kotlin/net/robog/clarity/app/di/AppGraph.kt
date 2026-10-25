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
import androidx.room.Room
import net.robog.clarity.app.connector.AndroidPhonePermissions
import net.robog.clarity.app.connector.ConfigSealer
import net.robog.clarity.app.connector.NoOpPhoneConnector
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.connector.TinkConfigSealer
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.ConstitutionRepository
import net.robog.clarity.app.data.DataStoreOnboardingFlagStore
import net.robog.clarity.app.data.DefaultConnectorsRepository
import net.robog.clarity.app.data.DefaultConstitutionRepository
import net.robog.clarity.app.data.DefaultFeedRepository
import net.robog.clarity.app.data.DefaultServerRepository
import net.robog.clarity.app.data.DefaultSessionsRepository
import net.robog.clarity.app.data.DefaultSettingsRepository
import net.robog.clarity.app.data.DefaultSetupRepository
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.IdGenerator
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.ServerRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.app.data.SetupRepository
import net.robog.clarity.app.data.SystemAppClock
import net.robog.clarity.app.data.UuidGenerator
import net.robog.clarity.app.data.feed.ClarityDatabase
import net.robog.clarity.app.data.feed.FeedStore
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.data.feed.RoomFeedStore
import net.robog.clarity.app.net.FeedEventSource
import net.robog.clarity.app.net.KtorFeedEventSource
import net.robog.clarity.app.voice.AndroidSpeechInput
import net.robog.clarity.app.voice.SpeechInput
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

    @Provides fun sessionRepository(sessions: SessionStore, key: DeviceKey, cache: FeedStore): SessionRepository =
        DefaultSessionRepository(sessions, key, cache)

    @Provides @SingleIn(AppScope::class)
    fun database(context: Context): ClarityDatabase =
        Room.databaseBuilder(context, ClarityDatabase::class.java, "clarity.db")
            // The cache is rebuilt from the server, so a schema change just starts it over.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides @SingleIn(AppScope::class)
    fun feedStore(db: ClarityDatabase): FeedStore = RoomFeedStore(db.feedDao())

    @Provides @SingleIn(AppScope::class)
    fun onboardingFlag(store: DataStore<Preferences>): OnboardingFlagStore = DataStoreOnboardingFlagStore(store)

    @Provides fun ids(): IdGenerator = UuidGenerator()

    @Provides fun clock(): AppClock = SystemAppClock()

    @Provides fun feedEvents(client: HttpClient, sessions: SessionStore): FeedEventSource =
        KtorFeedEventSource(client, sessions)

    @Provides @SingleIn(AppScope::class)
    fun feedSync(api: ClarityApi, source: FeedEventSource, store: FeedStore): FeedSync = FeedSync(api, source, store)

    @Provides fun feedRepository(api: ClarityApi, store: FeedStore, sync: FeedSync, ids: IdGenerator): FeedRepository =
        DefaultFeedRepository(api, store, sync, ids)

    @Provides fun serverRepository(api: ClarityApi): ServerRepository = DefaultServerRepository(api)
    @Provides fun setupRepository(api: ClarityApi): SetupRepository = DefaultSetupRepository(api)
    @Provides fun settingsRepository(api: ClarityApi): SettingsRepository = DefaultSettingsRepository(api)
    @Provides fun constitutionRepository(api: ClarityApi): ConstitutionRepository = DefaultConstitutionRepository(api)
    @Provides fun sessionsRepository(api: ClarityApi): SessionsRepository = DefaultSessionsRepository(api)

    @Provides fun configSealer(): ConfigSealer = TinkConfigSealer()

    @Provides fun connectorsRepository(api: ClarityApi, sealer: ConfigSealer): ConnectorsRepository =
        DefaultConnectorsRepository(api, sealer)

    @Provides fun phonePermissions(context: Context): PhonePermissions = AndroidPhonePermissions(context)

    @Provides @SingleIn(AppScope::class)
    fun speechInput(context: Context): SpeechInput = AndroidSpeechInput(context)

    @Provides fun instanceRepository(api: ClarityApi): InstanceRepository = DefaultInstanceRepository(api)

    @Provides fun phoneConnector(): PhoneConnector = NoOpPhoneConnector()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
