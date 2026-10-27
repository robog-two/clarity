package net.robog.clarity.backend.evals.live

import io.kotest.core.annotation.Condition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.DecisionAnswer
import net.robog.clarity.backend.agent.OpenRouterDecisionClient
import net.robog.clarity.backend.config.DEFAULT_OPENROUTER_BASE_URL
import net.robog.clarity.backend.scheduler.ACT_NOW
import net.robog.clarity.backend.scheduler.REVIEW_GATE_QUESTIONS
import net.robog.clarity.backend.scheduler.ReviewEvent
import net.robog.clarity.backend.scheduler.ReviewMessage
import net.robog.clarity.backend.scheduler.ReviewSnapshot
import net.robog.clarity.backend.scheduler.reviewGateState

/**
 * Opt-in: runs only with `-Pclarity.liveDecisions`, an `OPENROUTER_API_KEY` (or `OPENROUTER_API_KEY_FILE`) in the environment and no
 * `CI` variable. Never part of CI. Three real calls on SYNTHETIC text only (no real data), no
 * retries, about $0.00002 in all: the review gate's request and response shapes against Decision-1
 * with zero-data-retention preferences, and the same System One request against TypeSafe's Jev.
 *
 *     OPENROUTER_API_KEY=... ./gradlew :backend:test --tests '*LiveDecisionTest' -Pclarity.liveDecisions
 */
class LiveDecisionsEnabled : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean =
        System.getProperty("clarity.liveDecisions") == "true" &&
            liveKey() != null &&
            System.getenv("CI").isNullOrBlank()
}

/** `OPENROUTER_API_KEY`, else the file named by `OPENROUTER_API_KEY_FILE`. */
private fun liveKey(): String? = System.getenv("OPENROUTER_API_KEY")?.takeIf(String::isNotBlank)
    ?: System.getenv("OPENROUTER_API_KEY_FILE")?.let { runCatching { java.io.File(it).readText().trim() }.getOrNull() }?.takeIf(String::isNotBlank)

private val now = Instant.parse("2026-10-26T14:00:00Z")

private fun client(model: String) = OpenRouterDecisionClient(
    liveKey()!!, DEFAULT_OPENROUTER_BASE_URL, proxy = null, model = model, zdr = true,
    timeout = 30.seconds, retryDelays = emptyList(),
)

private val newsletterOnly = ReviewSnapshot(
    messages = listOf(
        ReviewMessage(now - 30.minutes, "email", "Garden Shop <deals@shop.example>", false, "Weekly deals", "Save 20% on hoses this week. Unsubscribe any time."),
    ),
)

private val urgent = ReviewSnapshot(
    messages = listOf(
        ReviewMessage(
            now - 10.minutes, "email", "Sam Rivera <sam@example.org>", false, "Pick-up moved to 3pm today",
            "Hi! School closes early today, can you confirm by 1pm that you can pick Ada up at 3pm instead of 5?",
        ),
    ),
    events = listOf(ReviewEvent(ReviewEvent.Change.STARTING_SOON, "Dentist", now + 1.hours, now + 2.hours, false, "Main St 4", now - 1.hours)),
)

@EnabledIf(LiveDecisionsEnabled::class)
class LiveDecisionTest : FunSpec({
    fun p(answers: Map<String, DecisionAnswer>) = answers.getValue(ACT_NOW).shouldBeInstanceOf<DecisionAnswer.Noul>().probability

    test("Decision-1 with zero data retention: a newsletter can wait, a same-day request cannot") {
        val decision1 = client("microsoft/microsoft-decision-1")
        val calm = decision1.decide(reviewGateState(newsletterOnly, now - 2.hours, now, TimeZone.UTC), REVIEW_GATE_QUESTIONS).getOrNull()!!
        val busy = decision1.decide(reviewGateState(urgent, now - 2.hours, now, TimeZone.UTC), REVIEW_GATE_QUESTIONS).getOrNull()!!
        println("decision-1: calm p=${p(calm.answers)} ${calm.answers} provider=${calm.provider} usage=${calm.usage}")
        println("decision-1: busy p=${p(busy.answers)} ${busy.answers} provider=${busy.provider} usage=${busy.usage}")
        (p(calm.answers) < 0.3) shouldBe true
        (p(busy.answers) >= 0.3) shouldBe true
    }

    test("the same System One request works with TypeSafe Jev") {
        val jev = client("typesafe/jev-1.14").decide(reviewGateState(urgent, now - 2.hours, now, TimeZone.UTC), REVIEW_GATE_QUESTIONS)
        println("jev-1.14: $jev")
        jev.isRight() shouldBe true
    }
})
