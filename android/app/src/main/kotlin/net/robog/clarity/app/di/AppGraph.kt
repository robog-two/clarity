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
import net.robog.clarity.app.call.AndroidCallAlarms
import net.robog.clarity.app.call.AndroidCallNotifier
import net.robog.clarity.app.call.CallAlarmHandler
import net.robog.clarity.app.call.CallAlarms
import net.robog.clarity.app.call.CallNotifier
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.call.CallService
import net.robog.clarity.app.call.CallTracker
import net.robog.clarity.app.call.OngoingCallStarter
import net.robog.clarity.app.call.CallSettingsCache
import net.robog.clarity.app.call.DataStoreCallSettingsCache
import net.robog.clarity.app.connector.AndroidDndAlarms
import net.robog.clarity.app.connector.AndroidDndController
import net.robog.clarity.app.connector.AndroidSmsSource
import net.robog.clarity.app.connector.ConnectorWork
import net.robog.clarity.app.connector.DataStoreDndStateStore
import net.robog.clarity.app.connector.DataStoreSmsCursorStore
import net.robog.clarity.app.connector.DefaultPhoneConnector
import net.robog.clarity.app.connector.DndActionHandler
import net.robog.clarity.app.connector.SmsCursorStore
import net.robog.clarity.app.connector.SmsSource
import net.robog.clarity.app.connector.WorkManagerConnectorWork
import net.robog.clarity.app.data.AmendmentsRepository
import net.robog.clarity.app.data.DefaultAmendmentsRepository
import net.robog.clarity.app.data.DefaultHeldRepository
import net.robog.clarity.app.data.DefaultTodayRepository
import net.robog.clarity.app.data.HeldRepository
import net.robog.clarity.app.data.TodayRepository
import net.robog.clarity.app.device.AndroidDeviceCapabilities
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.net.IngestApi
import net.robog.clarity.app.net.VaultApi
import net.robog.clarity.app.data.DefaultModelsRepository
import net.robog.clarity.app.data.ModelsRepository
import net.robog.clarity.app.vault.AndroidVaultKeys
import net.robog.clarity.app.vault.BiometricPhoneAuth
import net.robog.clarity.app.vault.DefaultVaultRepository
import net.robog.clarity.app.vault.ForegroundActivity
import net.robog.clarity.app.vault.PhoneApprover
import net.robog.clarity.app.vault.PhoneAuth
import net.robog.clarity.app.vault.VaultKeys
import net.robog.clarity.app.vault.VaultRepository
import net.robog.clarity.app.ui.Toasts
import net.robog.clarity.app.ui.held.ApprovalGate
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.robog.clarity.app.push.AndroidPushController
import net.robog.clarity.app.push.AndroidPushNotifier
import net.robog.clarity.app.push.ConnectionHolder
import net.robog.clarity.app.push.DataStorePushPrefs
import net.robog.clarity.app.push.FrontScreen
import net.robog.clarity.app.push.PushController
import net.robog.clarity.app.push.PushListener
import net.robog.clarity.app.push.PushNotifier
import net.robog.clarity.app.push.PushPrefs
import net.robog.clarity.app.push.DataStoreNotifiedLedger
import net.robog.clarity.app.push.FeedService
import net.robog.clarity.app.push.NotifiedLedger
import net.robog.clarity.app.push.PushFallback
import net.robog.clarity.app.push.PushFallbackWork
import net.robog.clarity.app.push.WorkManagerPushFallbackWork
import net.robog.clarity.app.push.canStartServiceNow
import net.robog.clarity.app.push.pushConditions
import net.robog.clarity.app.push.SeenOnScreen
import net.robog.clarity.app.push.notifiable
import net.robog.clarity.app.data.perServerState
import net.robog.clarity.app.data.feed.SyncState
import kotlinx.coroutines.flow.first

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
    fun ktorApi(client: HttpClient, sessions: SessionStore): KtorClarityApi = KtorClarityApi(client, sessions)

    @Provides fun api(api: KtorClarityApi): ClarityApi = api

    @Provides fun ingestApi(api: KtorClarityApi): IngestApi = api

    @Provides fun enrollmentRepository(api: ClarityApi, key: DeviceKey, sessions: SessionStore): EnrollmentRepository =
        DefaultEnrollmentRepository(api, key, sessions)

    @Provides fun sessionRepository(
        sessions: SessionStore,
        key: DeviceKey,
        cache: FeedStore,
        sync: FeedSync,
        pushWork: PushFallbackWork,
        connectorWork: ConnectorWork,
        phone: PhoneConnector,
        callScheduler: CallScheduler,
        dnd: DndActionHandler,
        notifier: PushNotifier,
        flag: OnboardingFlagStore,
        ledger: NotifiedLedger,
        smsCursor: SmsCursorStore,
        vaultKeys: VaultKeys,
    ): SessionRepository = DefaultSessionRepository(
        sessions, key, cache,
        perServerState(sync, pushWork, connectorWork, phone, callScheduler, dnd, notifier, flag, ledger, smsCursor, vaultKeys),
    )

    @Provides @SingleIn(AppScope::class)
    fun database(context: Context): ClarityDatabase =
        // A copy of the server's feed, tasks and held items: it lives in the cache folder, so "Clear
        // cache" resyncs it from the server while sign-in and keys (DataStore, Keystore) stay.
        Room.databaseBuilder(context, ClarityDatabase::class.java, context.cacheDir.resolve("clarity.db").path)
            // The cache is rebuilt from the server, so a schema change just starts it over.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                // Runs once, on Room's executor: drop the copy older versions kept in the data folder.
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    context.deleteDatabase("clarity.db")
                }
            })
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

    @Provides fun todayRepository(api: ClarityApi): TodayRepository = DefaultTodayRepository(api)
    @Provides fun pathRepository(api: KtorClarityApi): net.robog.clarity.app.data.PathRepository = net.robog.clarity.app.data.DefaultPathRepository(api)

    @Provides fun goodsRepository(api: KtorClarityApi): net.robog.clarity.app.data.GoodsRepository =
        net.robog.clarity.app.data.DefaultGoodsRepository(api)

    /**
     * Loads the Agent's Path screenshots over the app's own client (so each request carries the
     * device's signed token), decoding off the main thread, with memory and disk caches.
     */
    @Provides @SingleIn(AppScope::class)
    fun imageLoader(context: Context, client: HttpClient): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.ktor3.KtorNetworkFetcherFactory(httpClient = { client })) }
        .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { coil3.disk.DiskCache.Builder().directory(context.cacheDir.resolve("frames").absolutePath.let { okio.Path.Companion.run { it.toPath() } }).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()

    val imageLoader: coil3.ImageLoader
    @Provides fun heldRepository(api: ClarityApi): HeldRepository = DefaultHeldRepository(api)
    @Provides fun amendmentsRepository(api: ClarityApi): AmendmentsRepository = DefaultAmendmentsRepository(api)

    @Provides @SingleIn(AppScope::class)
    fun toasts(): Toasts = Toasts()

    // --- the credential vault and phone approvals ---

    @Provides fun vaultApi(api: KtorClarityApi): VaultApi = api

    @Provides fun modelsRepository(api: KtorClarityApi): ModelsRepository = DefaultModelsRepository(api)

    @Provides fun mcpRepository(api: KtorClarityApi): net.robog.clarity.app.data.McpRepository =
        net.robog.clarity.app.data.DefaultMcpRepository(api)

    @Provides @SingleIn(AppScope::class)
    fun vaultKeys(): VaultKeys = AndroidVaultKeys()

    @Provides @SingleIn(AppScope::class)
    fun foregroundActivity(): ForegroundActivity = ForegroundActivity()

    @Provides fun phoneAuth(foreground: ForegroundActivity): PhoneAuth = BiometricPhoneAuth(foreground)

    @Provides fun vaultRepository(api: VaultApi, keys: VaultKeys): VaultRepository = DefaultVaultRepository(api, keys)

    /** Never-alone actions and vault releases are approved with a biometric yes signed on this phone. */
    @Provides fun approvalGate(api: VaultApi, keys: VaultKeys, auth: PhoneAuth, vault: VaultRepository): ApprovalGate =
        PhoneApprover(api, keys, auth, vault)

    // --- device and morning call ---

    @Provides @SingleIn(AppScope::class)
    fun deviceCapabilities(context: Context): DeviceCapabilities = AndroidDeviceCapabilities(context)

    @Provides @SingleIn(AppScope::class)
    fun callAlarms(context: Context): CallAlarms = AndroidCallAlarms(context)

    @Provides @SingleIn(AppScope::class)
    fun callSettingsCache(store: DataStore<Preferences>): CallSettingsCache = DataStoreCallSettingsCache(store)

    @Provides @SingleIn(AppScope::class)
    fun callScheduler(settings: SettingsRepository, cache: CallSettingsCache, alarms: CallAlarms, clock: AppClock): CallScheduler =
        CallScheduler(settings, cache, alarms, clock)

    @Provides @SingleIn(AppScope::class)
    fun androidCallNotifier(context: Context): AndroidCallNotifier = AndroidCallNotifier(context)

    @Provides fun callNotifier(notifier: AndroidCallNotifier): CallNotifier = notifier

    /** The morning call app-wide, so it outlives its screen. */
    @Provides @SingleIn(AppScope::class)
    fun callTracker(sessions: SessionsRepository, clock: AppClock): CallTracker = CallTracker(sessions, clock)

    @Provides @SingleIn(AppScope::class)
    fun ongoingCallStarter(context: Context, tracker: CallTracker, clock: AppClock): OngoingCallStarter =
        OngoingCallStarter(context, tracker, clock)

    @Provides fun callService(
        sessions: SessionsRepository,
        scheduler: CallScheduler,
        notifier: CallNotifier,
        tracker: CallTracker,
        capabilities: DeviceCapabilities,
    ): CallService = CallService(sessions, scheduler, notifier, tracker, capabilities)

    @Provides fun callAlarmHandler(
        cache: CallSettingsCache,
        feed: FeedStore,
        notifier: CallNotifier,
        capabilities: DeviceCapabilities,
        scheduler: CallScheduler,
        clock: AppClock,
        tracker: CallTracker,
    ): CallAlarmHandler = CallAlarmHandler(cache, feed, notifier, capabilities, scheduler, clock, tracker)

    // --- the phone connector ---

    @Provides fun smsSource(context: Context): SmsSource = AndroidSmsSource(context)
    @Provides fun smsCursor(store: DataStore<Preferences>): SmsCursorStore = DataStoreSmsCursorStore(store)

    @Provides @SingleIn(AppScope::class)
    fun dndActions(context: Context, store: DataStore<Preferences>, clock: AppClock): DndActionHandler =
        DndActionHandler(AndroidDndController(context), DataStoreDndStateStore(store), AndroidDndAlarms(context), clock)

    @Provides fun connectorWork(context: Context): ConnectorWork = WorkManagerConnectorWork(context)

    @Provides @SingleIn(AppScope::class)
    fun phoneConnector(
        ingest: IngestApi,
        sms: SmsSource,
        cursor: SmsCursorStore,
        capabilities: DeviceCapabilities,
        sessions: SessionStore,
        dnd: DndActionHandler,
        clock: AppClock,
        work: ConnectorWork,
    ): PhoneConnector = DefaultPhoneConnector(ingest, sms, cursor, capabilities, sessions, dnd, clock, work)

    // --- push: the foreground service that keeps the feed open ---

    @Provides @SingleIn(AppScope::class)
    fun pushPrefs(store: DataStore<Preferences>): PushPrefs = DataStorePushPrefs(store)

    @Provides @SingleIn(AppScope::class)
    fun frontScreen(): FrontScreen = FrontScreen()

    @Provides fun pushFallbackWork(context: Context): PushFallbackWork = WorkManagerPushFallbackWork(context)

    @Provides @SingleIn(AppScope::class)
    fun pushController(
        context: Context,
        sessions: SessionStore,
        flag: OnboardingFlagStore,
        prefs: PushPrefs,
        fallback: PushFallbackWork,
    ): PushController = AndroidPushController(context, sessions, flag, prefs, fallback)

    /** What the service and the fallback have already notified. */
    @Provides @SingleIn(AppScope::class)
    fun notifiedLedger(store: DataStore<Preferences>, sessions: SessionStore, clock: AppClock): NotifiedLedger =
        DataStoreNotifiedLedger(store, sessions, clock)

    /** Marks what the person reads in the app as seen, so it never notifies afterwards. */
    @Provides fun seenOnScreen(front: FrontScreen, store: FeedStore, ledger: NotifiedLedger): SeenOnScreen =
        SeenOnScreen(front.front, store, ledger)

    /**
     * The app in front, the push service and the ongoing call share one feed connection, one action
     * loop and one notification listener.
     */
    @Provides @SingleIn(AppScope::class)
    fun connectionHolder(sync: FeedSync, phone: PhoneConnector, listener: PushListener): ConnectionHolder =
        ConnectionHolder(CoroutineScope(SupervisorJob() + Dispatchers.Default), sync::run, phone::runActionLoop, listener::run)

    @Provides @SingleIn(AppScope::class)
    fun pushNotifier(context: Context): PushNotifier = AndroidPushNotifier(context)

    @Provides @SingleIn(AppScope::class)
    fun pushListener(
        sync: FeedSync,
        front: FrontScreen,
        capabilities: DeviceCapabilities,
        clock: AppClock,
        notifier: PushNotifier,
        ledger: NotifiedLedger,
        store: FeedStore,
        call: CallTracker,
    ): PushListener = PushListener(
        sync.arrivals, { front.front.value }, capabilities::notificationsEnabled, clock, notifier, ledger,
        batches = sync.caughtUp,
        cached = { store.notifiable() },
        call = { call.live()?.id },
    )

    /** The periodic catch-up for when the service is not running. */
    @Provides fun pushFallback(
        sessions: SessionStore,
        flag: OnboardingFlagStore,
        prefs: PushPrefs,
        sync: FeedSync,
        listener: PushListener,
        capabilities: DeviceCapabilities,
        controller: PushController,
        work: PushFallbackWork,
    ): PushFallback = PushFallback(
        conditions = { pushConditions(sessions, flag, prefs).first() },
        serviceRunning = FeedService::isRunning,
        feedLive = { sync.state.value == SyncState.Live },
        catchUp = sync::catchUp,
        deliver = listener::deliverMissed,
        reconnect = sync::reconnectNow,
        canStartService = { canStartServiceNow(capabilities.batteryUnrestricted()) },
        // Reconcile starts it when the conditions hold, and copes with a refusal.
        startService = controller::reconcile,
        work = work,
    )

    // Entry points for receivers and workers, which are not created by the graph.
    val callAlarmHandler: CallAlarmHandler
    val callTracker: CallTracker
    val androidCallNotifier: AndroidCallNotifier
    val ongoingCallStarter: OngoingCallStarter
    val appClock: AppClock
    val callService: CallService
    val callScheduler: CallScheduler
    val phoneConnector: PhoneConnector
    val dndActions: DndActionHandler
    val sessionStore: SessionStore
    val vaultRepository: VaultRepository
    val foregroundActivity: ForegroundActivity
    val feedSync: FeedSync
    val onboardingFlag: OnboardingFlagStore
    val pushPrefs: PushPrefs
    val pushController: PushController
    val frontScreen: FrontScreen
    val connection: ConnectionHolder
    val pushListener: PushListener
    val pushNotifier: PushNotifier
    val pushFallback: PushFallback
    val feedRepository: net.robog.clarity.app.data.FeedRepository

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
