package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage

class ModelCostsTest : FunSpec({
    test("Opus: input, output, cache reads and both cache writes at their prices") {
        // 1M of each at $4 in, $20 out, $0.20 read, $5 (1.25x) 5m write, $8 (2x) 1h write
        val u = Usage(1_000_000, 1_000_000, cacheCreationInputTokens = 2_000_000, cacheReadInputTokens = 1_000_000, cacheCreation5mTokens = 1_000_000, cacheCreation1hTokens = 1_000_000)
        costMicros("claude-opus-5-5", u) shouldBe 37_200_000L
    }

    test("an unsplit cache write counts as 5 minute writes") {
        cacheWrites(Usage(cacheCreationInputTokens = 500)) shouldBe (500L to 0L)
        cacheWrites(Usage(cacheCreationInputTokens = 500, cacheCreation1hTokens = 200)) shouldBe (300L to 200L)
    }

    test("Sonnet and Haiku prices; Haiku's long-prompt price; unknown models have no cost") {
        costMicros("claude-sonnet-5-5", Usage(1_000_000, 100_000)) shouldBe 3_000_000L
        costMicros("claude-haiku-5-5", Usage(10_000, 1_000)) shouldBe 1_500L
        costMicros("claude-haiku-5-5", Usage(200_000, 0)) shouldBe 100_000L
        costMicros("some-other-model", Usage(1, 1)) shouldBe null
    }

    test("a cost the provider reports wins over the price table; an unknown model still has its tokens") {
        costMicros("anthropic/claude-haiku-5.5", Usage(10_000, 1_000, reportedCostMicros = 1_234)) shouldBe 1_234L
        costMicros("claude-opus-5-5", Usage(1_000_000, 0, reportedCostMicros = 7)) shouldBe 7L
        costMicros("vendor/unknown", Usage(10_000, 1_000)) shouldBe null
        (Usage(1, 1, reportedCostMicros = 5) + Usage(1, 1)).reportedCostMicros shouldBe 5L
        (Usage(1, 1) + Usage(1, 1)).reportedCostMicros shouldBe null
    }

    test("every call through the gateway is recorded, and a failing recorder never fails the call") {
        val response = ModelResponse(listOf(Block.Text("hi")), StopReason.END_TURN, Usage(10, 5))
        val inner = object : ModelGateway {
            override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit) = response
        }
        val seen = mutableListOf<Pair<String, Usage>>()
        val request = ModelRequest("claude-opus-5-5", emptyList(), emptyList(), emptyList(), 10, null)
        RecordingGateway(inner, { m, u, _ -> seen += m to u }).generate(request) shouldBe response
        RecordingGateway(inner, { m, u, _ -> seen += m to u }).complete(request) shouldBe response
        seen shouldBe listOf("claude-opus-5-5" to Usage(10, 5), "claude-opus-5-5" to Usage(10, 5))
        RecordingGateway(inner, { _, _, _ -> error("db down") }).generate(request) shouldBe response
    }
})
