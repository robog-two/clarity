package net.robog.clarity.backend.evals.live

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import kotlin.time.Duration.Companion.seconds
import net.robog.clarity.backend.agent.DecisionAnswer
import net.robog.clarity.backend.agent.FailureGate
import net.robog.clarity.backend.agent.GateKind
import net.robog.clarity.backend.agent.OpenRouterDecisionClient
import net.robog.clarity.backend.agent.failureGateState
import net.robog.clarity.backend.config.DEFAULT_JEV_MODEL
import net.robog.clarity.backend.config.DEFAULT_OPENROUTER_BASE_URL

private fun key(): String? = System.getenv("OPENROUTER_API_KEY")?.takeIf(String::isNotBlank)
    ?: System.getenv("OPENROUTER_API_KEY_FILE")?.let { runCatching { java.io.File(it).readText().trim() }.getOrNull() }?.takeIf(String::isNotBlank)

/**
 * Opt-in like [LiveDecisionTest] (`-Pclarity.liveDecisions` and a key): six real Jev calls on
 * synthetic text, about $0.00012 in all (2026-10-27: refused p=0.98 and 0.99; passed 0.02, 0.02, 0.30 for a plain outcome, 0.03). The failure gate's question against the real model: failure
 * stories and a brief with "known issues" are refused at the default 0.6, ordinary memory, a plain
 * outcome and a clean brief with a flow pass. Prints each p and the reported cost.
 *
 *     OPENROUTER_API_KEY_FILE=... ./gradlew :backend:test --tests net.robog.clarity.backend.evals.live.LiveFailureGateTest -Pclarity.liveDecisions
 */
@EnabledIf(LiveDecisionsEnabled::class)
class LiveFailureGateTest : FunSpec({
    val cases = listOf(
        Triple(GateKind.MEMORY, "Ulta lookup still broken (verification doesn't stick after code)", true),
        Triple(
            GateKind.BRIEF,
            "Check Ulta salon availability for a haircut in Ithaca this Saturday; view only, do not book. " +
                "Known issues from earlier runs: after VERIFY the site sometimes redirects to a blank page. If that happens, " +
                "navigate to beautyservices.ulta.com again and sign in once more.",
            true,
        ),
        Triple(GateKind.MEMORY, "Morning call: one thing today = finish the ML report", false),
        Triple(GateKind.MEMORY, "Sam prefers short replies, one or two sentences at most", false),
        Triple(GateKind.MEMORY, "Ulta lookup for Saturday didn't finish; Sam said to try again tomorrow", false),
        Triple(
            GateKind.BRIEF,
            "Goal: find Saturday haircut availability at the Ulta salon in Ithaca on ulta.com. Constraints: view only; " +
                "stop before booking; do not enter payment details. Flow: select the Ithaca store → go to the bookings page → " +
                "see availability → read the full page (scroll to the bottom) → report back the open times for Saturday.",
            false,
        ),
    )

    test("the failure gate on real Jev") {
        val jev = OpenRouterDecisionClient(
            key()!!, DEFAULT_OPENROUTER_BASE_URL, proxy = null, model = DEFAULT_JEV_MODEL, zdr = true, timeout = 60.seconds, retryDelays = emptyList(),
        )
        var micros = 0L
        val wrong = mutableListOf<String>()
        for ((kind, text, refuse) in cases) {
            val question = if (kind == GateKind.MEMORY) FailureGate.MEMORY_QUESTION else FailureGate.BRIEF_QUESTION
            val result = jev.decide(failureGateState(kind, text), mapOf(FailureGate.Q_FAILURE to question))
            val answer = result.getOrNull()
            val p = (answer?.answers?.get(FailureGate.Q_FAILURE) as? DecisionAnswer.Noul)?.probability
            micros += answer?.usage?.costMicros ?: 0
            println("failure gate live: $kind p=$p expect=${if (refuse) "refuse" else "pass"} usage=${answer?.usage} ${result.leftOrNull() ?: ""} :: ${text.take(70)}")
            if (p == null || (p >= 0.6) != refuse) wrong += "$kind p=$p ${text.take(50)}"
        }
        println("failure gate live: total cost \$${micros / 1_000_000.0} for ${cases.size} calls")
        wrong.shouldBeEmpty()
    }
})
