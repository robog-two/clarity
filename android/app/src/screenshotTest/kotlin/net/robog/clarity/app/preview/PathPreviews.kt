package net.robog.clarity.app.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.app.ui.path.PathContent
import net.robog.clarity.app.ui.path.PathUiState
import net.robog.clarity.app.ui.path.pathRows
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.browser.BrowserStepView
import net.robog.clarity.shared.path.AgentPath
import net.robog.clarity.shared.path.Outcome
import net.robog.clarity.shared.path.PathItem
import net.robog.clarity.shared.path.PathSubject
import net.robog.clarity.shared.path.PathTotals

/*
 * The Agent's Path on the test site: the Garden Shop, the Clarity ID sign-in, the SMS code, signed
 * in. The screenshots are the real frames of the browser pilot's end-to-end run.
 */

private const val BASE = "preview://clarity"
private const val LEAD = "lead"
private const val BROWSER = "5d1c2e40-8a7b-4c39-9f0e-2b6f1a9c7d31"
private const val MAIL = "a93f07b2-1e4d-4c8a-b5f6-7d2e9c0a4b18"
private val zone = TimeZone.of("America/Los_Angeles")
private val t0 = Instant.parse("2026-10-27T22:28:00Z") // 3:28 pm in Los Angeles

/** The frames each step id has, by file under `screenshotTest/resources/path/`. */
private val frames = mapOf(
    2L to ("garden-shop.png" to "clarity-id-sign-in.png"),
    3L to ("clarity-id-sign-in.png" to null),
    7L to (null to "signed-in.png"),
)

private fun png(name: String): ByteArray? {
    PathPreviewFrames::class.java.classLoader?.getResourceAsStream("path/$name")?.use { return it.readBytes() }
    return generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "src/screenshotTest/resources/path/$name"), File(it, "app/src/screenshotTest/resources/path/$name")) }
        .firstOrNull { it.isFile }?.readBytes()
}

private object PathPreviewFrames {
    val handler = AsyncImagePreviewHandler { request ->
        val url = request.data as? String
        val id = url?.substringAfter("/browser/steps/")?.substringBefore('/')?.toLongOrNull()
        val name = frames[id]?.let { (before, after) -> if (url!!.endsWith("after.png")) after else before }
        val bytes = name?.let(::png)
        (bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
    }
}

private fun jev(vararg calls: Pair<String, JsonObject>) = buildJsonObject { calls.forEach { (k, v) -> put(k, v) } }
private fun answers(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject { put("answers", buildJsonObject(block)) }
private fun box(top: Double, bottom: Double, left: Double, right: Double) =
    buildJsonObject { put("top", top); put("bottom", bottom); put("left", left); put("right", right); put("confidence", 0.82) }

private fun step(
    id: Long, at: Duration, tool: String, url: String, title: String, intent: String?, gate: String,
    hit: Pair<String, String>? = null, box: JsonObject? = null, jev: JsonObject? = null, report: String? = null,
    beforeWithheld: Boolean = false, afterWithheld: Boolean = false, cost: Long? = null,
) = PathItem.Browser(
    t0 + at, BROWSER, 1,
    BrowserStepView(
        id, BROWSER, id.toInt(), t0 + at, tool, url, title, intent, null,
        beforeShot = frames[id]?.first != null, beforeWithheld = beforeWithheld, box = box, hitRole = hit?.first, hitName = hit?.second, jev = jev,
        gate = gate, action = null, afterShot = frames[id]?.second != null, afterWithheld = afterWithheld, changeReport = report, costMicros = cost,
    ),
)

private val browserSteps = listOf(
    step(1, 50.seconds, "go", "https://shop.clarity.test/", "Garden Shop", "https://shop.clarity.test/", "passed", report = "Now on: Garden Shop — https://shop.clarity.test/ / Call look to see the page."),
    step(
        2, 62.seconds, "act", "https://shop.clarity.test/", "Garden Shop", "the Sign in link", "passed (jev sign_in p=0.88)",
        hit = "link" to "Sign in", box = box(0.36, 0.42, 0.30, 0.37),
        jev = jev("verify" to answers { put("happened", 0.99) }),
        report = "Page changed: https://shop.clarity.test/ → https://login.clarity.test/signin / Title: \"Garden Shop\" → \"Clarity ID\" / On the screen: looks like \"a sign-in form asking for an email address and a password\" happened (Jev p=0.99).",
        cost = 357,
    ),
    step(
        3, 80.seconds, "sign_in", "https://login.clarity.test/signin", "Clarity ID", "login", "passed", afterWithheld = true,
        report = "The site asks where to send a sign-in code (Jev p=1.00). Pick one with act, say act(intent=\"the SMS option\"…), continue with act, then call sign_in again for the code.",
        cost = 121,
    ),
    step(
        4, 96.seconds, "act", "https://login.clarity.test/method", "Clarity ID", "the SMS option", "passed (jev sign_in p=0.99)",
        hit = "radio" to "SMS (••• •• 42)", box = box(0.41, 0.47, 0.30, 0.71), beforeWithheld = true, afterWithheld = true,
        jev = jev("verify" to answers { put("happened", 1.0) }),
        report = "The element is now: checked / On the screen: looks like \"the SMS option is selected\" happened (Jev p=1.00).", cost = 358,
    ),
    step(
        5, 108.seconds, "act", "https://login.clarity.test/method", "Clarity ID", "the Continue button", "passed (jev sign_in p=0.99)",
        hit = "button" to "Continue", box = box(0.51, 0.56, 0.30, 0.38), beforeWithheld = true, afterWithheld = true,
        report = "Page changed: https://login.clarity.test/method → https://login.clarity.test/code / A one-time-code field is now on the page. / The site says it sent a sign-in code. Use ask_code now; do not press anything that sends another.",
        cost = 357,
    ),
    step(
        6, 150.seconds, "act", "https://login.clarity.test/code", "Clarity ID", "the Resend button", "code_guard",
        hit = "button" to "Resend", box = box(0.47, 0.52, 0.30, 0.43), beforeWithheld = true,
        jev = jev("approve" to answers { put("step", buildJsonObject { put("choice", "sign_in"); put("sign_in", 0.93) }) }),
        report = "Refused: a code was sent at 3:29 pm – use ask_code (then type it with act or sign_in).", cost = 244,
    ),
    step(
        7, 170.seconds, "sign_in", "https://login.clarity.test/code", "Clarity ID", "code", "passed", beforeWithheld = true,
        report = "Back on Garden Shop / Signed in to login.clarity.test (Jev p=1.00).", cost = 122,
    ),
)

private fun action(at: Duration, run: String, depth: Int, tool: String, line: String, detail: String? = null, outcome: Outcome = Outcome.OK) =
    PathItem.Action(t0 + at, run, depth, tool, line, detail, outcome)

private val browserHelperItems: List<PathItem> = buildList {
    add(action(40.seconds, BROWSER, 1, "list_credentials", "Checked saved logins"))
    addAll(browserSteps.take(5))
    add(action(120.seconds, BROWSER, 1, "ask_code", "Asked you for a code"))
    addAll(browserSteps.drop(5))
    add(PathItem.Said(t0 + 185.seconds, BROWSER, 1, "Signed in. The account shows two orders; the seed order is marked shipped."))
    add(PathItem.Finished(t0 + 190.seconds, BROWSER, 1, true, "Signed in to the Garden Shop as Dana. Two orders; the seed order (Oct 24) shipped Monday."))
}

private val mailHelperItems: List<PathItem> = listOf(
    action(35.seconds, MAIL, 1, "list_messages", "Read 3 messages", "orders@shop.clarity.test"),
    PathItem.Said(t0 + 44.seconds, MAIL, 1, "The shipping mail is from Monday: parcel post, arriving Thursday."),
    PathItem.Finished(t0 + 46.seconds, MAIL, 1, true, "Shipped Monday by parcel post; it should arrive Thursday."),
)

private fun helper(at: Duration, id: String, task: String, status: String, steps: Int, browser: Int) =
    PathItem.Helper(t0 + at, LEAD, 0, id, task, status, "helper", steps, browser)

private val finishedItems: List<PathItem> = buildList {
    add(PathItem.Heard(t0, LEAD, 0, PathItem.Heard.Source.PERSON, "Can you sign in to the Garden Shop and see if my seed order shipped?"))
    add(PathItem.Said(t0 + 8.seconds, LEAD, 0, "I’ll check the shop and your mail at the same time."))
    add(action(10.seconds, LEAD, 0, "update_task", "Added a task", "Check the Garden Shop seed order"))
    add(helper(14.seconds, MAIL, "Find the shipping confirmation for the Garden Shop seed order in the mail", "done", 2, 0))
    addAll(mailHelperItems)
    add(helper(20.seconds, BROWSER, "Sign in to shop.clarity.test with the saved Garden Shop login and read the order status", "done", 10, 7))
    addAll(browserHelperItems)
    add(PathItem.Heard(t0 + 192.seconds, LEAD, 0, PathItem.Heard.Source.HELPER, "A helper finished: Sign in to shop.clarity.test with the saved Garden Shop login"))
    add(action(200.seconds, LEAD, 0, "send_to_user", "Sent you a message", "Your seeds shipped Monday and should arrive Thursday."))
    add(action(204.seconds, LEAD, 0, "update_task", "Updated the task", "Check the Garden Shop seed order · done"))
}

private fun subject(kind: PathSubject.Kind, id: String, title: String, status: String, detail: String?) =
    PathSubject(kind, id, title, status, t0, null, detail)

private val finished = AgentPath(
    subject(PathSubject.Kind.TASK, "t1", "Check the Garden Shop seed order", "done", "web · shipped Monday, arrives Thursday"),
    PathTotals(steps = finishedItems.count { it !is PathItem.Heard && it !is PathItem.Finished }, durationMillis = 204_000, modelCostMicros = 41_200, decisionCostMicros = 1_916),
    finishedItems,
)

// Running: the browser helper is at the code page, waiting for the code.
private val runningItems: List<PathItem> = finishedItems.take(3) +
    helper(20.seconds, BROWSER, "Sign in to shop.clarity.test with the saved Garden Shop login and read the order status", "running", 6, 5) +
    browserHelperItems.take(6) + action(120.seconds, BROWSER, 1, "ask_code", "Asked you for a code", outcome = Outcome.RUNNING)

private val running = AgentPath(
    subject(PathSubject.Kind.TASK, "t1", "Check the Garden Shop seed order", "working", "web · signing in"),
    PathTotals(steps = 9, durationMillis = 124_000, modelCostMicros = 18_400, decisionCostMicros = 1_193),
    runningItems,
    live = true,
)

// The browser helper's own path, stepped into from the task.
private val child = AgentPath(
    subject(PathSubject.Kind.RUN, BROWSER, "Sign in to shop.clarity.test with the saved Garden Shop login and read the order status", "done", null),
    PathTotals(steps = 10, durationMillis = 150_000, modelCostMicros = 22_800, decisionCostMicros = 1_916),
    browserHelperItems.map { it.atDepth0() },
)

private fun PathItem.atDepth0(): PathItem = when (this) {
    is PathItem.Action -> copy(depth = 0)
    is PathItem.Browser -> copy(depth = 0)
    is PathItem.Said -> copy(depth = 0)
    is PathItem.Finished -> copy(depth = 0)
    is PathItem.Heard -> copy(depth = 0)
    is PathItem.Helper -> copy(depth = 0)
}

private fun state(path: AgentPath) = PathUiState(loading = false, path = path, rows = pathRows(path.items), frameBase = BASE)

@Composable
private fun PathFrame(dark: Boolean, path: AgentPath, backLabel: String = "Today", framePage: Int = 0) = ClarityTheme(darkTheme = dark) {
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides PathPreviewFrames.handler) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PathContent(state(path), {}, backLabel, {}, {}, {}, {}, zone = zone, initialFramePage = framePage)
        }
    }
}

// A finished task: the lead's turn, a mail helper and a browser helper (a withheld frame, the code guard's refusal).
@PreviewTest @Preview(widthDp = 412, heightDp = 4200) @Composable fun TaskPathFinishedLight() = PathFrame(false, finished)
@PreviewTest @Preview(widthDp = 412, heightDp = 4200) @Composable fun TaskPathFinishedDark() = PathFrame(true, finished)
// The top of the same thread as it first opens.
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun TaskPathTopLight() = PathFrame(false, finished)
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable fun TaskPathTopDark() = PathFrame(true, finished)
// Still working: the helper waits for the code; the path refreshes itself.
@PreviewTest @Preview(widthDp = 412, heightDp = 2300) @Composable fun TaskPathRunningLight() = PathFrame(false, running)
@PreviewTest @Preview(widthDp = 412, heightDp = 2300) @Composable fun TaskPathRunningDark() = PathFrame(true, running)
// Stepped into the browser helper; its frames on the "after" side.
@PreviewTest @Preview(widthDp = 412, heightDp = 3400) @Composable fun RunPathLight() = PathFrame(false, child, backLabel = "Back")
@PreviewTest @Preview(widthDp = 412, heightDp = 3400) @Composable fun RunPathDark() = PathFrame(true, child, backLabel = "Back")
@PreviewTest @Preview(widthDp = 412, heightDp = 3400) @Composable fun RunPathAfterFramesLight() = PathFrame(false, child, backLabel = "Back", framePage = 1)
