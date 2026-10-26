package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.robog.clarity.app.AppConfig
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.ConstitutionRepository
import net.robog.clarity.app.data.EnrollmentRepository
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.ModelKeyOutcome
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.SaveConnectorOutcome
import net.robog.clarity.app.data.ServerProbe
import net.robog.clarity.app.data.ServerRepository
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.app.data.SetupRepository
import net.robog.clarity.app.data.UrlError
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.push.ConnectionHolder
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.app.ui.onboarding.ConnectorRow
import net.robog.clarity.app.ui.onboarding.ConnectorSetupViewModel
import net.robog.clarity.app.ui.onboarding.ConnectorsStepViewModel
import net.robog.clarity.app.ui.onboarding.ConstitutionEntry
import net.robog.clarity.app.ui.onboarding.ConstitutionStepViewModel
import net.robog.clarity.app.ui.onboarding.ConstitutionUi
import net.robog.clarity.app.ui.onboarding.DoneStepViewModel
import net.robog.clarity.app.ui.onboarding.InterviewPhase
import net.robog.clarity.app.ui.onboarding.ModelResult
import net.robog.clarity.app.ui.onboarding.ModelStepViewModel
import net.robog.clarity.app.ui.onboarding.OnboardingFlowViewModel
import net.robog.clarity.app.ui.onboarding.OnboardingStep
import net.robog.clarity.app.ui.onboarding.ScheduleStepViewModel
import net.robog.clarity.app.ui.onboarding.ServerError
import net.robog.clarity.app.ui.onboarding.ServerStepViewModel
import net.robog.clarity.app.ui.onboarding.SetupProblem
import net.robog.clarity.app.ui.onboarding.TestState
import net.robog.clarity.app.ui.onboarding.ValuesStepViewModel
import net.robog.clarity.app.ui.onboarding.callTimeChoices
import net.robog.clarity.app.ui.onboarding.connectorsSummaryLine
import net.robog.clarity.app.ui.onboarding.connectorDisplayName
import net.robog.clarity.app.ui.onboarding.protocolLabel
import net.robog.clarity.app.ui.onboarding.constitutionSections
import net.robog.clarity.app.ui.onboarding.deriveInterview
import net.robog.clarity.app.ui.onboarding.doneLine
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.app.ui.onboarding.initialStep
import net.robog.clarity.app.ui.onboarding.isServerSetupComplete
import net.robog.clarity.app.ui.onboarding.isSetUp
import net.robog.clarity.app.ui.onboarding.nextCallDay
import net.robog.clarity.app.ui.onboarding.sectionLabel
import net.robog.clarity.app.ui.onboarding.toggleDay
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.MicInput
import net.robog.clarity.app.voice.MicState
import net.robog.clarity.app.voice.SpeechEvent
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.micStep
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.InstanceInfo
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestResult
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.SetupStatus

// --- fakes ---

private val info = InstanceInfo("Home", "1.0")

private class FakeEnrollment(var result: ApiResult<InstanceInfo> = ApiResult.Success(info)) : EnrollmentRepository {
    val calls = mutableListOf<Pair<String, String?>>()
    override suspend fun enroll(instanceUrl: String, enrollmentToken: String?): ApiResult<InstanceInfo> {
        calls += instanceUrl to enrollmentToken
        return result
    }
}

private class FakeServers(var result: ApiResult<ServerProbe> = ApiResult.Success(ServerProbe(18, true))) : ServerRepository {
    val probed = mutableListOf<String>()
    override suspend fun probe(instanceUrl: String): ApiResult<ServerProbe> { probed += instanceUrl; return result }
}

private class FakeSetup(var status: SetupStatus? = null, var keyOutcome: ModelKeyOutcome = ModelKeyOutcome.Works) : SetupRepository {
    val keys = mutableListOf<String>()
    override suspend fun status(): SetupStatus? = status
    override suspend fun submitModelKey(apiKey: String): ModelKeyOutcome { keys += apiKey; return keyOutcome }
}

private class FakeSessionRepo(enrolled: Boolean) : SessionRepository {
    val store = FakeSessionStore(if (enrolled) signedIn() else null)
    override val session = store.session
    override suspend fun forget() = store.clear()
}

private class FakePhone(var granted: Boolean = false) : PhonePermissions {
    override fun isGranted(kind: PhoneConnectorKind) = granted
}

private class FakeConnectors(var list: ApiResult<List<ConnectorSummary>>) : ConnectorsRepository {
    val tests = mutableListOf<ConnectorId>()
    var testResults = mutableMapOf<String, ApiResult<ConnectorTestResult>>()
    var saveOutcome: SaveConnectorOutcome = SaveConnectorOutcome.Saved
    var saved = mutableListOf<Pair<ConnectorId, FormValues>>()
    var stored: ConnectorConfig? = null
    override suspend fun list() = list
    override suspend fun storedConfig(id: ConnectorId) = stored
    override suspend fun save(connector: ConnectorSummary, values: FormValues): SaveConnectorOutcome {
        saved += connector.id to values
        return saveOutcome
    }
    override suspend fun test(id: ConnectorId): ApiResult<ConnectorTestResult> {
        tests += id
        return testResults[id.value] ?: ApiResult.Success(ConnectorTestResult(true, summary = "ok ${id.value}"))
    }
}

private class FakeSessions : SessionsRepository {
    var startResult: ApiResult<Session> = ApiResult.Success(session(0))
    val started = mutableListOf<SessionKind>()
    val controls = mutableListOf<SessionControl>()
    var controlResult: ApiResult<Session>? = null
    var current: Session = session(0)
    override suspend fun start(kind: SessionKind, heldActionId: net.robog.clarity.shared.governance.HeldActionId?): ApiResult<Session> { started += kind; return startResult }
    override suspend fun active(kind: SessionKind): ApiResult<Session?> = ApiResult.Success(null)
    override suspend fun get(id: SessionId): ApiResult<Session> = ApiResult.Success(current)
    override suspend fun control(id: SessionId, control: SessionControl): ApiResult<Session> {
        controls += control
        return controlResult ?: ApiResult.Success(current)
    }
}

private fun session(q: Int, state: Session.State = Session.State.ACTIVE, line: Line? = line(q)) = Session(
    SessionId("s1"), SessionKind.VALUES_INTERVIEW, state, T0, question = q, questionCount = 7, capturedLine = line,
)

private fun line(q: Int, section: Section = Section.VALUES) = Line(LineId("l$q"), section, "rule $q", "because $q")

private class FakeFeed : FeedRepository {
    val itemsFlow = MutableStateFlow<List<FeedItem>>(emptyList())
    val signalFlow = MutableSharedFlow<SyncSignal>(extraBufferCapacity = 4)
    val sent = mutableListOf<Triple<String, InputSource, SessionId?>>()
    override val items: Flow<List<FeedItem>> = itemsFlow
    override val tasks: Flow<List<AgentTask>> = emptyFlow()
    override val held: Flow<List<HeldAction>> = emptyFlow()
    override val syncState: Flow<SyncState> = MutableStateFlow(SyncState.Live)
    override val agentBusy: Flow<Boolean> = MutableStateFlow(false)
    override val signals: Flow<SyncSignal> = signalFlow
    override suspend fun send(text: String, source: InputSource, sessionId: SessionId?, clientId: ClientInputId?): ApiResult<InputAccepted> {
        sent += Triple(text, source, sessionId)
        return ApiResult.Success(InputAccepted(userItem(100, text, sessionId)))
    }
    override suspend fun decideHeld(id: HeldActionId, decision: HeldDecision) = ApiResult.Success(Unit)
}

private class FakeSpeech : SpeechInput {
    val flow = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 8)
    override val events: Flow<SpeechEvent> = flow
    val micState = MutableStateFlow(MicState())
    override val mic = micState
    val calls = mutableListOf<String>()
    override fun start(keepOn: Boolean) { calls += "start($keepOn)"; micState.value = micStep(micState.value, MicInput.Start(keepOn)).first }
    override fun stop() { calls += "stop"; micState.value = MicState() }
    override fun mute() { calls += "mute"; micState.value = micStep(micState.value, MicInput.Mute).first }
    override fun unmute() { calls += "unmute" }
    override fun release() = Unit
}

private class FakeSettings(var loaded: ApiResult<Settings> = ApiResult.Success(Settings()), var saveResult: ApiResult<Unit> = ApiResult.Success(Unit)) : SettingsRepository {
    val saved = mutableListOf<Settings>()
    override suspend fun load() = loaded
    override suspend fun save(settings: Settings): ApiResult<Unit> { saved += settings; return saveResult }
}

private class FakeFlag : OnboardingFlagStore {
    val state = MutableStateFlow(false)
    override val done: Flow<Boolean> = state
    override suspend fun markDone() { state.value = true }
}

private class FakeConstitution(var result: ApiResult<Constitution>) : ConstitutionRepository {
    override suspend fun current() = result
}

private fun manifest(schema: String, produces: Set<Noun> = setOf(Noun.MESSAGE), key: String? = "k") = ConnectorManifest(
    kind = "imap", displayName = "Mail", version = "1", produces = produces,
    configSchema = Json.parseToJsonElement(schema).jsonObject, sealingKey = key,
)

private fun summary(id: String, granted: Boolean, schema: String = """{"properties":{"host":{"type":"string"}}}""") = ConnectorSummary(
    ConnectorId(id), manifest(schema),
    grants = mapOf(Noun.MESSAGE to if (granted) GrantState.GRANTED else GrantState.PENDING), lastSeenAt = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
private inline fun withMain(block: () -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try { block() } finally { Dispatchers.resetMain() }
}

// --- pure rules ---

class OnboardingStepTest {
    private fun status(
        key: SetupStatus.KeyState = SetupStatus.KeyState.VALID,
        connectors: Int = 3,
        constitution: Int? = 1,
    ) = SetupStatus(key, connectors, constitution, Settings())

    @Test fun stepsRunInOrderWithTheConnectorDetour() {
        assertEquals(OnboardingStep.Server, OnboardingStep.Welcome.next())
        assertEquals(OnboardingStep.Connectors, OnboardingStep.Model.next())
        assertEquals(OnboardingStep.Connectors, OnboardingStep.ConnectorSetup.next())
        assertEquals(OnboardingStep.Connectors, OnboardingStep.ConnectorSetup.previous())
        assertEquals(OnboardingStep.Done, OnboardingStep.Done.next())
        assertNull(OnboardingStep.Welcome.previous())
        assertNull(OnboardingStep.Done.previous())
        assertEquals(OnboardingStep.Model, OnboardingStep.Connectors.previous())
    }

    @Test fun barSegmentsFollowTheFourPhases() {
        assertEquals(
            listOf(0, 1, 1, 1, 1, 2, 2, 3, 4, 0),
            OnboardingStep.entries.map { it.segments },
        )
        assertNull(OnboardingStep.Welcome.kicker)
        assertEquals("step 2 of 4 · values", OnboardingStep.Values.kicker)
    }

    @Test fun resumesAtTheFirstMissingThing() {
        assertEquals(OnboardingStep.Welcome, initialStep(enrolled = false, status = null))
        assertEquals(OnboardingStep.Model, initialStep(true, null))
        assertEquals(OnboardingStep.Model, initialStep(true, status(key = SetupStatus.KeyState.MISSING)))
        assertEquals(OnboardingStep.Connectors, initialStep(true, status(connectors = 0)))
        assertEquals(OnboardingStep.ValuesIntro, initialStep(true, status(constitution = null)))
        assertEquals(OnboardingStep.Schedule, initialStep(true, status()))
    }

    @Test fun serverSetupCompleteNeedsKeyConnectorAndConstitution() {
        assertTrue(isServerSetupComplete(status()))
        assertFalse(isServerSetupComplete(status(connectors = 0)))
        assertFalse(isServerSetupComplete(status(constitution = null)))
        assertFalse(isServerSetupComplete(status(key = SetupStatus.KeyState.INVALID)))
        assertFalse(isServerSetupComplete(null))
    }

    @Test fun connectorsSummaryLines() {
        assertEquals("2 of 6 set up", connectorsSummaryLine(6, 2, 0, syncing = false, anyFailed = false))
        assertEquals("testing and syncing…", connectorsSummaryLine(6, 6, 1, syncing = true, anyFailed = false))
        assertEquals("5 of 6 synced · tap the failed one to fix it", connectorsSummaryLine(6, 6, 5, syncing = false, anyFailed = true))
        assertEquals("all 6 synced", connectorsSummaryLine(6, 6, 6, syncing = false, anyFailed = false))
        assertEquals("2 of 4 synced · 1 not running · tap the failed one to fix it", connectorsSummaryLine(4, 4, 2, syncing = false, anyFailed = true, notRunning = 1))
        assertEquals("Dav", connectorDisplayName("dav"))
        assertEquals("IMAP", protocolLabel("imap"))
        assertEquals("iCloud", net.robog.clarity.app.ui.components.sentenceCase("iCloud"))
        assertEquals("v3 · 14 lines", net.robog.clarity.app.ui.components.sentenceCase("v3 · 14 lines"))
        assertEquals("Not now", net.robog.clarity.app.ui.components.sentenceCase("not now"))
    }

    @Test fun aConnectorIsSetUpWhenEveryProducedNounIsGranted() {
        assertTrue(isSetUp(summary("a", true)))
        assertFalse(isSetUp(summary("a", false)))
        assertFalse(isSetUp(ConnectorSummary(ConnectorId("a"), null, emptyMap(), null)))
    }
}

class InterviewDerivationTest {
    private val s = SessionId("s1")

    @Test fun questionIsTheLatestAgentTextAndAnswerFollowsIt() {
        val items = listOf(
            agentItem(1, "Q1", s), userItem(2, "A1", s), agentItem(3, "Q2", s), agentItem(4, "other", SessionId("x")),
        )
        assertEquals("Q2", deriveInterview(items, s).question)
        assertNull(deriveInterview(items, s).answer, "no answer to the new question yet")
        assertEquals("A2", deriveInterview(items + userItem(5, "A2", s), s).answer)
    }

    @Test fun nothingYet() {
        assertEquals(deriveInterview(emptyList(), s).question, null)
        assertEquals("A", deriveInterview(listOf(userItem(1, "A", s)), s).answer)
    }

    @Test fun sectionLabelsMatchThePrototype() {
        assertEquals("hard line", sectionLabel(Section.HARD_LINES))
        assertEquals("how much I do alone", sectionLabel(Section.AUTONOMY))
        assertEquals("repair + memory", sectionLabel(Section.REPAIR))
    }
}

class ConstitutionSectionsTest {
    private val c = Constitution(
        version = 1,
        lines = listOf(
            line(0, Section.VALUES), line(1, Section.HARD_LINES), line(2, Section.PEOPLE),
            Line(LineId("fixed"), Section.HARD_LINES, "Never move money.", null, hard = true),
        ),
        autonomy = mapOf(
            ActionKind.CREATE_EVENT to AutonomyTier.DO_THEN_TELL,
            ActionKind.SAVE_DRAFT to AutonomyTier.DO_THEN_TELL,
            ActionKind.SEND_EMAIL to AutonomyTier.ASK_FIRST,
            ActionKind.PAYMENT to AutonomyTier.NEVER_ALONE,
        ),
        updatedAt = T0,
    )

    @Test fun groupsByPrototypeSectionsAndSkipsEmptyOnes() {
        val titles = constitutionSections(c).map { it.title }
        assertEquals(listOf("what I value", "hard lines · never crossed", "how much I do alone", "who gets through"), titles)
    }

    @Test fun keepsReasonsAndFixedLines() {
        val hard = constitutionSections(c).first { it.title.startsWith("hard") }
        assertEquals(listOf(ConstitutionEntry("rule 1", "because 1"), ConstitutionEntry("Never move money.", null)), hard.entries)
    }

    @Test fun autonomyListsTiersInOrder() {
        val auto = constitutionSections(c).first { it.title == "how much I do alone" }
        assertEquals(
            listOf(
                "do it, then tell me: save draft, create event.",
                "ask me first: send email.",
                "never alone: payment.",
            ),
            auto.entries.map { it.text },
        )
    }
}

class ScheduleRulesTest {
    private val call = Settings.MorningCall(days = setOf(1, 2, 3, 4, 5), at = LocalTime(9, 0))

    @Test fun timeFormattingAndChoices() {
        assertEquals("9:00 am", formatTime(LocalTime(9, 0)))
        assertEquals("8:05 am", formatTime(LocalTime(8, 5)))
        assertEquals("12:00 pm", formatTime(LocalTime(12, 0)))
        assertEquals("12:15 am", formatTime(LocalTime(0, 15)))
        assertEquals("6:30 pm", formatTime(LocalTime(18, 30)))
        assertEquals(3, callTimeChoices(LocalTime(9, 30)).size)
        assertEquals(listOf("8:30 am", "9:00 am", "9:30 am", "10:15 am"), callTimeChoices(LocalTime(10, 15)).map(::formatTime))
    }

    @Test fun daysToggleBothWays() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6), call.toggleDay(6).days)
        assertEquals(setOf(2, 3, 4, 5), call.toggleDay(1).days)
    }

    @Test fun nextCallDay() {
        // 2026-10-26 is a Monday.
        assertEquals("tomorrow", nextCallDay(call, LocalDate(2026, 10, 26)))
        // Friday: the next weekday call is Monday.
        assertEquals("on Monday", nextCallDay(call, LocalDate(2026, 10, 30)))
        assertNull(nextCallDay(call.copy(days = emptySet()), LocalDate(2026, 10, 26)))
    }

    @Test fun doneLineVariants() {
        assertEquals("I’ll send a heads-up 10 minutes before, then call. Pick up in the app.", doneLine(call))
        assertEquals(
            "I’ll send a heads-up 10 minutes before, then a notification to start.",
            doneLine(call.copy(mode = Settings.MorningCall.Mode.NOTIFY)),
        )
        assertEquals("I’ll call at 9:00 am. Pick up in the app.", doneLine(call.copy(headsUpMinutes = null)))
        assertEquals(
            "You’ll get a notification at 9:00 am.",
            doneLine(call.copy(headsUpMinutes = null, mode = Settings.MorningCall.Mode.NOTIFY)),
        )
    }
}

// --- view models ---

class ServerStepViewModelTest {
    private fun vm(enrolled: Boolean = false, enroll: FakeEnrollment = FakeEnrollment(), servers: FakeServers = FakeServers(), cleartext: Boolean = false) =
        ServerStepViewModel(servers, enroll, FakeSessionRepo(enrolled), AppConfig(allowCleartext = cleartext))

    @Test fun invalidUrlNeverCallsTheServer() = withMain {
        val servers = FakeServers(); val enroll = FakeEnrollment()
        val vm = vm(enroll = enroll, servers = servers)
        vm.onUrlChanged("http://insecure.example")
        vm.check()
        assertEquals(ServerError.InvalidUrl(UrlError.InsecureScheme), vm.state.value.error)
        assertTrue(servers.probed.isEmpty() && enroll.calls.isEmpty())
    }

    @Test fun checkProbesThenEnrollsWithTheNormalizedUrlAndCode() = withMain {
        val servers = FakeServers(); val enroll = FakeEnrollment()
        val vm = vm(enroll = enroll, servers = servers)
        vm.onUrlChanged("https://Clarity.example.com/")
        vm.onCodeChanged("abc-123")
        vm.check()
        assertEquals(listOf("https://clarity.example.com"), servers.probed)
        assertEquals(listOf<Pair<String, String?>>("https://clarity.example.com" to "abc-123"), enroll.calls)
        val s = vm.state.value
        assertTrue(s.enrolled && !s.checking && s.error == null)
        assertEquals(ServerProbe(18, true), s.probe)
        assertFalse(s.canCheck)
    }

    @Test fun anUnreachableServerIsNotEnrolled() = withMain {
        val enroll = FakeEnrollment()
        val vm = vm(enroll = enroll, servers = FakeServers(ApiResult.Failure(ApiError.Unreachable("dns"))))
        vm.onUrlChanged("https://nope.example")
        vm.check()
        assertEquals(ServerError.Unreachable, vm.state.value.error)
        assertTrue(enroll.calls.isEmpty() && !vm.state.value.enrolled)
    }

    @Test fun aRefusedSetupCodeAsksForIt() = withMain {
        val vm = vm(enroll = FakeEnrollment(ApiResult.Failure(ApiError.Unauthorized(null))), cleartext = true)
        vm.onUrlChanged("http://10.0.2.2:8080")
        vm.check()
        assertEquals(ServerError.CodeRequired, vm.state.value.error)
        assertFalse(vm.state.value.enrolled)
        // Editing clears the error and allows another try.
        vm.onCodeChanged("fixed")
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.canCheck)
    }

    @Test fun alreadyEnrolledShowsTheConnectedServer() = withMain {
        val vm = vm(enrolled = true)
        assertTrue(vm.state.value.enrolled)
        assertEquals("https://clarity.example", vm.state.value.url)
    }
}

class ModelStepViewModelTest {
    @Test fun checkSubmitsTheTrimmedKeyAndShowsItWorks() = withMain {
        val setup = FakeSetup()
        val vm = ModelStepViewModel(setup)
        assertFalse(vm.state.value.canCheck)
        vm.onKeyChanged("  sk-ant-api03-abc  ")
        vm.check()
        assertEquals(listOf("sk-ant-api03-abc"), setup.keys)
        assertEquals(ModelResult.Works, vm.state.value.result)
        assertTrue(vm.state.value.canContinue && !vm.state.value.canCheck)
    }

    @Test fun aRejectedKeyCanBeCorrected() = withMain {
        val setup = FakeSetup(keyOutcome = ModelKeyOutcome.Rejected("invalid x-api-key"))
        val vm = ModelStepViewModel(setup)
        vm.onKeyChanged("bad")
        vm.check()
        assertEquals(ModelResult.Rejected("invalid x-api-key"), vm.state.value.result)
        assertFalse(vm.state.value.canContinue)
        vm.onKeyChanged("good")
        assertNull(vm.state.value.result)
    }

    @Test fun aServerWithoutTheRouteDoesNotBlockSetup() = withMain {
        val vm = ModelStepViewModel(FakeSetup(keyOutcome = ModelKeyOutcome.Unavailable))
        vm.onKeyChanged("k")
        vm.check()
        assertEquals(ModelResult.ServerNotReady, vm.state.value.result)
        assertTrue(vm.state.value.canContinue)
    }
}

class FlowViewModelTest {
    @Test fun notEnrolledStartsAtWelcome() = withMain {
        val vm = OnboardingFlowViewModel(FakeSessionRepo(false), FakeSetup(), FakeFlag())
        assertTrue(vm.state.value.ready)
        assertEquals(OnboardingStep.Welcome, vm.state.value.step)
    }

    @Test fun enrolledResumesWhereTheServerSaysSetupStopped() = withMain {
        val setup = FakeSetup(SetupStatus(SetupStatus.KeyState.VALID, 0, null, Settings()))
        val vm = OnboardingFlowViewModel(FakeSessionRepo(true), setup, FakeFlag())
        assertEquals(OnboardingStep.Connectors, vm.state.value.step)
        vm.next()
        assertEquals(OnboardingStep.ValuesIntro, vm.state.value.step)
        vm.back()
        assertEquals(OnboardingStep.Connectors, vm.state.value.step)
        vm.go(OnboardingStep.Welcome)
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.state.value.step, "nothing before the welcome screen")
    }
}

class ConnectorsStepViewModelTest {
    private fun vm(connectors: FakeConnectors, phone: FakePhone = FakePhone(true)) = ConnectorsStepViewModel(connectors, phone)

    @Test fun listsServerConnectorsThenThePhoneOnes() = withMain {
        val repo = FakeConnectors(ApiResult.Success(listOf(summary("mail", true), summary("cal", false))))
        val vm = vm(repo)
        vm.refresh()
        val items = vm.state.value.items
        assertEquals(listOf("mail", "cal", "phone-messages"), items.map { it.row.key })
        assertEquals(listOf(true, false, true), items.map { it.saved })
        assertEquals(listOf("ready", "set up →", "ready"), items.map { it.status })
        assertEquals("IMAP · on your server", items[0].row.proto)
        assertEquals("allowed · on this phone", items[2].caption)
        assertFalse(vm.state.value.canSync, "every connector must be set up first")
        assertEquals("2 of 3 set up", vm.state.value.summaryLine)
    }

    @Test fun aServerWithoutTheRouteStillOffersThePhoneConnectors() = withMain {
        val vm = vm(FakeConnectors(ApiResult.Failure(ApiError.NotFound(null))))
        vm.refresh()
        assertTrue(vm.state.value.unavailable)
        assertNull(vm.state.value.error)
        assertEquals(listOf("phone-messages"), vm.state.value.items.map { it.row.key })
    }

    @Test fun otherFailuresAreReported() = withMain {
        val vm = vm(FakeConnectors(ApiResult.Failure(ApiError.Unreachable("x"))))
        vm.refresh()
        assertFalse(vm.state.value.unavailable)
        assertIs<ApiError.Unreachable>(vm.state.value.error)
    }

    @Test fun syncAllTestsEachConnectorAndShowsFailures() = withMain {
        val repo = FakeConnectors(ApiResult.Success(listOf(summary("mail", true), summary("files", true))))
        repo.testResults["files"] = ApiResult.Success(ConnectorTestResult(false, error = "password rejected (401)"))
        val vm = vm(repo)
        vm.refresh()
        assertTrue(vm.state.value.canSync)
        vm.syncAll()
        val s = vm.state.value
        assertEquals(listOf(ConnectorId("mail"), ConnectorId("files")), repo.tests)
        assertEquals(TestState.Ok("ok mail"), s.items[0].test)
        assertEquals(TestState.Failed("password rejected (401)"), s.items[1].test)
        assertEquals("failed · fix →", s.items[1].status)
        assertEquals("password rejected (401)", s.items[1].caption)
        assertTrue(s.anyFailed && !s.syncing && !s.allSynced && !s.canSync)
        assertEquals("2 of 3 synced · tap the failed one to fix it", s.summaryLine)
    }

    @Test fun fixingAFailedConnectorRetestsItAndEnablesContinue() = withMain {
        val repo = FakeConnectors(ApiResult.Success(listOf(summary("files", true))))
        repo.testResults["files"] = ApiResult.Success(ConnectorTestResult(false, error = "401"))
        val vm = vm(repo)
        vm.refresh(); vm.syncAll()
        assertTrue(vm.state.value.anyFailed)
        repo.testResults["files"] = ApiResult.Success(ConnectorTestResult(true, summary = "1,940 files"))
        vm.afterSetup("files", retest = true)
        assertEquals(TestState.Ok("1,940 files"), vm.state.value.items.first { it.row.key == "files" }.test)
        assertFalse(vm.state.value.anyFailed)
        assertTrue(vm.state.value.allSynced)
        assertEquals("all 2 synced", vm.state.value.summaryLine)
    }

    @Test fun missingTestRouteIsAFailureNotACrash() = withMain {
        val repo = FakeConnectors(ApiResult.Success(listOf(summary("mail", true))))
        repo.testResults["mail"] = ApiResult.Failure(ApiError.NotFound(null))
        val vm = vm(repo)
        vm.refresh(); vm.syncAll()
        assertEquals(TestState.Failed("not available on this server yet"), vm.state.value.items[0].test)
    }

    @Test fun syncIsBlockedWhileAPhonePermissionIsMissing() = withMain {
        val vm = vm(FakeConnectors(ApiResult.Success(listOf(summary("mail", true)))), FakePhone(false))
        vm.refresh()
        assertFalse(vm.state.value.canSync)
    }
}

class ConnectorSetupViewModelTest {
    private val schema = """
        {"description":"I read your inbox.","required":["host","password"],
         "properties":{"host":{"type":"string"},"password":{"type":"string","format":"password"},"port":{"type":"integer","default":993}}}
    """

    private fun row(granted: Boolean = false) = ConnectorRow.Server(summary("mail", granted, schema))

    @Test fun buildsTheFormFromTheManifestSchema() = withMain {
        val vm = ConnectorSetupViewModel(FakeConnectors(ApiResult.Success(emptyList())), FakePhone())
        vm.open(row(), failedReason = null)
        val s = vm.state.value
        assertEquals("Mail", s.title)
        assertEquals("I read your inbox.", s.blurb)
        assertEquals(listOf("host", "password", "port"), s.form.fields.map { it.key })
        assertEquals(mapOf("port" to "993"), s.values.texts)
        assertFalse(s.canSave, "required fields are empty")
        assertTrue(s.errors.isEmpty(), "errors only show after a save attempt")
        assertEquals("save", s.saveLabel)
    }

    @Test fun savingWithMissingFieldsShowsErrorsWithoutCallingTheServer() = withMain {
        val repo = FakeConnectors(ApiResult.Success(emptyList()))
        val vm = ConnectorSetupViewModel(repo, FakePhone())
        vm.open(row(), null)
        vm.save()
        assertEquals(setOf("host", "password"), vm.state.value.errors.keys)
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun savingSendsTheValuesAndSignalsDone() = withMain {
        val repo = FakeConnectors(ApiResult.Success(emptyList()))
        val vm = ConnectorSetupViewModel(repo, FakePhone())
        vm.open(row(), null)
        vm.onValueChanged("host", "imap.home.local")
        vm.onValueChanged("password", "pw")
        assertTrue(vm.state.value.canSave)
        vm.save()
        assertEquals(listOf(ConnectorId("mail") to mapOf("port" to "993", "host" to "imap.home.local", "password" to "pw")), repo.saved.map { it.first to it.second.texts })
        assertTrue(vm.state.value.saved)
        vm.consumeSaved()
        assertFalse(vm.state.value.saved)
    }

    @Test fun serverProblemsAreExplained() = withMain {
        val repo = FakeConnectors(ApiResult.Success(emptyList()))
        val vm = ConnectorSetupViewModel(repo, FakePhone())
        vm.open(row(), null)
        vm.onValueChanged("host", "h"); vm.onValueChanged("password", "p")

        repo.saveOutcome = SaveConnectorOutcome.NoSealingKey
        vm.save()
        assertEquals(SetupProblem.NoSealingKey, vm.state.value.problem)
        assertFalse(vm.state.value.saved)

        repo.saveOutcome = SaveConnectorOutcome.Failed(ApiError.NotFound(null))
        vm.save()
        assertEquals(SetupProblem.NotAvailable, vm.state.value.problem)

        vm.onValueChanged("host", "h2")
        assertNull(vm.state.value.problem, "editing clears the message")
    }

    @Test fun aFailedConnectorSaysSaveAndRetest() = withMain {
        val vm = ConnectorSetupViewModel(FakeConnectors(ApiResult.Success(emptyList())), FakePhone())
        vm.open(row(true), failedReason = "password rejected (401)")
        assertEquals("save and retest", vm.state.value.saveLabel)
        assertTrue(vm.wasFailed())
    }

    @Test fun prefillsStoredNonSecretValues() = withMain {
        val repo = FakeConnectors(ApiResult.Success(emptyList()))
        repo.stored = ConnectorConfig(3, Json.parseToJsonElement("""{"host":"imap.x","password":{"type":"sealed"}}""").jsonObject)
        val vm = ConnectorSetupViewModel(repo, FakePhone())
        vm.open(row(true), null)
        assertEquals("imap.x", vm.state.value.values.text("host"))
        assertEquals("", vm.state.value.values.text("password"))
        assertTrue("password" in vm.state.value.values.storedSecrets, "the stored secret is kept, not shown")
    }

    @Test fun phoneConnectorNeedsThePermission() = withMain {
        val phone = FakePhone(false)
        val vm = ConnectorSetupViewModel(FakeConnectors(ApiResult.Success(emptyList())), phone)
        vm.open(ConnectorRow.Phone(PhoneConnectorKind.Messages), null)
        assertFalse(vm.state.value.canSave)
        vm.onPermissionResult(true)
        assertTrue(vm.state.value.canSave)
        vm.save()
        assertTrue(vm.state.value.saved)
    }
}

class ValuesStepViewModelTest {
    private class Rig {
        val sessions = FakeSessions(); val feed = FakeFeed(); val speech = FakeSpeech()
        val connection = ConnectionHolder(
            CoroutineScope(Dispatchers.Unconfined),
            feed = { awaitCancellation() },
            actions = { awaitCancellation() },
        )
        val vm = ValuesStepViewModel(sessions, feed, speech, connection)
    }

    @Test fun theInterviewKeepsTheFeedOpenSoQuestionsArrive() = withMain {
        val r = Rig()
        assertFalse(r.connection.feedRunning)
        r.vm.begin()
        assertTrue(r.connection.feedRunning, "onboarding runs before the main screen opens the feed")
        r.vm.restart()
        assertTrue(r.connection.feedRunning, "still open after redo")
    }

    @Test fun beginStartsAValuesInterviewSession() = withMain {
        val r = Rig()
        r.vm.begin()
        assertEquals(listOf(SessionKind.VALUES_INTERVIEW), r.sessions.started)
        val s = r.vm.state.value
        assertEquals(InterviewPhase.Active, s.phase)
        assertEquals("1 of 7", s.position)
        assertEquals("rule 0", s.captured?.text)
        assertTrue(s.canDecide)
        r.vm.begin()
        assertEquals(1, r.sessions.started.size, "already running")
    }

    @Test fun missingRouteIsReportedAsUnavailable() = withMain {
        val r = Rig(); r.sessions.startResult = ApiResult.Failure(ApiError.NotFound(null))
        r.vm.begin()
        assertEquals(InterviewPhase.Unavailable, r.vm.state.value.phase)
    }

    @Test fun otherFailuresCanBeRetried() = withMain {
        val r = Rig(); r.sessions.startResult = ApiResult.Failure(ApiError.Unreachable("x"))
        r.vm.begin()
        assertIs<InterviewPhase.Failed>(r.vm.state.value.phase)
        r.sessions.startResult = ApiResult.Success(session(0))
        r.vm.restart()
        assertEquals(InterviewPhase.Active, r.vm.state.value.phase)
    }

    @Test fun questionAndAnswerComeFromTheSessionsFeedItems() = withMain {
        val r = Rig(); r.vm.begin()
        val id = SessionId("s1")
        r.feed.itemsFlow.value = listOf(agentItem(1, "Think of a time…", id), userItem(2, "My old assistant…", id))
        assertEquals("Think of a time…", r.vm.state.value.question)
        assertEquals("My old assistant…", r.vm.state.value.said)
    }

    @Test fun partialSpeechIsLiveAndFinalSpeechIsSentAsVoiceInput() = withMain {
        val r = Rig(); r.vm.begin()
        r.speech.flow.tryEmit(SpeechEvent.Partial("my old"))
        assertEquals("my old", r.vm.state.value.live)
        r.speech.flow.tryEmit(SpeechEvent.Final(" my old assistant "))
        assertNull(r.vm.state.value.live)
        assertEquals(listOf<Triple<String, InputSource, SessionId?>>(Triple("my old assistant", InputSource.VOICE, SessionId("s1"))), r.feed.sent)
    }

    @Test fun keepAdvancesRewordAsksForAnotherPhrasing() = withMain {
        val r = Rig(); r.vm.begin()
        r.sessions.controlResult = ApiResult.Success(session(0, Session.State.BUSY))
        r.vm.reword()
        assertEquals(listOf<SessionControl>(SessionControl.Reword), r.sessions.controls)
        assertTrue(r.vm.state.value.busy)
        assertFalse(r.vm.state.value.canDecide, "buttons wait while the agent works")

        r.sessions.controlResult = ApiResult.Success(session(1))
        r.vm.state.value.let { /* still busy: keep is ignored */ }
        r.vm.keep()
        assertEquals(1, r.sessions.controls.size)

        // The server finishes the reword and tells us through the feed signal.
        r.sessions.current = session(0)
        r.feed.signalFlow.tryEmit(SyncSignal.SessionChanged(SessionId("s1")))
        assertTrue(r.vm.state.value.canDecide)
        r.vm.keep()
        assertEquals(listOf(SessionControl.Reword, SessionControl.Next), r.sessions.controls)
        assertEquals("2 of 7", r.vm.state.value.position)
    }

    @Test fun aNewItemInTheSessionReloadsItEvenWhenTheSignalWasMissed() = withMain {
        val r = Rig()
        r.sessions.current = session(0, line = null)
        r.sessions.startResult = ApiResult.Success(r.sessions.current)
        r.vm.begin()
        assertFalse(r.vm.state.value.canDecide)
        val id = SessionId("s1")
        r.feed.itemsFlow.value = listOf(agentItem(1, "Think of a time…", id))
        // The agent captures a line while the stream is reconnecting: no signal, only the gap-filled card.
        r.sessions.current = session(0)
        r.feed.itemsFlow.value = r.feed.itemsFlow.value + agentItem(2, "captured as · value", id)
        assertEquals("rule 0", r.vm.state.value.captured?.text)
        assertTrue(r.vm.state.value.canDecide)
    }

    @Test fun keepingTheLastQuestionFinishesTheInterview() = withMain {
        val r = Rig(); r.vm.begin()
        r.sessions.controlResult = ApiResult.Success(session(6, Session.State.ENDED))
        r.vm.keep()
        assertEquals(InterviewPhase.Finished, r.vm.state.value.phase)
    }

    @Test fun micStateIsReflectedAndStoppedWhenLeaving() = withMain {
        val r = Rig(); r.vm.begin()
        r.vm.startListening()
        assertEquals(MicChipState.MicOn, r.vm.state.value.mic)
        r.vm.mute()
        assertEquals(MicChipState.Muted, r.vm.state.value.mic)
        r.vm.stopListening()
        assertEquals(MicChipState.Off, r.vm.state.value.mic)
        assertEquals(listOf("start(true)", "mute", "stop"), r.speech.calls)
    }

    @Test fun typedAnswersAreSentAsTyped() = withMain {
        val r = Rig(); r.vm.begin()
        r.vm.sendTyped("  I prefer short emails ")
        assertEquals(Triple("I prefer short emails", InputSource.TYPED, SessionId("s1")), r.feed.sent.single())
        r.vm.sendTyped("   ")
        assertEquals(1, r.feed.sent.size)
    }
}

class ConstitutionStepViewModelTest {
    @Test fun loadsAndGroupsTheConstitution() = withMain {
        val vm = ConstitutionStepViewModel(FakeConstitution(ApiResult.Success(Constitution(3, listOf(line(0)), updatedAt = T0))))
        vm.load()
        val s = assertIs<ConstitutionUi.Loaded>(vm.state.value)
        assertEquals(3, s.version)
        assertEquals(listOf("what I value"), s.sections.map { it.title })
    }

    @Test fun missingRouteAndFailures() = withMain {
        val vm = ConstitutionStepViewModel(FakeConstitution(ApiResult.Failure(ApiError.NotFound(null))))
        vm.load()
        assertEquals(ConstitutionUi.Unavailable, vm.state.value)
        val vm2 = ConstitutionStepViewModel(FakeConstitution(ApiResult.Failure(ApiError.Unreachable("x"))))
        vm2.load()
        assertIs<ConstitutionUi.Failed>(vm2.state.value)
    }
}

class ScheduleStepViewModelTest {
    @Test fun editsThePillsAndSavesTheSettings() = withMain {
        val settings = FakeSettings()
        val vm = ScheduleStepViewModel(settings, FixedClock())
        assertFalse(vm.state.value.loading)
        vm.pickTime(LocalTime(8, 30))
        vm.toggleDay(7)
        vm.toggleDay(1)
        vm.toggleHeadsUp()
        vm.toggleMode()
        vm.finish()
        val saved = settings.saved.single().morningCall
        assertEquals(LocalTime(8, 30), saved.at)
        assertEquals(setOf(2, 3, 4, 5, 7), saved.days)
        assertNull(saved.headsUpMinutes)
        assertEquals(Settings.MorningCall.Mode.NOTIFY, saved.mode)
        assertTrue(vm.state.value.saved && !vm.state.value.notStored)
        vm.toggleHeadsUp()
        assertEquals(10, vm.state.value.call.headsUpMinutes)
    }

    @Test fun showsStoredSettingsOnThePhonesClock() = withMain {
        // Stored 08:30 UTC = 09:30 Berlin (CET on T0)
        val existing = Settings(morningCall = Settings.MorningCall(at = LocalTime(8, 30)), homeZone = "Europe/Berlin")
        val vm = ScheduleStepViewModel(FakeSettings(ApiResult.Success(existing)), FixedClock(zone = kotlinx.datetime.TimeZone.of("Europe/Berlin")))
        assertEquals(LocalTime(9, 30), vm.state.value.call.at)
        assertEquals("Europe/Berlin", vm.state.value.settings.homeZone)
    }

    @Test fun aTimePickedInNewYorkIsSavedInUtcWithNewYorkAsHome() = withMain {
        val settings = FakeSettings(ApiResult.Failure(ApiError.NotFound(null)))
        val vm = ScheduleStepViewModel(settings, FixedClock(zone = kotlinx.datetime.TimeZone.of("America/New_York")))
        vm.pickTime(LocalTime(8, 30))
        vm.finish()
        val saved = settings.saved.single()
        assertEquals(LocalTime(13, 30), saved.morningCall.at) // EST offset; daylight saving moves the moment, not this
        assertEquals("America/New_York", saved.homeZone)
    }

    @Test fun aServerWithoutTheRouteLetsSetupFinish() = withMain {
        val vm = ScheduleStepViewModel(FakeSettings(ApiResult.Failure(ApiError.NotFound(null)), ApiResult.Failure(ApiError.NotFound(null))), FixedClock())
        vm.finish()
        assertTrue(vm.state.value.saved && vm.state.value.notStored)
        assertNull(vm.state.value.error)
    }

    @Test fun otherSaveFailuresStayOnTheScreen() = withMain {
        val vm = ScheduleStepViewModel(FakeSettings(saveResult = ApiResult.Failure(ApiError.Unreachable("x"))), FixedClock())
        vm.finish()
        assertFalse(vm.state.value.saved)
        assertIs<ApiError.Unreachable>(vm.state.value.error)
    }
}

class DoneStepViewModelTest {
    @Test fun summarizesSetupAndMarksOnboardingDone() = withMain {
        val flag = FakeFlag()
        val settings = FakeSettings(ApiResult.Success(Settings(morningCall = Settings.MorningCall(at = LocalTime(9, 0), days = setOf(1, 2, 3, 4, 5)))))
        val setup = FakeSetup(SetupStatus(SetupStatus.KeyState.VALID, 6, 1, Settings()))
        // Monday 2026-10-26 12:00 UTC.
        val vm = DoneStepViewModel(
            settings, setup, flag, { kotlin.time.Instant.parse("2026-10-26T12:00:00Z") },
            net.robog.clarity.app.testutil.FakeCapabilities(), net.robog.clarity.app.testutil.FakePushController(),
        )
        vm.load(kotlinx.datetime.TimeZone.UTC)
        val s = vm.state.value
        assertEquals(6, s.connectors)
        assertEquals(1, s.constitutionVersion)
        assertEquals("tomorrow", s.nextDay)
        assertFalse(flag.state.value)
        assertFalse(s.askBattery, "no explanation when the phone already lets the app run")
        vm.openApp()
        assertTrue(flag.state.value)
    }

    @Test fun explainsTheBatteryExemptionOnceWhileTheAppIsRestricted() = withMain {
        val push = net.robog.clarity.app.testutil.FakePushController()
        val vm = DoneStepViewModel(
            FakeSettings(ApiResult.Success(Settings())), FakeSetup(SetupStatus(SetupStatus.KeyState.VALID, 1, 1, Settings())), FakeFlag(),
            { kotlin.time.Instant.parse("2026-10-26T12:00:00Z") },
            net.robog.clarity.app.testutil.FakeCapabilities(battery = false), push,
        )
        vm.load(kotlinx.datetime.TimeZone.UTC)
        assertTrue(vm.state.value.askBattery)
        vm.batteryAsked()
        assertFalse(vm.state.value.askBattery)
        assertTrue(push.askedState.value)
        vm.load(kotlinx.datetime.TimeZone.UTC)
        assertFalse(vm.state.value.askBattery, "asked once is enough")
    }
}
