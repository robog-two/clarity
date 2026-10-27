package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.httpsProxyFromEnv
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The decision models the browser pilot asks: Jev ([jev], text and screenshots) and the text
 * decision model ([text], Decision-1). Null when unavailable (no key, decisions off): the pilot then
 * never acts on a guess, it reports back.
 */
interface BrowserModels {
    val jev: DecisionClient?
    val text: DecisionClient?
    val jevModel: String
    val textModel: String
}

/** [inner] with every answered call recorded in `model_call` (role `decision`) and every throw turned into an error. */
class RecordingDecisionClient(
    private val inner: DecisionClient,
    private val model: String,
    private val database: Database,
    private val clock: Clock,
) : DecisionClient {
    override suspend fun decide(state: JsonElement, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult> {
        val result = try {
            inner.decide(state, questions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DecisionError.Unreachable("decision call failed: ${e::class.simpleName}").left()
        }
        result.onRight { recordDecisionCall(database, clock, model, it.usage) }
        return result
    }
}

/** OpenRouter's decisions endpoint for both models, with zero data retention when routing asks for it. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OpenRouterBrowserModels(private val config: AgentConfig, private val database: Database, private val clock: Clock) : BrowserModels {
    override val jevModel: String = config.browser.jevModel
    override val textModel: String = config.decisions.model
    private val key = config.openRouterApiKey?.takeIf { it.isNotBlank() && config.decisions.enabled }

    private fun client(model: String, timeoutSeconds: Int): DecisionClient? = key?.let {
        RecordingDecisionClient(
            OpenRouterDecisionClient(
                it, config.openRouterBaseUrl, httpsProxyFromEnv(System.getenv()), model, zdr = config.openRouterRouting.zdr,
                timeout = timeoutSeconds.seconds,
            ),
            model, database, clock,
        )
    }

    // Every request with an image runs in TypeSafe's slower long-context mode.
    override val jev: DecisionClient? = client(jevModel, 40)
    override val text: DecisionClient? = client(textModel, 15)
}

/** Pure: a PNG as a Decisions image part (`{"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}`). */
fun imagePart(png: ByteArray): JsonObject = buildJsonObject {
    put("type", "image_url")
    putJsonObject("image_url") { put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)) }
}

/** One request to a decision model: its state and its questions by id. */
data class DecisionAsk(val state: JsonElement, val questions: Map<String, DecisionQuestion>)

private fun state(vararg parts: Any): JsonArray = JsonArray(parts.map { if (it is JsonElement) it else JsonPrimitive(it.toString()) })

const val Q_VISIBLE = "visible"
const val Q_TARGET = "target"
const val Q_STEP = "step"
const val Q_RESEND = "resend"
const val Q_HAPPENED = "happened"
const val Q_SIGN_IN = "sign_in"
const val Q_PICK = "pick"

/** Pure: "is [intent] on the screen, and where" in one Jev call ([Q_VISIBLE] noul plus [Q_TARGET] box). */
fun groundingAsk(intent: String, png: ByteArray): DecisionAsk = DecisionAsk(
    state("Screenshot of a browser tab, as the person sees it:", imagePart(png)),
    mapOf(
        Q_VISIBLE to DecisionQuestion.Noul(
            "Is this visible on the page in the screenshot: $intent?",
            whenTrue = "It is visible in the screenshot: $intent",
            whenFalse = "It is not visible in the screenshot, or only something else like it",
        ),
        Q_TARGET to DecisionQuestion.Box("$intent, in the screenshot of the browser tab"),
    ),
)

val STEP_OPTIONS: Map<String, String> = linkedMapOf(
    JevStep.BROWSE.wire to "Ordinary browsing: it opens a page, link, menu, tab, search or filter, picks an option, closes a notice, or fills an ordinary field. It sends, buys or changes nothing.",
    JevStep.SIGN_IN.wire to "A step of signing in: entering the login, choosing where a sign-in code goes, entering or verifying that code, keeping the device signed in.",
    JevStep.COMMIT.wire to "It commits the person: sends a message or form to someone, buys, books, pays, orders, deletes, subscribes, accepts terms, uploads, or changes account or security settings.",
    JevStep.UNSURE.wire to "It cannot be told from the screenshot what the step does.",
)

/**
 * Pure: Jev's check of one step ([Q_STEP] choice, and with [askResend] the code guard's [Q_RESEND]
 * noul). [png] has the target outlined in red; without it the step is judged from its text alone.
 */
fun approvalAsk(description: String, task: String?, png: ByteArray?, askResend: Boolean): DecisionAsk {
    val parts = buildList<Any> {
        add("An assistant is about to take one step in a web browser for a person.")
        task?.let { add("The person's task: ${it.take(600)}") }
        add("The step: $description")
        if (png != null) {
            add("The element it would act on is outlined with a thick red frame in this screenshot:")
            add(imagePart(png))
        }
    }
    val questions = buildMap {
        put(Q_STEP, DecisionQuestion.Choice("What does this step do for the person?", STEP_OPTIONS))
        if (askResend) {
            put(
                Q_RESEND,
                DecisionQuestion.Noul(
                    "A one-time sign-in code was already sent to the person. Would this step send another one-time code, or restart the sign-in?",
                    whenTrue = "It would send another code, or start the sign-in over",
                    whenFalse = "It sends no code: it verifies the code, browses, or does something else",
                ),
            )
        }
    }
    return DecisionAsk(JsonArray(parts.map { if (it is JsonElement) it else JsonPrimitive(it.toString()) }), questions)
}

/** Pure: "did [expect] happen" on the screenshot after a step ([Q_HAPPENED] noul). */
fun verifyAsk(expect: String, description: String, png: ByteArray): DecisionAsk = DecisionAsk(
    state("A browser tab right after this step: $description", imagePart(png)),
    mapOf(Q_HAPPENED to DecisionQuestion.Noul("Did this happen: $expect?", whenTrue = "The screenshot shows: $expect", whenFalse = "The screenshot does not show that")),
)

val SIGN_IN_OPTIONS: Map<String, String> = linkedMapOf(
    "signed_in" to "The person is signed in: an account page, their name, orders, or a sign-out link.",
    "needs_code" to "The page asks for a one-time code that was sent by SMS, email or an app.",
    "choose_method" to "The page asks how to send a sign-in code (SMS, email, call).",
    "needs_login" to "The page asks for the email, username or password.",
    "error" to "The page shows an error: wrong password or code, locked, blocked, or try later.",
    "other" to "Something else.",
)

/** Pure: where the sign-in on [domain] stands, from the screenshot ([Q_SIGN_IN] choice). */
fun signInAsk(domain: String, png: ByteArray): DecisionAsk = DecisionAsk(
    state("A browser tab during a sign-in to $domain:", imagePart(png)),
    mapOf(Q_SIGN_IN to DecisionQuestion.Choice("What does this page show about signing in to $domain?", SIGN_IN_OPTIONS)),
)

/** Pure: which of [options] (ref to its `look` line) is [intent], for the text model ([Q_PICK] choice). */
fun pickAsk(intent: String, options: List<SeenElement>): DecisionAsk = DecisionAsk(
    buildJsonObject {
        put("wanted", intent)
        put("elements", JsonArray(options.map { JsonPrimitive(it.line()) }))
    },
    mapOf(Q_PICK to DecisionQuestion.Choice("Which element of the page is the one wanted?", options.associate { it.ref to it.line() })),
)

/** Jev's grounding: how likely the target is visible, and its box. */
data class Grounding(val pVisible: Double, val box: DecisionAnswer.Box?)

/** Pure: the grounding answers, or null when they are not there (a refusal). */
fun groundingOf(result: DecisionResult): Grounding? {
    val p = (result.answers[Q_VISIBLE] as? DecisionAnswer.Noul)?.probability ?: return null
    return Grounding(p, result.answers[Q_TARGET] as? DecisionAnswer.Box)
}

/** Pure: Jev's view of the step and its resend probability (null when not asked). A refusal is a failure. */
fun approvalOf(result: DecisionResult): Pair<JevView, Double?> {
    val choice = result.answers[Q_STEP] as? DecisionAnswer.Choice ?: return JevView(null, 0.0, "it declined to answer") to null
    val step = JevStep.of(choice.choice)
    val p = choice.probabilities[choice.choice] ?: choice.confidence ?: 0.0
    return JevView(step, p) to (result.answers[Q_RESEND] as? DecisionAnswer.Noul)?.probability
}

/** Pure: a readable summary of a decision result for the audit trail (answers with probabilities, usage). */
fun answersJson(result: DecisionResult, millis: Long): JsonObject = buildJsonObject {
    result.model?.let { put("model", it) }
    put("ms", millis)
    result.usage.costMicros?.let { put("cost_micros", it) }
    putJsonObject("answers") {
        result.answers.forEach { (id, a) ->
            when (a) {
                is DecisionAnswer.Noul -> put(id, a.probability)
                is DecisionAnswer.Choice -> putJsonObject(id) { put("choice", a.choice); a.probabilities.forEach { (k, v) -> put(k, v) } }
                is DecisionAnswer.Box -> putJsonObject(id) { put("top", a.top); put("bottom", a.bottom); put("left", a.left); put("right", a.right); a.confidence?.let { put("confidence", it) } }
                DecisionAnswer.Refusal -> put(id, "refusal")
            }
        }
    }
}
