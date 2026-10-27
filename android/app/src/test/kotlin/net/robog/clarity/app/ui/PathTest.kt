package net.robog.clarity.app.ui

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.PathRepository
import net.robog.clarity.app.data.frameUrl
import net.robog.clarity.app.net.DeviceJwtSigner
import net.robog.clarity.app.net.KtorClarityApi
import net.robog.clarity.app.net.createHttpClient
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.app.ui.path.PATH_POLL
import net.robog.clarity.app.ui.path.PathRow
import net.robog.clarity.app.ui.path.PathTarget
import net.robog.clarity.app.ui.path.PathViewModel
import net.robog.clarity.app.ui.path.StepOutcome
import net.robog.clarity.app.ui.path.condenseReport
import net.robog.clarity.app.ui.path.confidenceText
import net.robog.clarity.app.ui.path.costText
import net.robog.clarity.app.ui.path.durationText
import net.robog.clarity.app.ui.path.frameDescription
import net.robog.clarity.app.ui.path.jevBox
import net.robog.clarity.app.ui.path.pathRows
import net.robog.clarity.app.ui.path.stepConfidence
import net.robog.clarity.app.ui.path.stepOutcome
import net.robog.clarity.app.ui.path.stepTitle
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.browser.BrowserStepView
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.path.AgentPath
import net.robog.clarity.shared.path.PathItem
import net.robog.clarity.shared.path.PathSubject
import net.robog.clarity.shared.path.PathTotals

private val at = Instant.parse("2026-10-27T22:28:00Z")

private fun step(id: Long, url: String, title: String, gate: String = "passed", tool: String = "act", intent: String = "the Sign in link") =
    BrowserStepView(id, "h1", id.toInt(), at, tool, url, title, intent, null, gate = gate, hitRole = "link", hitName = "Sign in")

private fun path(items: List<PathItem>, live: Boolean, next: Int? = null) =
    AgentPath(PathSubject(PathSubject.Kind.TASK, "t1", "Check the order", "working"), PathTotals(items.size, 1000), items, next, live)

class PathPresentationTest {
    @Test fun gatesBecomeOutcomes() {
        assertEquals(StepOutcome.PASSED, stepOutcome("passed (jev sign_in p=0.99)"))
        assertEquals(StepOutcome.REFUSED, stepOutcome("code_guard"))
        assertEquals(StepOutcome.REFUSED, stepOutcome("refused"))
        assertEquals(StepOutcome.HELD, stepOutcome("held"))
        assertEquals(StepOutcome.NOT_FOUND, stepOutcome("not_found"))
        assertEquals(StepOutcome.FAILED, stepOutcome("error"))
    }

    @Test fun stepsAreSaidInPlainWords() {
        assertEquals("Tapped the Sign in link", stepTitle(step(1, "https://shop.clarity.test/", "Garden Shop")))
        assertEquals("Wanted to tap the Resend button", stepTitle(step(1, "u", "t", gate = "code_guard", intent = "the Resend button")))
        assertEquals("Typed the sign-in code", stepTitle(step(1, "u", "t", tool = "sign_in", intent = "code")))
        assertEquals("Opened shop.clarity.test", stepTitle(step(1, "u", "t", tool = "go", intent = "https://shop.clarity.test/")))
        assertEquals("Before: tapped the Sign in link, on link · Sign in", frameDescription(step(1, "u", "t"), after = false))
    }

    @Test fun reportsAreCondensedAndJevSpeaksInWords() {
        assertEquals(
            "Page changed: shop.clarity.test/ → login.clarity.test/signin · Looks like \"a sign-in form\" happened (Jev is very sure, 99%)",
            condenseReport("Page changed: https://shop.clarity.test/ → https://login.clarity.test/signin / Title: \"Garden Shop\" → \"Clarity ID\" / On the screen: looks like \"a sign-in form\" happened (Jev p=0.99)."),
        )
        assertEquals("Refused: a code was sent at 3:29 pm", condenseReport("Refused: a code was sent at 3:29 pm – use ask_code (then type it with act or sign_in)."))
        assertNull(condenseReport(" "))
        assertEquals("Jev is fairly sure, 88%", confidenceText(0.88))
    }

    @Test fun jevsBoxAndConfidenceComeFromItsAnswers() {
        val s = step(1, "u", "t").copy(
            box = buildJsonObject { put("top", 0.36); put("bottom", 0.42); put("left", 0.3); put("right", 0.37) },
            jev = buildJsonObject { put("verify", buildJsonObject { put("answers", buildJsonObject { put("happened", 0.99) }) }) },
        )
        assertEquals(0.36f, jevBox(s)!!.top)
        assertEquals(0.99, stepConfidence(s))
        assertNull(jevBox(s.copy(box = buildJsonObject { put("top", 0.5); put("bottom", 0.2); put("left", 0.1); put("right", 0.2) })))
    }

    @Test fun browserStepsAreGroupedByTab() {
        val items = listOf(
            PathItem.Browser(at, "h1", 1, step(1, "https://shop.clarity.test/", "Garden Shop")),
            PathItem.Browser(at, "h1", 1, step(2, "https://shop.clarity.test/", "Garden Shop")),
            PathItem.Browser(at, "h1", 1, step(3, "https://login.clarity.test/signin", "Clarity ID")),
            PathItem.Browser(at, "h1", 1, step(4, "https://shop.clarity.test/account", "Garden Shop")),
        )
        val tabs = pathRows(items).filterIsInstance<PathRow.Tab>().map { it.label }
        assertEquals(listOf("On Garden Shop", "Opened the sign-in tab · Clarity ID", "Back on Garden Shop"), tabs)
    }

    @Test fun totalsReadAtAGlance() {
        assertEquals("$0.0019", costText(1_916))
        assertEquals("$0.04", costText(41_200))
        assertNull(costText(null))
        assertEquals("38 s", durationText(38_000))
        assertEquals("3 min", durationText(204_000))
        assertEquals("1 h 5 min", durationText(3_900_000))
        assertEquals("https://c.example/api/v1/browser/steps/7/after.png", frameUrl("https://c.example/", 7, after = true))
    }
}

private class FakePaths(var pages: MutableList<AgentPath>) : PathRepository {
    val calls = mutableListOf<Pair<Int, Int>>()
    override suspend fun task(id: TaskId, from: Int, limit: Int): ApiResult<AgentPath> {
        calls += from to limit
        return ApiResult.Success(if (pages.size > 1) pages.removeAt(0) else pages.first())
    }
    override suspend fun run(id: String, from: Int, limit: Int) = task(TaskId(id), from, limit)
    override suspend fun base(): String = "https://c.example"
}

@OptIn(ExperimentalCoroutinesApi::class)
class PathViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun main() = Dispatchers.setMain(dispatcher)
    @AfterTest fun reset() = Dispatchers.resetMain()

    private val said = PathItem.Said(at, "lead", 0, "On it.")

    @Test fun aLivePathIsFetchedAgainUntilItSettles() = runTest(dispatcher) {
        val repo = FakePaths(mutableListOf(path(listOf(said), live = true), path(listOf(said, said), live = true), path(listOf(said, said, said), live = false)))
        val vm = PathViewModel(repo, ScreenFeed())
        vm.open(PathTarget.Task(TaskId("t1")))
        advanceUntilIdle()
        assertEquals("https://c.example", vm.state.value.frameBase)
        val polling = launch { vm.poll() }
        advanceTimeBy(PATH_POLL * 3 + PATH_POLL / 2)
        advanceUntilIdle()
        assertTrue(polling.isCompleted, "polling stops once the path is no longer live")
        assertEquals(3, vm.state.value.rows.size)
        assertEquals(3, repo.calls.size)
    }

    @Test fun moreIsAppendedAndARefreshKeepsWhatIsOpen() = runTest(dispatcher) {
        val repo = FakePaths(mutableListOf(path(listOf(said), live = false, next = 1), path(listOf(said), live = false), path(listOf(said, said), live = false)))
        val vm = PathViewModel(repo, ScreenFeed())
        vm.open(PathTarget.Task(TaskId("t1")))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.path!!.items.size)
        assertEquals(1 to 100, repo.calls[1])
        vm.refresh()
        advanceUntilIdle()
        assertEquals(0 to 100, repo.calls[2])
    }
}

class PathApiTest {
    @Test fun pathsAreFetchedPagedFromTheirRoutes() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val body = ClarityJson.encodeToString(AgentPath.serializer(), path(emptyList(), live = false))
        val store = FakeSessionStore(signedIn())
        val engine = MockEngine { request -> requests += request; respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }
        val api = KtorClarityApi(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store, engine), store)
        assertTrue(api.taskPath(TaskId("t1"), 100, 50) is ApiResult.Success)
        assertTrue(api.runPath("r1", 0, 100) is ApiResult.Success)
        assertEquals("/api/v1/tasks/t1/path", requests[0].url.encodedPath)
        assertEquals("100", requests[0].url.parameters["from"])
        assertEquals("50", requests[0].url.parameters["limit"])
        assertEquals("/api/v1/runs/r1/path", requests[1].url.encodedPath)
        assertEquals("https://clarity.example", api.baseUrl())
    }
}
